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
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * 차량 Bluetooth가 연결된 동안 돌아가는 포그라운드 서비스.
 * 알림창 버튼: 멈춤/시작(토글), 종료, 설정
 */
class AlertService : Service(), LocationListener {

    private lateinit var voice: Voice
    private lateinit var source: CameraSource
    private var running = false
    private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        voice = Voice(this)
        source = if (KakaoCameraSource.available) KakaoCameraSource(application) else NoCameraSource()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> shutdown()
            ACTION_PAUSE -> if (running) {
                paused = true
                source.muted = true
                voice.stop()
                refreshNotification()
            } else {
                stopSelf()
            }
            else -> {
                if (!running) {
                    if (!begin()) return START_NOT_STICKY
                } else if (intent?.action == ACTION_START) {
                    // startForegroundService로 다시 불렸으면 매번 startForeground를 호출해야 함
                    startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                }
                if (paused) voice.say("카메라 알림을 다시 시작합니다")
                paused = false
                source.muted = false
                refreshNotification()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        statusLine = null
        if (running) stopTracking()
        voice.shutdown()
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) = source.onLocation(location)

    private fun begin(): Boolean {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // 권한 부족이나 백그라운드 시작 제한
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
            return false
        }
        running = true
        startTracking()
        voice.say("카메라 알림을 시작합니다")
        return true
    }

    private fun startTracking() {
        source.start(onStatus = ::refreshNotification)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        }
    }

    private fun stopTracking() {
        getSystemService(LocationManager::class.java).removeUpdates(this)
        source.stop()
    }

    private fun shutdown() {
        if (running) stopTracking()
        running = false
        statusLine = null
        voice.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // 운전이 끝난 뒤에만 업데이트 (설치하면 앱이 재시작됨)
        Updater.autoUpdate(this)
    }

    private fun refreshNotification() {
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val title = if (paused) "카메라 알림 멈춤" else "카메라 알림 실행 중"
        statusLine = "$title\n${source.status}"
        val toggle = if (paused) {
            action(android.R.drawable.ic_media_play, "▶ 시작", servicePending(this, ACTION_RESUME))
        } else {
            action(android.R.drawable.ic_media_pause, "⏸ 멈춤", servicePending(this, ACTION_PAUSE))
        }
        return Notification.Builder(this, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle(title)
            .setContentText(source.status)
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

        /** 설정 화면에 보여줄 현재 상태. 서비스가 꺼져 있으면 null */
        @Volatile
        var statusLine: String? = null
            private set

        const val ACTION_START = "com.gom.camalert.START"
        const val ACTION_RESUME = "com.gom.camalert.RESUME"
        const val ACTION_PAUSE = "com.gom.camalert.PAUSE"
        const val ACTION_EXIT = "com.gom.camalert.EXIT"

        fun start(context: Context) {
            val intent = Intent(context, AlertService::class.java).setAction(ACTION_START)
            try {
                context.startForegroundService(intent)
                context.getSystemService(NotificationManager::class.java).cancel(CONNECTED_NOTIFICATION_ID)
            } catch (e: Exception) {
                // Android 12+ 백그라운드 시작이 막힌 경우: 누르면 시작되는 알림으로 대신함
                Log.w(TAG, "background start blocked", e)
                showTapToStart(context)
            }
        }

        fun stop(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(CONNECTED_NOTIFICATION_ID)
            try {
                context.startService(Intent(context, AlertService::class.java).setAction(ACTION_EXIT))
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
                .setSmallIcon(R.drawable.ic_stat_camera)
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
