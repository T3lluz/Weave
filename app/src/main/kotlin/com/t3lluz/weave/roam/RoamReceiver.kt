package com.t3lluz.weave.roam

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Hears a pair of earbuds connect to this phone, with Weave closed or open. When they are a
 * pair the computer plays through and the lab is on, the player starts. The computer is asked
 * first whether they just left it: earbuds that were never on it are none of its business.
 */
class RoamReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = RoamPrefs.read(context)
        if (!settings.on) return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = try {
            device?.address
        } catch (_: SecurityException) {
            null
        } ?: return
        if (settings.earbuds.none { it.address.equals(address, ignoreCase = true) }) return
        val action = when (intent.action) {
            // on their way in: the player gets its line to the computer up meanwhile
            BluetoothDevice.ACTION_ACL_CONNECTED -> RoamService.ACTION_TAKE
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ->
                if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) RoamService.ACTION_HAVE else return
            else -> return
        }
        RoamService.start(context, action, address)
    }
}
