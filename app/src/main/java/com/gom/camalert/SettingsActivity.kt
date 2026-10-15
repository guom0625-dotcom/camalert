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
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** 유일한 화면: 권한 상태, 차량 Bluetooth 선택, 안내 설정 */
class SettingsActivity : Activity() {

    private lateinit var permissionList: LinearLayout
    private lateinit var deviceList: LinearLayout
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private lateinit var statusView: TextView
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            statusView.text = AlertService.statusLine ?: "꺼짐 · 차량 Bluetooth가 연결되면 자동으로 시작합니다"
            handler.postDelayed(this, 1000)
        }
    }
    private var editingDevices = false
    private var pendingRelease: Updater.Release? = null
    private var missingPermissions = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionList = vertical()
        deviceList = vertical()
        updateStatus = caption("")
        updateButton = button("업데이트 확인") { onUpdateClicked() }
        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, 0, dp(8))
        }

        actionBar?.hide()

        val root = vertical().apply {
            addView(TextView(this@SettingsActivity).apply {
                text = getString(R.string.app_name)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                setPadding(0, dp(8), 0, dp(4))
            })
            addView(caption("차량 Bluetooth가 연결되면 전방 단속 카메라를 카카오내비 음성으로 알려줍니다."))
            addView(heading("현재 상태"))
            addView(statusView)
            addView(heading("권한"))
            addView(permissionList)
            addView(heading("차량 Bluetooth"))
            addView(deviceList)
            addView(heading("안내 설정"))
            addView(caption("바꾼 설정은 바로 적용됩니다."))
            addView(guidanceSettings())
            addView(heading("테스트"))
            addView(button("지금 시작") { AlertService.start(this@SettingsActivity) }, buttonParams())
            addView(heading("업데이트"))
            addView(updateStatus)
            addView(updateButton, buttonParams())
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(root)
        }
        // Android 15부터 화면이 상태바/내비게이션바 밑까지 그려지므로 그만큼 여백을 줌
        scroll.setPadding(dp(20), dp(16), dp(20), dp(24))
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(dp(20) + bars.left, dp(16) + bars.top, dp(20) + bars.right, dp(24) + bars.bottom)
                insets
            }
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        Updater.foreground = true
        handler.post(statusTicker)
        refresh()
        checkForUpdate()
    }

    override fun onPause() {
        Updater.foreground = false
        handler.removeCallbacks(statusTicker)
        super.onPause()
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
        missingPermissions = 0
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
        permissionRow("앱 설치 허용 (자동 업데이트)", packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        val power = getSystemService(PowerManager::class.java)
        permissionRow("배터리 최적화 제외 (자동 시작에 필요)", power.isIgnoringBatteryOptimizations(packageName)) {
            @SuppressLint("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
        if (missingPermissions == 0) {
            // 다 허용됐으면 목록 대신 한 줄로
            permissionList.removeAllViews()
            permissionList.addView(TextView(this).apply {
                text = "✅ 모든 권한 허용됨"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
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
        val selected = prefs.carAddresses

        // 이미 골랐으면 목록 대신 선택된 차량만 보여줌
        if (selected.isNotEmpty() && !editingDevices) {
            val names = selected.map { address -> bonded.firstOrNull { it.address == address }?.name ?: address }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
            }
            row.addView(TextView(this).apply {
                text = "🚗 " + names.sorted().joinToString(", ")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "변경"
                minWidth = dp(72)
                setOnClickListener {
                    editingDevices = true
                    refreshDevices()
                }
            })
            deviceList.addView(row)
            return
        }

        deviceList.addView(caption("체크한 기기가 연결되면 자동으로 시작하고, 끊기면 종료합니다."))
        val items = vertical()
        for (device in bonded) {
            items.addView(CheckBox(this).apply {
                text = "${device.name ?: "(이름 없음)"}\n${device.address}"
                isChecked = device.address in selected
                setPadding(dp(8), dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, checked ->
                    prefs.carAddresses = if (checked) prefs.carAddresses + device.address else prefs.carAddresses - device.address
                    if (checked) {
                        editingDevices = false
                        refreshDevices()
                    }
                }
            })
        }
        // 기기가 많으면 목록만 따로 스크롤
        val list = ScrollView(this).apply {
            isNestedScrollingEnabled = true
            addView(items)
        }
        val height = if (bonded.size > 5) dp(320) else LinearLayout.LayoutParams.WRAP_CONTENT
        deviceList.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
        if (selected.isNotEmpty()) {
            deviceList.addView(button("완료") {
                editingDevices = false
                refreshDevices()
            }, buttonParams())
        }
    }

    private fun guidanceSettings(): LinearLayout {
        val prefs = Prefs(this)
        val box = vertical()

        box.addView(subheading(GuidanceGroup.CAMERA.title))
        itemChecks(prefs, GuidanceGroup.CAMERA).forEach(box::addView)

        box.addView(subheading("안내 시작 거리"))
        val distances = listOf(1000 to "1km", 500 to "500m", 300 to "300m")
        box.addView(caption("고속도로"))
        box.addView(choices(distances, prefs.highwayDistance) { prefs.highwayDistance = it })
        box.addView(caption("일반도로"))
        box.addView(choices(distances, prefs.localDistance) { prefs.localDistance = it })

        box.addView(subheading("과속 경고음"))
        box.addView(choices(
            listOf(Prefs.OVERSPEED_OFF to "끄기", Prefs.OVERSPEED_EXCEPT_MOBILE to "이동식 제외", Prefs.OVERSPEED_ALL to "모두"),
            prefs.overspeedMode,
        ) { prefs.overspeedMode = it })
        box.addView(caption("경고 기준 (제한속도 대비)"))
        box.addView(choices(listOf(0 to "즉시", 5 to "5% 초과", 10 to "10% 초과"), prefs.overspeedPercent) {
            prefs.overspeedPercent = it
        })

        box.addView(subheading("음량"))
        val volumeLabel = caption("${prefs.volume}%")
        box.addView(SeekBar(this).apply {
            max = 100
            progress = prefs.volume
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    volumeLabel.text = "$value%"
                    if (fromUser) prefs.volume = value
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
        box.addView(volumeLabel)

        // 나머지 항목은 접어 둠
        val details = vertical().apply { visibility = View.GONE }
        for (group in listOf(GuidanceGroup.ENFORCEMENT, GuidanceGroup.HAZARD, GuidanceGroup.INFO)) {
            details.addView(subheading(group.title))
            itemChecks(prefs, group).forEach(details::addView)
        }
        val toggle = button("상세 안내 항목 펼치기 ▼") {}
        toggle.setOnClickListener {
            val open = details.visibility != View.VISIBLE
            details.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "상세 안내 항목 접기 ▲" else "상세 안내 항목 펼치기 ▼"
        }
        box.addView(toggle, buttonParams())
        box.addView(details)
        return box
    }

    private fun itemChecks(prefs: Prefs, group: GuidanceGroup) =
        GuidanceItem.entries.filter { it.group == group }.map { item ->
            CheckBox(this).apply {
                text = item.label
                isChecked = prefs.isOn(item)
                setPadding(dp(8), dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, checked -> prefs.setOn(item, checked) }
            }
        }

    /** 가로 라디오 버튼 묶음 */
    private fun choices(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) =
        RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            for ((value, label) in options) {
                addView(RadioButton(this@SettingsActivity).apply {
                    id = View.generateViewId()
                    text = label
                    isChecked = value == selected
                    setPadding(0, 0, dp(16), 0)
                    setOnCheckedChangeListener { _, checked -> if (checked) onSelect(value) }
                })
            }
        }

    private fun checkForUpdate() {
        val current = Updater.currentVersionCode(this)
        updateStatus.text = "현재 버전 0.1.$current"
        Thread {
            val release = Updater.findNewer(this)
            runOnUiThread {
                pendingRelease = release
                if (release != null) {
                    updateStatus.text = "현재 버전 0.1.$current → 새 버전 ${release.versionName}"
                    updateButton.text = "지금 업데이트"
                } else {
                    updateButton.text = "업데이트 확인"
                }
            }
        }.start()
    }

    private fun onUpdateClicked() {
        val release = pendingRelease
        if (release == null) {
            checkForUpdate()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        updateButton.isEnabled = false
        updateStatus.text = "내려받는 중…"
        Thread {
            val ok = Updater.install(this, release)
            runOnUiThread {
                updateButton.isEnabled = true
                updateStatus.text = if (ok) "설치 중… 잠시 후 앱이 다시 시작됩니다" else "업데이트 실패. 잠시 후 다시 시도하세요"
            }
        }.start()
    }

    private fun permissionRow(label: String, ok: Boolean, onFix: () -> Unit) {
        if (!ok) missingPermissions++
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
        }
        row.addView(TextView(this).apply {
            text = (if (ok) "✅ " else "⚠️ ") + label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!ok) row.addView(Button(this).apply {
            text = "허용"
            minWidth = dp(72)
            setOnClickListener { onFix() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(12)
        })
        permissionList.addView(row)
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setPadding(0, dp(28), 0, dp(8))
    }

    private fun subheading(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
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

    private fun buttonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(8) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
