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
    private lateinit var startStopButton: Button
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            val line = AlertService.statusLine
            statusView.text = line ?: "꺼짐\n차량 Bluetooth가 연결되면 자동으로 시작합니다"
            startStopButton.text = if (line == null) "수동 시작" else "종료"
            handler.postDelayed(this, 1000)
        }
    }
    private var editingDevices = false
    private var pendingRelease: Updater.Release? = null
    // 확인 창에서 취소했을 때 화면에 돌아올 때마다 다시 받지 않도록, 자동 설치는 버전당 한 번만
    private var autoTriedVersion: Int? = null
    private var missingPermissions = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionList = vertical()
        deviceList = vertical()
        updateStatus = caption("")
        updateButton = button("업데이트") { onUpdateClicked() }.apply { visibility = View.GONE }
        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        // 차량으로 등록하지 않은 차를 탔거나 자동 시작이 안 됐을 때 손으로 켜고 끄는 용도
        startStopButton = Button(this).apply {
            minWidth = dp(72)
            setOnClickListener {
                if (AlertService.statusLine == null) AlertService.start(this@SettingsActivity)
                else AlertService.stop(this@SettingsActivity)
            }
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(statusView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(startStopButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            })
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
            addView(statusRow)
            addView(heading("권한"))
            addView(permissionList)
            addView(heading("차량 Bluetooth"))
            addView(deviceList)
            // 안내 설정 전체도 평소엔 접어 둠
            val guidance = vertical().apply {
                visibility = View.GONE
                addView(caption("항목을 누르면 펼쳐집니다. 바꾼 설정은 바로 적용됩니다."))
                addView(guidanceSettings())
            }
            addView(heading("안내 설정  ▼").apply {
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    val open = guidance.visibility != View.VISIBLE
                    guidance.visibility = if (open) View.VISIBLE else View.GONE
                    text = if (open) "안내 설정  ▲" else "안내 설정  ▼"
                }
            })
            addView(guidance)
            addView(heading("진단 기록"))
            addView(diagnostics())
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
        val summaries = mutableListOf<() -> Unit>()
        val changed = { summaries.forEach { it() } }

        fun section(title: String, summary: () -> String, content: LinearLayout) {
            val (header, update) = expandable(title, summary, content)
            summaries += update
            box.addView(header)
            box.addView(content)
        }

        fun itemSummary(group: GuidanceGroup): String {
            val items = GuidanceItem.entries.filter { it.group == group }
            val on = items.count(prefs::isOn)
            return when (on) {
                items.size -> "모두 켬"
                0 -> "모두 끔"
                else -> "${items.size}개 중 ${on}개 켬"
            }
        }

        section(GuidanceGroup.CAMERA.title, { itemSummary(GuidanceGroup.CAMERA) }, vertical().apply {
            itemChecks(prefs, GuidanceGroup.CAMERA, changed).forEach { addView(it) }
        })

        val distances = listOf(1000 to "1km", 500 to "500m", 300 to "300m")
        fun distanceLabel(m: Int) = distances.first { it.first == m }.second
        section("안내 시작 거리", {
            "고속도로 ${distanceLabel(prefs.highwayDistance)} · 일반도로 ${distanceLabel(prefs.localDistance)}"
        }, vertical().apply {
            addView(caption("고속도로"))
            addView(choices(distances, prefs.highwayDistance) { prefs.highwayDistance = it; changed() })
            addView(caption("일반도로"))
            addView(choices(distances, prefs.localDistance) { prefs.localDistance = it; changed() })
        })

        val modes = listOf(Prefs.OVERSPEED_OFF to "끄기", Prefs.OVERSPEED_EXCEPT_MOBILE to "이동식 제외", Prefs.OVERSPEED_ALL to "모두")
        val thresholds = listOf(0 to "즉시", 5 to "5% 초과", 10 to "10% 초과")
        section("과속 경고음", {
            val mode = modes.first { it.first == prefs.overspeedMode }.second
            if (prefs.overspeedMode == Prefs.OVERSPEED_OFF) mode
            else "$mode · ${thresholds.first { it.first == prefs.overspeedPercent }.second}"
        }, vertical().apply {
            addView(choices(modes, prefs.overspeedMode) { prefs.overspeedMode = it; changed() })
            addView(caption("경고 기준 (제한속도 대비)"))
            addView(choices(thresholds, prefs.overspeedPercent) { prefs.overspeedPercent = it; changed() })
        })

        section("음량", { "${prefs.volume}%" }, vertical().apply {
            addView(SeekBar(this@SettingsActivity).apply {
                max = 100
                progress = prefs.volume
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        if (fromUser) prefs.volume = value
                        changed()
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            })
        })

        for (group in listOf(GuidanceGroup.ENFORCEMENT, GuidanceGroup.HAZARD, GuidanceGroup.INFO)) {
            section(group.title, { itemSummary(group) }, vertical().apply {
                itemChecks(prefs, group, changed).forEach { addView(it) }
            })
        }
        return box
    }

    /** 누르면 펼쳐지는 한 줄. 접혀 있을 땐 제목과 현재 값만 보임. 반환값: (헤더, 요약 갱신 함수) */
    private fun expandable(title: String, summary: () -> String, content: View): Pair<View, () -> Unit> {
        content.visibility = View.GONE
        content.setPadding(dp(12), 0, 0, dp(8))
        val value = TextView(this).apply { alpha = 0.7f }
        val arrow = TextView(this).apply { text = "▼" }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            addView(TextView(this@SettingsActivity).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(12)
            })
            addView(arrow)
            setOnClickListener {
                val open = content.visibility != View.VISIBLE
                content.visibility = if (open) View.VISIBLE else View.GONE
                arrow.text = if (open) "▲" else "▼"
            }
        }
        val update = { value.text = summary() }
        update()
        return header to update
    }

    private fun itemChecks(prefs: Prefs, group: GuidanceGroup, onChange: () -> Unit) =
        GuidanceItem.entries.filter { it.group == group }.map { item ->
            CheckBox(this).apply {
                text = item.label
                isChecked = prefs.isOn(item)
                setPadding(dp(8), dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, checked ->
                    prefs.setOn(item, checked)
                    onChange()
                }
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

    private fun diagnostics(): LinearLayout {
        val prefs = Prefs(this)
        val box = vertical()
        val info = caption("")
        fun refreshInfo() {
            val kb = DiagLog.sizeBytes(this) / 1024
            info.text = if (prefs.diagEnabled) "기록 중 · ${kb}KB (폰 안에만 저장, 외부 전송 없음)" else "꺼짐 · ${kb}KB"
        }
        box.addView(CheckBox(this).apply {
            text = "진단 기록 남기기"
            isChecked = prefs.diagEnabled
            setPadding(dp(8), dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked ->
                prefs.diagEnabled = checked
                refreshInfo()
            }
        })
        box.addView(info)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("기록 복사") {
            val text = DiagLog.recent(this)
            if (text.isBlank()) {
                android.widget.Toast.makeText(this, "기록이 없습니다", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("camalert 진단 기록", text))
                android.widget.Toast.makeText(this, "복사했습니다. 채팅에 붙여 넣어 주세요", android.widget.Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        buttons.addView(button("기록 지우기") {
            DiagLog.clear(this)
            info.postDelayed({ refreshInfo() }, 300)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(buttons)
        refreshInfo()
        return box
    }

    private fun checkForUpdate() {
        val current = Updater.currentVersionCode(this)
        if (Updater.installing) return
        updateStatus.text = "현재 버전 0.1.$current · 새 버전 확인 중…"
        updateButton.visibility = View.GONE
        Thread {
            val release = Updater.findNewer(this)
            runOnUiThread {
                pendingRelease = release
                when {
                    release == null -> updateStatus.text = "최신 버전입니다 (0.1.$current)"
                    !packageManager.canRequestPackageInstalls() -> {
                        updateStatus.text = "새 버전 ${release.versionName}이 있습니다. 앱 설치를 허용하면 자동으로 업데이트합니다."
                        showUpdateButton("설치 허용")
                    }
                    // 운전 중에는 설치하면 앱이 재시작되므로 끝난 뒤에 자동 설치
                    AlertService.statusLine != null -> {
                        updateStatus.text = "새 버전 ${release.versionName}이 있습니다. 카메라 알림이 꺼지면 자동으로 설치합니다."
                        showUpdateButton("지금 업데이트")
                    }
                    autoTriedVersion == release.versionCode -> {
                        updateStatus.text = "새 버전 ${release.versionName}이 있습니다."
                        showUpdateButton("지금 업데이트")
                    }
                    else -> {
                        autoTriedVersion = release.versionCode
                        startInstall(release)
                    }
                }
            }
        }.start()
    }

    private fun showUpdateButton(label: String) {
        updateButton.text = label
        updateButton.visibility = View.VISIBLE
    }

    private fun onUpdateClicked() {
        val release = pendingRelease ?: return checkForUpdate()
        if (!packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        startInstall(release)
    }

    private fun startInstall(release: Updater.Release) {
        updateButton.visibility = View.GONE
        updateStatus.text = "새 버전 ${release.versionName} 내려받는 중…"
        Thread {
            val ok = Updater.install(this, release) { received, total ->
                val mb = { bytes: Long -> "%.1f".format(bytes / 1_048_576.0) }
                val text = if (total > 0) {
                    "새 버전 ${release.versionName} 내려받는 중… ${received * 100 / total}% (${mb(received)}/${mb(total)}MB)"
                } else {
                    "새 버전 ${release.versionName} 내려받는 중… ${mb(received)}MB"
                }
                runOnUiThread { updateStatus.text = text }
            }
            runOnUiThread {
                if (ok) {
                    updateStatus.text = "설치 중… 잠시 후 앱이 다시 시작됩니다"
                } else {
                    updateStatus.text = "업데이트 실패. 네트워크를 확인하고 다시 시도하세요"
                    showUpdateButton("다시 시도")
                }
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
