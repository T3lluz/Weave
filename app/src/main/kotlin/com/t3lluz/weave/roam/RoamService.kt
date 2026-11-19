package com.t3lluz.weave.roam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.t3lluz.weave.MainActivity
import com.t3lluz.weave.R
import com.t3lluz.weave.data.link.LinkAudio
import com.t3lluz.weave.data.link.LinkPrefs
import com.t3lluz.weave.data.link.LinkProtocol
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import javax.inject.Inject

/**
 * The player: with the earbuds on this phone, it plays the computer's sound through them, with
 * the screen off and Weave closed. It starts when the computer hands the earbuds over
 * ([ACTION_TAKE], sent by [RoamShell] a moment before they connect), or when they connect here
 * by hand ([RoamReceiver]). It ends when they leave.
 *
 * It never plays from the phone's own speaker. Nothing is played before the earbuds are an
 * output here, the sound is pinned to them, and it stops the instant they go.
 */
@AndroidEntryPoint
class RoamService : Service(), RoamLine.Listener {
    @Inject lateinit var linkPrefs: LinkPrefs
    @Inject lateinit var roamPrefs: RoamPrefs

    private val main = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var line: RoamLine? = null
    private var audio: LinkAudio? = null
    private val beacon by lazy { RoamBeacon(this) }

    /** The earbuds this run is about, and they as an output of this phone, once they are one. */
    private var target: String? = null
    private var output: AudioDeviceInfo? = null

    /** The sound's decoder setup, kept for the moment the earbuds arrive. */
    @Volatile private var setup: ByteArray? = null
    @Volatile private var playing = false

    /** Asked for by hand in Weave: the computer sends its sound whether or not the earbuds just left it. */
    private var forced = false

    /** The computer has been told once that the earbuds are here, and has sent its sound for them. */
    private var played = false

    /** The computer said it is taking the earbuds back: their leaving is no surprise. */
    private var releasing = false
    private var status: RoamStatus? = null
    private var computer = ""
    private var waitUntil = 0L
    private var offlineSince = 0L
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = look()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = look()
    }

    /** Android says the sound is about to come out of the speaker: it stops before it can. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            quiet()
            main.post { look() }
        }
    }

    private val networks = object : ConnectivityManager.NetworkCallback() {
        private var seen: Network? = null
        override fun onAvailable(network: Network) {
            // another network took over: the old connection is dead weight, the next one uses this
            if (seen != null && seen != network) line?.kick()
            seen = network
        }
    }

    private val watch = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val gaveUp = when {
                output == null && now > waitUntil -> "The earbuds didn't connect to this phone."
                offlineSince != 0L && now - offlineSince > OFFLINE_MOST_MS -> "The computer couldn't be reached."
                else -> null
            }
            if (gaveUp != null) end(gaveUp) else main.postDelayed(this, WATCH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        ensureChannel()
        audioManager.registerAudioDeviceCallback(devices, main)
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(networks) }
        computer = roamPrefs.settings.value.name.ifBlank { "the computer" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // first of all: a service started for the foreground has a few seconds to get there
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val action = intent?.action
        val address = intent?.getStringExtra(EXTRA_ADDRESS)?.uppercase()
        if (line == null && action in setOf(ACTION_STOP, ACTION_BACK, ACTION_TOGGLE)) {
            // a button of a notification that outlived its player
            end(null)
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_STOP -> {
                line?.send(JSONObject().put("t", "roam").put("stop", true))
                main.postDelayed({ end("Stopped.") }, 250)
                return START_NOT_STICKY
            }
            ACTION_BACK -> {
                line?.send(JSONObject().put("t", "roam").put("back", true))
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                line?.send(JSONObject().put("t", "media").put("cmd", "play"))
                return START_NOT_STICKY
            }
        }
        // (whoever starts this has already said which earbuds: the computer's helper, which only
        // the phone's shell can be, the receiver, which lets through the computer's own only, or
        // a button in Weave)
        if (roamPrefs.settings.value.key.isBlank()) roamPrefs.adopt(linkPrefs)   // turned on before the lab kept its own computer
        if (!roamPrefs.settings.value.on || roamPrefs.computer(linkPrefs) == null || address == null) {
            // not this phone's business: the lab is off, or it has no computer to play
            if (line == null) end(null)
            return START_NOT_STICKY
        }
        if (line != null && target != null && target != address && output != null) return START_NOT_STICKY   // busy with another pair
        target = address
        forced = forced || action == ACTION_LISTEN
        releasing = false
        waitUntil = SystemClock.elapsedRealtime() + if (action == ACTION_HAVE) HAVE_WAIT_MS else TAKE_WAIT_MS
        if (line == null) {
            acquireLocks()
            line = RoamLine(applicationContext, linkPrefs, roamPrefs, play = true, listener = this).also { it.start() }
            main.removeCallbacks(watch)
            main.postDelayed(watch, WATCH_MS)
        }
        publish()
        look()
        return START_NOT_STICKY
    }

    // ── the earbuds ──

    /** Whether the earbuds are an output of this phone now, and what follows from that. */
    private fun look() {
        val want = target ?: return
        val found = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && sameEarbuds(it.address, want) }
        when {
            found != null && output == null -> arrived(found)
            found == null && output != null -> left()
        }
    }

    private fun arrived(device: AudioDeviceInfo) {
        output = device
        releasing = false
        main.removeCallbacks(gone)
        // more sound in hand over mobile data, where it arrives in fits and starts
        val cell = line?.onCell() == true
        audio?.stop()
        raiseFromZero()
        // What the track holds is what the sound lags by, so it holds little: a tenth of a second
        // on Wi-Fi, as the remote's sound does, and more only over mobile data.
        audio = LinkAudio(
            bufferMs = if (cell) 260 else 100, startMs = if (cell) 120 else 40,
            // A computer mixed for earbuds it drives at full strength sends its sound 20 to 40 dB
            // under full, and the phone then turns it down again with its own volume: far too
            // quiet to hear. So it is brought all the way up to what the phone's own music
            // sounds like, from the first second, and the phone's volume keys are what sets
            // how loud it is, as for anything else the phone plays.
            liftMost = LIFT_MOST, liftFromMost = true,
        ).also {
            it.level = true
            it.metered = true
            it.speaker = device
        }
        main.removeCallbacks(meter)
        main.postDelayed(meter, METER_MS)
        playing = true
        RoamHub.playing = true
        setup?.let { audio?.feed(LinkProtocol.CONFIG, 0, it, 0, it.size) }
        sayHave()
        publish()
    }

    /** A phone kept at zero would play nothing at all: brought to a third, with Android's slider shown. */
    private fun raiseFromZero() {
        try {
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 || audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                val third = (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.35f).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, third, AudioManager.FLAG_SHOW_UI)
            }
        } catch (_: SecurityException) {
            // Do not disturb can forbid it; the volume keys still work
        }
    }

    private fun sayHave() {
        val want = target ?: return
        if (output == null) return
        // (a player that was already playing says so: a computer that restarted, or woke from
        // sleep, has forgotten the bridge and takes it up again on that word)
        if (line?.send(JSONObject().put("t", "roam").put("have", want).put("force", forced || played)) == true) played = true
    }

    /** Not a sound more: the earbuds are going or gone. */
    private fun quiet() {
        playing = false
        RoamHub.playing = false
        audio?.stop()
    }

    /**
     * Tells the computer what is coming out here: how loud the sound is after its lift, and
     * where the phone's own volume stands. `weave roam` shows it, so "is anything playing" has
     * an answer that doesn't take a pair of ears.
     */
    private val meter = object : Runnable {
        override fun run() {
            if (!playing) return
            val peak = audio?.peak ?: 0
            val index = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val most = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val report = JSONObject().put("peak", if (peak <= 0) -99.0 else Math.round(200.0 * kotlin.math.log10(peak / 32768.0)) / 10.0)
                .put("vol", index).put("max", most)
            audio?.lift?.let { report.put("lift", Math.round(200.0 * kotlin.math.log10(it.toDouble().coerceAtLeast(0.001))) / 10.0) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { report.put("db", Math.round(audioManager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) * 10.0) / 10.0) }
            }
            line?.send(JSONObject().put("t", "roam").put("meter", report))
            main.postDelayed(this, METER_MS)
        }
    }

    private val gone = Runnable { end(null) }

    private fun left() {
        quiet()
        output = null
        beacon.stop()
        if (!releasing) target?.let { line?.send(JSONObject().put("t", "roam").put("lost", it)) }
        publish()
        // a moment, in case they are only changing hands inside the phone; then there is nothing to play to
        main.removeCallbacks(gone)
        main.postDelayed(gone, GONE_WAIT_MS)
    }

    // ── the line ──

    override fun online(on: Boolean) {
        main.post {
            if (line == null) return@post
            offlineSince = if (on) 0L else SystemClock.elapsedRealtime()
            if (on) {
                line?.send(JSONObject().put("t", "audio").put("on", true))
                sayHave()
            }
            publish()
        }
    }

    override fun status(status: RoamStatus, release: Boolean) {
        main.post {
            if (line == null) return@post
            this.status = status
            roamPrefs.update { it.copy(on = status.on, mode = status.mode, sens = status.sens, earbuds = status.devices.ifEmpty { it.earbuds }) }
            if (release) releasing = true
            beacon.show(status.beacon.takeIf { playing })
            when {
                !status.on -> end(null)
                // the computer has them again, or was never missing them: nothing here to do
                output == null && status.state in setOf("armed", "idle") && SystemClock.elapsedRealtime() > waitUntil - SETTLED_MS -> end(null)
                // they are here, but the computer isn't sending for them (they never were its own)
                output != null && status.state == "idle" && !forced -> end(null)
                else -> publish()
            }
        }
    }

    override fun sound(flags: Int, ptsMs: Long, data: ByteArray, offset: Int, length: Int) {
        if (flags and LinkProtocol.CONFIG != 0) setup = data.copyOfRange(offset, offset + length)
        if (playing) audio?.feed(flags, ptsMs, data, offset, length)
    }

    override fun refused(why: String) {
        main.post { end(why) }
    }

    // ── showing it ──

    private fun publish() {
        val name = status?.device?.name ?: roamPrefs.settings.value.earbuds.firstOrNull { it.address == target }?.name ?: "Earbuds"
        val isOnline = line?.isOnline == true
        val note = when {
            output == null -> "Waiting for $name"
            !isOnline -> "Reaching $computer…"
            else -> "$name play $computer's sound"
        }
        RoamHub.player.update { RoamPlayer(running = true, playing = playing && isOnline, online = isOnline, earbuds = name, note = note) }
        if (foreground) {
            runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(note)) }
        }
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Getting ready"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        foreground = true
        true
    } catch (_: Exception) {
        // Android wouldn't have it started from where this start came from
        false
    }

    private fun notification(note: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_roam)
            .setContentTitle(note)
            .setContentText("Through this phone, while the earbuds are out of the computer's reach")
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
        if (output != null) {
            builder.addAction(0, "Play / pause", action(ACTION_TOGGLE, 1))
            builder.addAction(0, "Back to $computer", action(ACTION_BACK, 2))
        }
        builder.addAction(0, "Stop", action(ACTION_STOP, 3))
        return builder.build()
    }

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, RoamService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "The computer's sound on your earbuds", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this phone plays the computer's sound through your earbuds"
                setShowBadge(false)
            },
        )
    }

    // ── staying awake, and ending ──

    private fun acquireLocks() {
        runCatching {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "weave:roam").apply { acquire(LOCK_MOST_MS) }
        }
        runCatching {
            // the Wi-Fi radio naps between packets with the screen off, and sound arriving in clumps stutters
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "weave:roam").apply { acquire() }
        }
    }

    private fun end(why: String?) {
        if (why != null) RoamHub.player.update { it.copy(note = why) }
        stopSelf()
    }

    override fun onDestroy() {
        if (current === this) current = null
        main.removeCallbacksAndMessages(null)
        quiet()
        audio = null
        beacon.stop()
        line?.stop()
        line = null
        runCatching { audioManager.unregisterAudioDeviceCallback(devices) }
        runCatching { unregisterReceiver(noisy) }
        runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(networks) }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        RoamHub.playing = false
        RoamHub.player.update { RoamPlayer(note = it.note.takeIf { _ -> !it.playing }.orEmpty()) }
        super.onDestroy()
    }

    companion object {
        /** The computer is handing the earbuds over: they are about to connect here. */
        const val ACTION_TAKE = "com.t3lluz.weave.roam.TAKE"

        /** The earbuds connected to this phone. */
        const val ACTION_HAVE = "com.t3lluz.weave.roam.HAVE"

        /** Asked for by hand in Weave, with the earbuds already here. */
        const val ACTION_LISTEN = "com.t3lluz.weave.roam.LISTEN"
        const val ACTION_STOP = "com.t3lluz.weave.roam.STOP"
        const val ACTION_BACK = "com.t3lluz.weave.roam.BACK"
        const val ACTION_TOGGLE = "com.t3lluz.weave.roam.TOGGLE"
        const val EXTRA_ADDRESS = "addr"

        private const val CHANNEL_ID = "roam"
        private const val NOTIFICATION_ID = 4760
        private const val TAKE_WAIT_MS = 14_000L
        private const val HAVE_WAIT_MS = 6_000L
        /** How long after a start the computer's "they are here with me" is not yet the last word. */
        private const val SETTLED_MS = 9_000L
        private const val GONE_WAIT_MS = 2_500L
        private const val WATCH_MS = 3_000L
        private const val METER_MS = 2_000L
        /** The most the computer's sound is brought up by: 40 dB, a mix faded to a fifth and then some. */
        private const val LIFT_MOST = 100f
        private const val OFFLINE_MOST_MS = 10 * 60_000L
        private const val LOCK_MOST_MS = 12 * 60 * 60_000L

        /** The player, while there is one. */
        @Volatile private var current: RoamService? = null

        /** Asks the computer to take the earbuds back; false when no player is running to ask through. */
        fun askBack(): Boolean = current?.line?.send(JSONObject().put("t", "roam").put("back", true)) == true

        fun start(context: Context, action: String, address: String?) {
            val intent = Intent(context, RoamService::class.java).setAction(action).putExtra(EXTRA_ADDRESS, address)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // Android wouldn't let it start from the background this time
            }
        }
    }
}

/**
 * Whether the address Android [shown] for an output is the earbuds at [real]. Without the
 * Bluetooth permission Android shows only an address's end ("XX:XX:XX:XX:EE:01"), and that end
 * is what is compared then.
 */
internal fun sameEarbuds(shown: String?, real: String): Boolean {
    if (shown.isNullOrBlank()) return false
    if (shown.equals(real, ignoreCase = true)) return true
    val tail = shown.substringAfterLast("XX:", "")
    return shown.startsWith("XX:") && tail.length >= 5 && real.endsWith(tail, ignoreCase = true)
}
