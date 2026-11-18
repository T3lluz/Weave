package com.t3lluz.weave.ui.screens.link

import android.content.Context
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.t3lluz.weave.data.link.LinkInvite
import com.t3lluz.weave.data.link.parseInvite

/**
 * Scans the code `weave pair` shows on a computer, with Google's own scanner: its camera and
 * its screen, so Weave asks for no camera permission. The scanner is a piece of Google Play
 * services that a phone may not have fetched yet: then it is asked for, [say] tells the owner,
 * and the scan opens by itself when it lands.
 */
internal fun scanInvite(context: Context, onInvite: (LinkInvite) -> Unit, say: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    val scanner = GmsBarcodeScanning.getClient(context, options)
    val unavailable = "No scanner on this phone. Point its camera at the code instead, or type the computer's address."

    fun scan() {
        scanner.startScan()
            .addOnSuccessListener { code ->
                val invite = parseInvite(code.rawValue)
                if (invite != null) onInvite(invite) else say("That isn't a Weave code. Run weave pair on the computer.")
            }
            .addOnFailureListener { say(unavailable) }
        // a scan backed out of ends quietly: nothing to say
    }

    val modules = ModuleInstall.getClient(context)
    modules.areModulesAvailable(scanner)
        .addOnSuccessListener { answer ->
            if (answer.areModulesAvailable()) {
                scan()
            } else {
                say("Getting the scanner ready. This takes a moment the first time.")
                modules.installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
                    .addOnSuccessListener { if (it.areModulesAlreadyInstalled()) scan() else say("The scanner is on its way. Tap Scan a code again in a moment.") }
                    .addOnFailureListener { say(unavailable) }
            }
        }
        .addOnFailureListener { scan() }   // couldn't ask: try anyway, and let the scan say if it can't
}
