package com.gom.camalert

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 유일한 화면: 권한 상태 확인 + 차량 Bluetooth 선택 */
class SettingsActivity : Activity() {

    private lateinit var permissionList: LinearLayout
    private lateinit var deviceList: LinearLayout
    private var voice: Voice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionList = vertical()
        deviceList = vertical()

        val root = vertical().apply {
            setPadding(dp(16), dp(24), dp(16), dp(24))
            addView(heading("권한"))
            addView(permissionList)
            addView(heading("차량 Bluetooth"))
            addView(caption("체크한 기기가 연결되면 자동으로 시작하고, 끊기면 종료합니다."))
            addView(deviceList)
            addView(heading("테스트"))
            addView(button("음성 테스트") {
                val v = voice ?: Voice(this@SettingsActivity).also { voice = it }
                v.say("600미터 앞 신호 과속 카메라, 제한속도 60")
            })
            addView(button("지금 시작") { AlertService.start(this@SettingsActivity) })
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        voice?.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        refreshPermissions()
        refreshDevices()
    }

    private fun refreshPermissions() {
        permissionList.removeAllViews()
        if (Build.VERSION.SDK_INT >= 33) {
            permissionRow("알림", granted(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            permissionRow("근처 기기 (Bluetooth)", granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            }
        }
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionRow("위치 (정확한 위치)", fine) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 3)
        }
        // "항상 허용"은 정확한 위치를 먼저 받은 뒤에만 요청할 수 있음
        permissionRow("위치 \"항상 허용\"", granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            if (!fine) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 3)
            } else {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 4)
            }
        }
        val power = getSystemService(PowerManager::class.java)
        permissionRow("배터리 최적화 제외 (자동 시작에 필요)", power.isIgnoringBatteryOptimizations(packageName)) {
            @SuppressLint("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshDevices() {
        deviceList.removeAllViews()
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            deviceList.addView(caption("근처 기기 권한을 허용하면 페어링된 기기가 표시됩니다."))
            return
        }
        val bonded = getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty()
            .sortedBy { it.name ?: it.address }
        if (bonded.isEmpty()) {
            deviceList.addView(caption("페어링된 기기가 없습니다. 먼저 폰 설정에서 차량과 페어링하세요."))
            return
        }
        val prefs = Prefs(this)
        for (device in bonded) {
            deviceList.addView(CheckBox(this).apply {
                text = "${device.name ?: "(이름 없음)"}\n${device.address}"
                isChecked = device.address in prefs.carAddresses
                setPadding(dp(8), dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, checked ->
                    prefs.carAddresses = if (checked) prefs.carAddresses + device.address else prefs.carAddresses - device.address
                }
            })
        }
    }

    private fun permissionRow(label: String, ok: Boolean, onFix: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = (if (ok) "✅ " else "⚠️ ") + label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!ok) row.addView(Button(this).apply {
            text = "허용"
            setOnClickListener { onFix() }
        })
        permissionList.addView(row)
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        alpha = 0.7f
        setPadding(0, 0, 0, dp(8))
    }

    private fun button(text: String, onClick: (View) -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener(onClick)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
