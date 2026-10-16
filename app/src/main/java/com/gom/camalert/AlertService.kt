package com.gom.camalert

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * 차량 Bluetooth가 연결된 동안 돌아가는 포그라운드 서비스.
 * 알림창 버튼: 멈춤/시작(토글), 종료, 설정
 */
class AlertService : Service(), LocationListener {

    private lateinit var chime: Chime
    private lateinit var source: CameraSource
    private var running = false
    private var paused = false
    /** 길안내 중인 내비 앱 (자동 멈춤 원인). 없으면 null */
    private var navApp: String? = null
    /** 내비 실행 중인데도 사용자가 ▶ 시작을 누른 경우 */
    private var navOverride = false
    private val navPaused get() = navApp != null && !navOverride
    private val muted get() = paused || navPaused
    private var lastLocationLog = 0L
    private val main = Handler(Looper.getMainLooper())
    /** 내비 앱이 화면에 떠 있는지 주기적으로 확인 */
    private val screenPoll = object : Runnable {
        override fun run() {
            if (Prefs(this@AlertService).navAutoPause) NavWatcher.pollScreen(this@AlertService)
            main.postDelayed(this, SCREEN_POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        chime = Chime(this)
        source = if (KakaoCameraSource.available) KakaoCameraSource(application) else NoCameraSource()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> {
                val silent = intent?.getBooleanExtra(EXTRA_SILENT, false) == true
                DiagLog.log(this, if (silent) "서비스 종료 (차량 연결 끊김)" else "서비스 종료 (사용자)")
                shutdown(sound = !silent)
            }
            ACTION_PAUSE -> if (running) {
                DiagLog.log(this, "멈춤")
                paused = true
                source.muted = true
                if (!navPaused) chime.stop()
                refreshNotification()
            } else {
                stopSelf()
            }
            else -> {
                val wasMuted = running && muted
                if (!running) {
                    if (!begin()) return START_NOT_STICKY
                } else if (intent?.action == ACTION_START) {
                    // startForegroundService로 다시 불렸으면 매번 startForeground를 호출해야 함
                    startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                }
                if (intent?.action == ACTION_RESUME && navPaused) {
                    DiagLog.log(this, "내비 실행 중이지만 사용자가 시작")
                    navOverride = true
                }
                paused = false
                if (wasMuted && !muted) {
                    DiagLog.log(this, "재개")
                    chime.start()
                }
                source.muted = muted
                refreshNotification()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        statusLine = null
        NavWatcher.listener = null
        main.removeCallbacks(screenPoll)
        if (running) stopTracking()
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        source.onLocation(location)
        // GPS가 살아 있는지 확인용으로 1분에 한 번만 기록
        val now = System.currentTimeMillis()
        if (now - lastLocationLog >= 60_000) {
            lastLocationLog = now
            val speed = if (location.hasSpeed()) "${(location.speed * 3.6f).toInt()}km/h" else "속도 없음"
            DiagLog.log(this, "GPS 수신 중 · $speed · 정확도 ${location.accuracy.toInt()}m" + source.diag().let { if (it.isEmpty()) "" else " · $it" })
        }
    }

    private fun begin(): Boolean {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // 권한 부족이나 백그라운드 시작 제한
            Log.w(TAG, "startForeground failed", e)
            DiagLog.log(this, "서비스 시작 실패: ${e.javaClass.simpleName} ${e.message} → '눌러서 시작' 알림 표시")
            statusLine = null
            showTapToStart(this)
            stopSelf()
            return false
        }
        running = true
        DiagLog.log(this, "서비스 시작 · 버전 ${packageManager.getPackageInfo(packageName, 0).versionName}")
        startTracking()
        chime.start()
        NavWatcher.listener = { onNavChanged() }
        NavWatcher.resetScreen()
        screenPoll.run()
        onNavChanged()
        return true
    }

    /** 내비 길안내가 시작/끝나면 소리 없이 멈추고 다시 켬 (내비 소리와 겹치지 않게) */
    private fun onNavChanged() {
        if (!running) return
        val app = if (Prefs(this).navAutoPause) NavWatcher.active else null
        if (app == navApp) return
        navApp = app
        if (app == null) navOverride = false
        DiagLog.log(this, if (app != null) "자동 멈춤: $app 실행 중" else "자동 재개: 내비 종료")
        source.muted = muted
        refreshNotification()
    }

    private fun startTracking() {
        source.start(onStatus = {
            DiagLog.log(this, "상태: ${source.status}")
            refreshNotification()
        })
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } else {
            DiagLog.log(this, "위치 권한 없음: 과속 경고 기준(5%/10%) 판단 불가")
        }
    }

    private fun stopTracking() {
        getSystemService(LocationManager::class.java).removeUpdates(this)
        source.stop()
    }

    private fun shutdown(sound: Boolean) {
        if (running) stopTracking()
        running = false
        statusLine = null
        NavWatcher.listener = null
        main.removeCallbacks(screenPoll)
        if (sound) chime.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // 운전이 끝난 뒤에만 업데이트 (설치하면 앱이 재시작됨)
        Updater.autoUpdate(this)
    }

    private fun refreshNotification() {
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val title = when {
            paused -> "카메라 알림 멈춤"
            navPaused -> "카메라 알림 쉬는 중 · $navApp 실행 중"
            else -> "카메라 알림 실행 중"
        }
        val text = if (navPaused && !paused) "내비를 끄면 자동으로 다시 켜집니다" else source.status
        statusLine = "$title\n$text"
        val toggle = if (muted) {
            action(android.R.drawable.ic_media_play, "▶ 시작", servicePending(this, ACTION_RESUME))
        } else {
            action(android.R.drawable.ic_media_pause, "⏸ 멈춤", servicePending(this, ACTION_PAUSE))
        }
        return Notification.Builder(this, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_dog)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(settingsPending(this))
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            // 알림 버튼은 최대 3개라 시작/멈춤은 하나로 토글
            .addAction(toggle)
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "✕ 종료", servicePending(this, ACTION_EXIT)))
            .addAction(action(android.R.drawable.ic_menu_preferences, "⚙ 설정", settingsPending(this)))
            .build()
    }

    private fun action(icon: Int, title: String, intent: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, intent).build()

    companion object {
        private const val TAG = "AlertService"
        private const val NOTIFICATION_ID = 1
        private const val CONNECTED_NOTIFICATION_ID = 2
        private const val CHANNEL_RUNNING = "running"
        private const val CHANNEL_CONNECTED = "connected"
        private const val SCREEN_POLL_MS = 3_000L

        /** 설정 화면에 보여줄 현재 상태. 서비스가 꺼져 있으면 null */
        @Volatile
        var statusLine: String? = null
            private set

        const val ACTION_START = "com.gom.camalert.START"
        const val ACTION_RESUME = "com.gom.camalert.RESUME"
        const val ACTION_PAUSE = "com.gom.camalert.PAUSE"
        const val ACTION_EXIT = "com.gom.camalert.EXIT"
        private const val EXTRA_SILENT = "silent"

        fun start(context: Context) {
            val intent = Intent(context, AlertService::class.java).setAction(ACTION_START)
            try {
                context.startForegroundService(intent)
                context.getSystemService(NotificationManager::class.java).cancel(CONNECTED_NOTIFICATION_ID)
            } catch (e: Exception) {
                // Android 12+ 백그라운드 시작이 막힌 경우: 누르면 시작되는 알림으로 대신함
                Log.w(TAG, "background start blocked", e)
                DiagLog.log(context, "백그라운드 시작 차단됨 (${e.javaClass.simpleName}) → '눌러서 시작' 알림 표시")
                showTapToStart(context)
            }
        }

        /** silent: 차에서 내려 Bluetooth가 끊긴 경우엔 폰 스피커로 소리가 나지 않게 */
        fun stop(context: Context, silent: Boolean = false) {
            context.getSystemService(NotificationManager::class.java).cancel(CONNECTED_NOTIFICATION_ID)
            try {
                context.startService(
                    Intent(context, AlertService::class.java).setAction(ACTION_EXIT).putExtra(EXTRA_SILENT, silent)
                )
            } catch (e: Exception) {
                // 서비스가 이미 없으면 백그라운드 startService가 거부됨. 끌 것도 없음.
                Log.d(TAG, "stop ignored", e)
            }
        }

        private fun showTapToStart(context: Context) {
            createChannels(context)
            val pending = PendingIntent.getForegroundService(
                context, 10,
                Intent(context, AlertService::class.java).setAction(ACTION_START),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(context, CHANNEL_CONNECTED)
                .setSmallIcon(R.drawable.ic_stat_dog)
                .setContentTitle("차량 연결됨")
                .setContentText("눌러서 카메라 알림 시작 (자동 시작은 설정에서 배터리 최적화 제외 필요)")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(CONNECTED_NOTIFICATION_ID, notification)
        }

        private fun servicePending(context: Context, action: String): PendingIntent =
            PendingIntent.getService(
                context, action.hashCode(),
                Intent(context, AlertService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun settingsPending(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0,
                Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_RUNNING, "카메라 알림 실행 중", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_CONNECTED, "차량 연결", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }
}
