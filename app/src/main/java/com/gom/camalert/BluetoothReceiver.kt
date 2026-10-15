package com.gom.camalert

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** 설정에서 고른 차량 Bluetooth가 연결되면 서비스 시작, 끊기면 종료 */
class BluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = device?.address ?: return
        if (address !in Prefs(context).carAddresses) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> AlertService.start(context)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> AlertService.stop(context)
        }
    }
}
