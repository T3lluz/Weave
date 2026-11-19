package com.t3lluz.weave.roam

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Looper
import android.os.Process
import kotlin.system.exitProcess

/**
 * The hand that connects the earbuds to this phone. It does not run inside Weave: the computer
 * starts it over adb, as the phone's shell (`app_process`, the way scrcpy starts its server),
 * with this class out of Weave's own APK.
 *
 * Android lets no ordinary app connect a Bluetooth headset: that takes a permission only the
 * system and the shell hold. Run as the shell, the same call goes through.
 *
 *     CLASSPATH=<Weave's apk> app_process / com.t3lluz.weave.roam.RoamShell serve
 *
 * `serve` says "ready 1" and the addresses this phone is paired with, then answers lines on its
 * input until the input closes. Each line is a number, a word and its arguments, and each
 * answer starts with that number:
 *
 *     take ADDR        wake Weave's player and connect the earbuds: "ok" or "no ..."
 *     connect ADDR     connect them, nothing else
 *     disconnect ADDR
 *     state ADDR       "linked" or "apart", and whether their sound goes to this phone
 *     ping             "pong"
 *
 * With a word in place of `serve`, it does that one thing and ends.
 */
object RoamShell {
    private const val VERSION = 1
    private const val SHELL = "com.android.shell"

    /** Android as the shell sees it: every call names the shell's package, which is who is calling. */
    private class AsShell(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL
        override fun getOpPackageName(): String = SHELL
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL).build()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            val adapter = adapter()
            Thread {
                val code = try {
                    work(adapter, args)
                } catch (t: Throwable) {
                    println("no ${rootOf(t)}")
                    1
                }
                System.out.flush()
                exitProcess(code)
            }.start()
            // Bluetooth hands its service to the adapter on this thread's queue
            Looper.loop()
        } catch (t: Throwable) {
            println("no ${rootOf(t)}")
            System.out.flush()
            exitProcess(1)
        }
    }

    /**
     * The Bluetooth adapter, made outside any app. There is no app here to take one from, so the
     * pieces an app gets handed are put together by hand: a thread Android takes for the main
     * one, the system's own context, and over it the shell's name.
     */
    private fun adapter(): BluetoothAdapter {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) error("this needs Android 12 or newer")
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        threadClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        threadClass.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
        // Bluetooth is a module of its own since Android 13; this is what gives it its way to the system
        runCatching { threadClass.getMethod("initializeMainlineModules").invoke(null) }
        val system = threadClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread) as Context
        val create = BluetoothAdapter::class.java.getDeclaredMethod("createAdapter", Context::class.java).apply { isAccessible = true }
        return create.invoke(null, AsShell(system)) as BluetoothAdapter
    }

    private fun work(adapter: BluetoothAdapter, args: Array<String>): Int {
        // the adapter gets its service a moment after it is made
        var waited = 0
        while (!adapter.isEnabled && waited < 2_000) {
            Thread.sleep(25)
            waited += 25
        }
        if (!adapter.isEnabled) {
            println("no Bluetooth is off on the phone")
            return 1
        }
        if (args.firstOrNull() != "serve") {
            println(answer(adapter, args.toList()))
            return 0
        }
        @Suppress("MissingPermission")
        val bonded = adapter.bondedDevices.orEmpty().joinToString(" ") { it.address }
        println("ready $VERSION $bonded")
        System.out.flush()
        while (true) {
            val line = readlnOrNull() ?: return 0
            val words = line.trim().split(' ').filter { it.isNotEmpty() }
            if (words.size < 2) continue
            val said = try {
                answer(adapter, words.drop(1))
            } catch (t: Throwable) {
                "no ${rootOf(t)}"
            }
            println("${words[0]} $said")
            System.out.flush()
        }
    }

    private fun answer(adapter: BluetoothAdapter, words: List<String>): String {
        val what = words.firstOrNull().orEmpty()
        if (what == "ping") return "pong"
        val address = words.getOrNull(1)?.uppercase().orEmpty()
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return "no that is not an address"
        val device = adapter.getRemoteDevice(address)
        return when (what) {
            "take" -> {
                wakePlayer(address)
                result(call(device, "connect"))
            }
            "connect" -> result(call(device, "connect"))
            "disconnect" -> result(call(device, "disconnect"))
            "state" -> {
                val linked = BluetoothDevice::class.java.getMethod("isConnected").invoke(device) == true
                val sound = adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
                "${if (linked) "linked" else "apart"}${if (linked && sound) " sound" else ""}"
            }
            else -> "no unknown word $what"
        }
    }

    private fun call(device: BluetoothDevice, name: String): Int =
        BluetoothDevice::class.java.getMethod(name).invoke(device) as? Int ?: -1

    /** Bluetooth's own answer to connect and disconnect: 0 is "on its way". */
    private fun result(code: Int): String = if (code == 0) "ok" else "no Bluetooth said $code"

    /**
     * Starts Weave's player before the earbuds are here, so it is connected to the computer and
     * its sound is already arriving by the time they are. Not waited for: the earbuds matter more.
     */
    private fun wakePlayer(address: String) {
        runCatching {
            ProcessBuilder(
                "cmd", "activity", "start-foreground-service", "-n", "com.t3lluz.weave/.roam.RoamService",
                "-a", RoamService.ACTION_TAKE, "--es", RoamService.EXTRA_ADDRESS, address,
            ).redirectErrorStream(true).start()
        }
    }

    private fun rootOf(t: Throwable): String {
        var cause = t
        while (cause.cause != null) cause = cause.cause!!
        return (cause.message ?: cause.javaClass.simpleName).lineSequence().firstOrNull().orEmpty().take(160)
    }
}
