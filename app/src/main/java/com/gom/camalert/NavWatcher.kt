package com.gom.camalert

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 내비 앱이 실행 중인지 두 가지로 판단한다.
 * - 알림: 백그라운드에서 길안내할 때 띄우는 고정 알림 (내용은 읽지 않고 어느 앱인지만 봄)
 * - 화면: 내비 앱이 화면(분할 화면 포함)에 떠 있는지. 화면에 떠 있는 동안엔 고정 알림을 안 띄우는 내비가 있음
 */
class NavWatcher : NotificationListenerService() {

    override fun onListenerConnected() {
        DiagLog.log(this, "알림 접근 연결됨 (내비 감지 준비)")
        check()
    }

    override fun onListenerDisconnected() {
        DiagLog.log(this, "알림 접근 끊김")
        update(this, null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in NAV_APPS) check()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in NAV_APPS) check()
    }

    private fun check() {
        val notifications = try {
            activeNotifications.orEmpty()
        } catch (e: Exception) {
            return
        }
        val found = notifications.firstOrNull { it.packageName in NAV_APPS && it.isPersistent() }
        update(this, found?.let { NAV_APPS[it.packageName] }, found?.describe())
    }

    /** 진단용: 길안내 알림과 다른 고정 알림(위치 공유 등)을 나중에 구분할 수 있게 종류만 남김. 내용은 읽지 않음 */
    private fun StatusBarNotification.describe() =
        "채널=${notification.channelId} 분류=${notification.category ?: "-"}"

    private fun StatusBarNotification.isPersistent() =
        isOngoing || notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0

    companion object {
        /** 감지 대상 내비 앱: 패키지 → 이름 */
        val NAV_APPS = mapOf(
            "com.skt.tmap.ku" to "TMAP",
            "com.skt.skaf.l001mtm091" to "TMAP",
            "com.nhn.android.nmap" to "네이버 지도",
            "com.locnall.KimGiSa" to "카카오내비",
            "net.daum.android.map" to "카카오맵",
        )

        /** 지금 실행 중인 내비 앱 이름. 없으면 null */
        @Volatile
        var active: String? = null
            private set

        /** 고정 알림으로 감지한 내비 */
        @Volatile private var notifApp: String? = null
        /** 화면에 떠 있는 내비 */
        @Volatile private var screenApp: String? = null

        /** 화면에 떠 있는 내비 액티비티 ("패키지/클래스") */
        private val visible = mutableSetOf<String>()
        private var lastQuery = 0L

        /** 내비 상태가 바뀌면 호출됨 (실행 중인 AlertService가 등록) */
        var listener: (() -> Unit)? = null

        private fun update(context: Context, name: String?, detail: String? = null) {
            if (name == notifApp) return
            notifApp = name
            DiagLog.log(context, if (name != null) "내비 알림 감지: $name ($detail)" else "내비 알림 사라짐")
            publish()
        }

        private fun publish() {
            val now = screenApp ?: notifApp
            if (now == active) return
            active = now
            listener?.invoke()
        }

        /** 화면 감시를 처음부터 다시 (서비스 시작 시). 최근 기록부터 다시 읽어 이미 떠 있는 내비도 잡음 */
        fun resetScreen() {
            visible.clear()
            lastQuery = 0L
            screenApp = null
            publish()
        }

        /** 사용 정보에서 액티비티가 화면에 나타나고 사라진 기록을 읽어 화면에 떠 있는 내비를 갱신. 메인 스레드에서 주기적으로 호출 */
        fun pollScreen(context: Context) {
            if (!hasUsageAccess(context)) return
            val now = System.currentTimeMillis()
            val from = if (lastQuery == 0L) now - SCREEN_LOOKBACK_MS else lastQuery
            lastQuery = now
            val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(from, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName !in NAV_APPS) continue
                val key = "${event.packageName}/${event.className}"
                when (event.eventType) {
                    // 분할 화면에선 다른 쪽을 눌러도 PAUSED만 오고 계속 보이므로, 사라짐은 STOPPED로만 판단
                    UsageEvents.Event.ACTIVITY_RESUMED -> visible += key
                    UsageEvents.Event.ACTIVITY_STOPPED -> visible -= key
                }
            }
            val name = visible.firstOrNull()?.let { NAV_APPS[it.substringBefore('/')] }
            if (name == screenApp) return
            screenApp = name
            DiagLog.log(context, if (name != null) "내비 화면 감지: $name" else "내비 화면 사라짐")
            publish()
        }

        private const val SCREEN_LOOKBACK_MS = 3 * 60 * 60 * 1000L

        fun hasAccess(context: Context) = context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, NavWatcher::class.java))

        /** '사용 정보 접근' 권한 (화면에 떠 있는 내비 감지용) */
        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            return ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }
    }
}
