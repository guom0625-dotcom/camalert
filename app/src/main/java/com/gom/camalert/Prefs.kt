package com.gom.camalert

import android.content.Context

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("camalert", Context.MODE_PRIVATE)

    /** 차량으로 지정한 Bluetooth 기기 MAC 주소 */
    var carAddresses: Set<String>
        get() = prefs.getStringSet(KEY_CARS, emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet(KEY_CARS, value).apply()

    private companion object {
        const val KEY_CARS = "car_addresses"
    }
}
