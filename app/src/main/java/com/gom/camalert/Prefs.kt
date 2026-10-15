package com.gom.camalert

import android.content.Context

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("camalert", Context.MODE_PRIVATE)

    /** 차량으로 지정한 Bluetooth 기기 MAC 주소 */
    var carAddresses: Set<String>
        get() = prefs.getStringSet(KEY_CARS, emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet(KEY_CARS, value).apply()

    fun isOn(item: GuidanceItem) = prefs.getBoolean("item_${item.name}", item.defaultOn)
    fun setOn(item: GuidanceItem, on: Boolean) = prefs.edit().putBoolean("item_${item.name}", on).apply()

    /** 안내 시작 거리(m) */
    var highwayDistance by intPref("highway_distance", 1000)
    var localDistance by intPref("local_distance", 500)

    var overspeedMode by intPref("overspeed_mode", OVERSPEED_EXCEPT_MOBILE)
    /** 제한속도 대비 몇 % 넘으면 경고음 (0 = 즉시) */
    var overspeedPercent by intPref("overspeed_percent", 0)

    /** 카카오 음성 크기 0~100 */
    var volume by intPref("volume", 100)

    /** 진단 기록 남기기 (테스트 기간이라 기본 켬) */
    var diagEnabled: Boolean
        get() = prefs.getBoolean("diag_enabled", true)
        set(value) = prefs.edit().putBoolean("diag_enabled", value).apply()

    private fun intPref(key: String, default: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getInt(key, default)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) =
            prefs.edit().putInt(key, value).apply()
    }

    companion object {
        private const val KEY_CARS = "car_addresses"

        const val OVERSPEED_OFF = 0
        const val OVERSPEED_EXCEPT_MOBILE = 1
        const val OVERSPEED_ALL = 2
    }
}
