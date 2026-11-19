package com.t3lluz.weave.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.t3lluz.weave.data.link.LinkConfig
import com.t3lluz.weave.data.link.LinkDirectory
import com.t3lluz.weave.data.link.LinkDirectoryState
import com.t3lluz.weave.data.link.LinkHost
import com.t3lluz.weave.data.link.LinkPrefs
import com.t3lluz.weave.data.link.LinkSession
import com.t3lluz.weave.data.link.LinkShortcut
import com.t3lluz.weave.data.link.LinkShortcutAi
import com.t3lluz.weave.data.link.LinkShortcutKind
import com.t3lluz.weave.data.link.LinkShortcutStore
import com.t3lluz.weave.data.link.ShortcutAnswer
import com.t3lluz.weave.data.link.ShortcutTurn
import com.t3lluz.weave.data.link.LABEL_MAX
import com.t3lluz.weave.data.link.siteName
import com.t3lluz.weave.ui.screens.link.ShortcutSheetState
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The laptop remote. The connection lives in [LinkSession]; this keeps what a rotation must not lose. */
@HiltViewModel
class LinkViewModel @Inject constructor(
    val session: LinkSession,
    private val prefs: LinkPrefs,
    private val directory: LinkDirectory,
    val shortcutStore: LinkShortcutStore,
    private val shortcutAi: LinkShortcutAi,
) : ViewModel() {
    val state = session.state
    val config = prefs.config
    val events = session.events

    /**
     * What the rotate button asked for: 0 follows the phone, 1 holds it on its side, 2 holds it
     * upright. A rotation recreates the screen, so it is kept here.
     */
    var orientation by mutableStateOf(0)

    /** Full screen: the laptop's screen is a touchscreen (true) or the whole phone is a trackpad. */
    var touchMode by mutableStateOf(false)

    /** Preview zoom over "fits the area", or null to use the setting's default. */
    var zoom by mutableStateOf<Float?>(null)

    /** The laptops the dashboard knows about, for the list you pick from. */
    private val _laptops = MutableStateFlow(LinkDirectoryState())
    val laptops: StateFlow<LinkDirectoryState> = _laptops
    private var looking: Job? = null

    /** The trackpad's hint has done its job once a finger has been on it. */
    var touched by mutableStateOf(false)

    /** The shortcut sheet while it is open, kept here so a trip to Settings → AI from it loses nothing. */
    internal var shortcutSheet by mutableStateOf<ShortcutSheetState?>(null)

    fun findLaptops() {
        if (looking?.isActive == true) return
        looking = viewModelScope.launch {
            val found = directory.laptops()
            _laptops.value = found
            // The dashboard says the saved computer restarted with a new ticket, or moved: keep what is
            // saved in step. Only the dashboard's own entry for it may, the one whose id the phone
            // kept when the computer was picked from the dashboard's list: one that merely has the
            // same name could be another computer, and the saved token would go to it. What the
            // network answered is never written over a saved computer, and a ticket is never traded
            // for none (a code just scanned would be lost before it was used).
            val saved = prefs.config.value
            found.hosts.firstOrNull { it.fromDashboard && saved.board.isNotBlank() && it.id == saved.board }?.let { h ->
                val addresses = h.addresses.joinToString(",")
                val ticket = h.ticket.ifBlank { saved.ticket }
                if (saved.ticket != ticket || saved.host != addresses) prefs.update { it.copy(host = addresses, port = h.port, ticket = ticket) }
            }
        }
    }

    /**
     * A computer of the list was tapped. One this phone knows keeps its key (and so its token, and
     * what wakes it); one it has never been let into gets a new key, whatever it calls itself.
     */
    fun connectTo(laptop: LinkHost) {
        shortcutSheet = null
        val known = prefs.known.value.firstOrNull { it.key == laptop.key && laptop.key.isNotBlank() }
        prefs.update {
            it.copy(
                host = laptop.addresses.joinToString(","), port = laptop.port, name = laptop.name, ticket = laptop.ticket,
                key = known?.key ?: prefs.newKey(), wakeMac = known?.wakeMac.orEmpty(), wakeBroadcast = known?.wakeBroadcast.orEmpty(),
                ident = known?.ident.orEmpty(),
                // picked from the dashboard's list: its id there is what ties the two from now on
                board = if (laptop.fromDashboard) laptop.id else known?.board.orEmpty(),
            )
        }
        session.reconnect()
    }

    /** Back to the list, with nothing picked. */
    /** The picked computer is taken off this phone's list of its own, and let go of. */
    fun forget() {
        prefs.forget(prefs.config.value.key)
        disconnect()
    }

    fun disconnect() {
        shortcutSheet = null
        prefs.update { it.copy(host = "", name = "", ticket = "", key = "", board = "", ident = "", wakeMac = "", wakeBroadcast = "") }
        session.stop()
        // the laptop tells the dashboard it is free within a moment; ask again then, so the
        // list doesn't say this phone is still using it
        viewModelScope.launch {
            kotlinx.coroutines.delay(1_200)
            looking?.cancel()
            findLaptops()
        }
    }

    /**
     * Wakes the picked laptop with a magic packet, then tries it again once it has had time to
     * come up (and back onto the tailnet).
     */
    fun wake() {
        val cfg = prefs.config.value
        if (cfg.wakeMac.isBlank()) return
        viewModelScope.launch {
            com.t3lluz.weave.data.link.WakeOnLan.send(cfg.wakeMac, cfg.wakeBroadcast)
            kotlinx.coroutines.delay(WAKE_WAIT_MS)
            findLaptops()
            session.reconnect()
        }
    }

    fun start() = session.start()
    fun stop() = session.stop()

    /** By hand, for a laptop the dashboard doesn't list. The laptop asks its owner the first time. */
    /**
     * An invitation that arrived (a scanned code, an opened link), waiting for the owner's yes.
     * Nothing connects on a link's word alone: any web page can open one, and pairing sends this
     * phone's token for that computer to wherever the link says.
     */
    var offered by mutableStateOf<com.t3lluz.weave.data.link.LinkInvite?>(null)
        private set

    /** A code was scanned for a computer this phone has already: said instead of pairing it twice. */
    var alreadyPaired by mutableStateOf<com.t3lluz.weave.data.link.KnownComputer?>(null)
        private set

    fun offer(invite: com.t3lluz.weave.data.link.LinkInvite) {
        // the picked computer counts too, while it still waits to be let in
        val cfg = prefs.config.value
        val picked = com.t3lluz.weave.data.link.KnownComputer(cfg.name, cfg.host, cfg.port, key = cfg.key, ident = cfg.ident)
            .takeIf { cfg.host.isNotBlank() && prefs.known.value.none { k -> k.key == cfg.key } }
        val known = com.t3lluz.weave.data.link.alreadyKnown(invite, prefs.known.value + listOfNotNull(picked))
        if (known != null) alreadyPaired = known else offered = invite
    }

    fun dismissAlreadyPaired() {
        alreadyPaired = null
    }

    /** Whether [computer] is the one this phone is connected to right now. */
    fun isConnectedTo(computer: com.t3lluz.weave.data.link.KnownComputer): Boolean =
        prefs.config.value.key == computer.key && session.state.value.status == com.t3lluz.weave.data.link.LinkStatus.Online

    /** Connects to the computer a scanned code turned out to be for, as it is saved. */
    fun connectAlreadyPaired() {
        val k = alreadyPaired ?: return
        alreadyPaired = null
        if (isConnectedTo(k)) return
        if (prefs.config.value.key == k.key) {
            session.reconnect()
            return
        }
        connectTo(
            LinkHost(
                id = "known:${k.key}", name = k.name, addresses = k.host.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                port = k.port, ticket = k.ticket, online = true, busy = false, key = k.key, board = k.board,
            ),
        )
    }

    /**
     * Takes a computer off this phone's list. The one in use is let go of as well. It can be
     * paired again with a code; until then the phone has nothing of it.
     */
    fun remove(key: String) {
        if (key.isBlank()) return
        prefs.forget(key)
        if (prefs.config.value.key == key) {
            disconnect()
        } else {
            looking?.cancel()
            findLaptops()
        }
    }

    fun declineOffer() {
        offered = null
    }

    /** The owner said yes to [offered]: connect, and its code lets this phone in. */
    fun acceptOffer() {
        val invite = offered ?: return
        offered = null
        pair(invite)
    }

    private fun pair(invite: com.t3lluz.weave.data.link.LinkInvite) {
        shortcutSheet = null
        // always a new key: an invitation says where to connect, and a token made for another
        // computer must never go to an address a code brought
        prefs.update {
            it.copy(host = invite.addresses.joinToString(","), port = invite.port, name = invite.name, ticket = invite.code,
                key = prefs.newKey(), board = "", ident = invite.id, wakeMac = "", wakeBroadcast = "")
        }
        session.reconnect()
    }

    fun connectTo(address: String) {
        val typed = address.trim()
        val port = com.t3lluz.weave.data.link.LinkProtocol.PORT
        // the same address typed before is the same computer: its key, so it doesn't ask again
        val known = prefs.known.value.firstOrNull { it.host == typed && it.port == port }
        prefs.update {
            it.copy(host = typed, port = port, name = known?.name ?: typed, ticket = "", key = known?.key ?: prefs.newKey(),
                board = known?.board.orEmpty(), ident = known?.ident.orEmpty(), wakeMac = known?.wakeMac.orEmpty(), wakeBroadcast = known?.wakeBroadcast.orEmpty())
        }
        session.reconnect()
    }

    fun change(change: (LinkConfig) -> LinkConfig) = prefs.update(change)

    val shortcuts = shortcutStore.shortcuts

    /**
     * Keeps a shortcut, and its pictures: each app's icon as the laptop sent it, each site's
     * fetched once in the background (the tile shows a globe until it lands), and its own
     * glyphs. Each step keeps only its own kind's fields.
     */
    fun saveShortcut(s: LinkShortcut) {
        val clean = s.copy(label = s.label.trim(), steps = s.steps.map { it.clean() }, toggle = s.toggle?.let { it.copy(label = it.label.trim()) })
        if (!clean.complete) return
        shortcutStore.save(clean)
        viewModelScope.launch {
            clean.steps.forEach { step ->
                when (step.kind) {
                    LinkShortcutKind.APP -> launch { keepAppIconWhenHere(step.app) }
                    LinkShortcutKind.SITE -> {
                        val own = shortcutStore.fetchSiteIcon(step.url)
                        if (step == clean.lead) nameAfterSite(clean, own)
                    }
                    else -> Unit
                }
            }
            listOfNotNull(clean.glyph, clean.toggle?.glyph).forEach { shortcutStore.fetchGlyph(it.id) }
        }
    }

    /**
     * Runs a shortcut's steps on the laptop. One with two looks flips to the other at once, so the
     * tile answers the tap; [keep] is false for one that isn't saved yet (Try it in the sheet).
     */
    fun runShortcut(s: LinkShortcut, keep: Boolean = true) {
        viewModelScope.launch { session.runSteps(s.steps) }
        if (keep && s.toggle != null) {
            shortcutStore.shortcuts.value.firstOrNull { it.id == s.id }?.let { shortcutStore.save(it.copy(on = !it.on)) }
        }
    }

    /** Puts a two-look shortcut in the look that matches the laptop, without running it. */
    fun setShortcutOn(s: LinkShortcut, on: Boolean) {
        shortcutStore.shortcuts.value.firstOrNull { it.id == s.id }?.let { shortcutStore.save(it.copy(on = on)) }
    }

    fun removeShortcut(id: String) = shortcutStore.remove(id)

    /**
     * Keeps an app's icon once the laptop's list has it: the icons come in batches after the
     * list, so this waits for its own. "None" is noted only when the laptop says it has none; an
     * app the list doesn't name, or an icon that doesn't come, is tried again next visit.
     */
    private suspend fun keepAppIconWhenHere(appId: String) {
        val app = withTimeoutOrNull(20_000) {
            session.catalog.filterNotNull()
                .map { c -> c.apps.firstOrNull { it.id == appId }?.takeIf { it.png != null || !it.hasIcon || c.iconsDone } }
                .first { it != null }
        } ?: return
        when {
            app.png != null -> shortcutStore.keepAppIcon(appId, app.png)
            // only the list saying "no icon" is kept as none; one the laptop failed to draw is tried again
            !app.hasIcon -> shortcutStore.keepAppIcon(appId, null)
        }
    }

    /** A site's shortcut still named after its address ("Youtube") takes the name the site gives itself ("YouTube"). */
    private fun nameAfterSite(s: LinkShortcut, own: String?) {
        if (own == null || own == s.label) return
        val now = shortcutStore.shortcuts.value.firstOrNull { it.id == s.id } ?: return
        val url = now.lead?.takeIf { it.kind == LinkShortcutKind.SITE }?.url ?: return
        if (now.label == siteName(url)) shortcutStore.save(now.copy(label = own.take(LABEL_MAX)))
    }

    /** Whether an AI is set up to make shortcuts from words. */
    fun aiReady() = shortcutAi.ready()

    private var iconsFilled = false

    /**
     * Pictures a shortcut is still missing (a site that didn't answer, an app saved before the
     * laptop's list came) get one more try per visit: sites straight away, apps once the
     * laptop's list is here.
     */
    fun fillMissingIcons() {
        if (iconsFilled) return
        iconsFilled = true
        val all = shortcutStore.shortcuts.value
        val steps = all.flatMap { it.steps }
        val missing = steps.filter {
            (it.kind == LinkShortcutKind.SITE || it.kind == LinkShortcutKind.APP) && shortcutStore.iconFile(it) == null && !shortcutStore.noIcon(it)
        }.distinct()
        val glyphs = all.flatMap { listOfNotNull(it.glyph, it.toggle?.glyph) }.map { it.id }.distinct().filter { shortcutStore.glyphFile(it) == null }
        if (missing.isEmpty() && glyphs.isEmpty()) return
        viewModelScope.launch {
            glyphs.forEach { launch { shortcutStore.fetchGlyph(it) } }
            missing.filter { it.kind == LinkShortcutKind.SITE }.forEach { step ->
                val own = shortcutStore.fetchSiteIcon(step.url)
                all.firstOrNull { it.lead == step }?.let { nameAfterSite(it, own) }
            }
            val apps = missing.filter { it.kind == LinkShortcutKind.APP }
            if (apps.isNotEmpty()) {
                session.requestCatalog()
                // side by side: an app no longer on the laptop mustn't hold up the rest
                apps.forEach { launch { keepAppIconWhenHere(it.app) } }
            }
        }
    }

    /** Icons from Iconify for some words, for the sheet's icon search. */
    suspend fun searchGlyphs(query: String): List<String> = shortcutStore.searchGlyphs(query)

    /**
     * A shortcut out of a few words. The laptop's apps and bindings go with the ask; if they
     * haven't come yet, they are waited for a moment, so the AI picks real ones.
     */
    suspend fun askShortcut(
        ask: String,
        laptop: String,
        current: LinkShortcut?,
        turns: List<ShortcutTurn>,
        onProgress: (String) -> Unit,
    ): ShortcutAnswer {
        session.requestCatalog()
        val catalog = session.catalog.value ?: if (LinkSession.CATALOG in session.state.value.caps) {
            onProgress("Reading $laptop's apps and bindings")
            withTimeoutOrNull(5_000) { session.catalog.filterNotNull().first() }
        } else {
            null
        }
        return shortcutAi.make(ask, catalog, laptop, current, turns, onProgress)
    }

    /**
     * A site's picture and own name while its address is typed, so the sheet's tile shows what
     * the shortcut will look like. The picture is kept for the shortcut once it is saved.
     */
    suspend fun previewSite(url: String): String? = shortcutStore.fetchSiteIcon(url)

    private companion object {
        /** A laptop waking from sleep is back on Wi-Fi and the tailnet in about this long. */
        const val WAKE_WAIT_MS = 9_000L
    }
}
