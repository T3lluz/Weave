package com.t3lluz.weave.roam

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The phone saying "here I am" over Bluetooth while it has the earbuds, so the computer can
 * hear how near it is: where the phone is, the earbuds are, and a beacon heard loud means they
 * are back in the computer's reach. What it sends is a number the computer made up for this
 * one time, so nothing about it can be followed from one day to the next.
 */
class RoamBeacon(private val context: Context) {
    private var callback: AdvertiseCallback? = null
    private var showing: String? = null

    /** Sends [hex] out (the computer's number), or nothing for null. */
    fun show(hex: String?) {
        if (hex == showing) return
        stop()
        val bytes = beaconBytes(hex) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            val advertiser = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeAdvertiser ?: return
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addManufacturerData(MANUFACTURER, MARK + bytes)
                .build()
            val mine = object : AdvertiseCallback() {}
            advertiser.startAdvertising(settings, data, mine)
            callback = mine
            showing = hex
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
            // Bluetooth went off under it
        }
    }

    fun stop() {
        val mine = callback ?: return
        callback = null
        showing = null
        try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(mine)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    private companion object {
        /** The id set aside for tests, which no product on the air uses; the computer listens for it. */
        const val MANUFACTURER = 0xFFFF
        val MARK = byteArrayOf('W'.code.toByte(), 'V'.code.toByte())
    }
}
