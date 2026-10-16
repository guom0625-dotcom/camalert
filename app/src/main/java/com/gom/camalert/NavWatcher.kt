package com.gom.camalert

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 내비 앱이 길안내 중에 띄우는 고정 알림을 보고 내비 사용 여부를 판단한다.
 * 알림 내용은 읽지 않고 어느 앱의 고정 알림인지만 본다.
 */
class NavWatcher : NotificationListenerService() {

    override fun onListenerConnected() = check()

    override fun onListenerDisconnected() {
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

        /** 지금 길안내 중인 내비 앱 이름. 없으면 null */
        @Volatile
        var active: String? = null
            private set

        /** 내비 상태가 바뀌면 호출됨 (실행 중인 AlertService가 등록) */
        var listener: (() -> Unit)? = null

        private fun update(context: Context, name: String?, detail: String? = null) {
            if (name == active) return
            active = name
            DiagLog.log(context, if (name != null) "내비 실행 감지: $name ($detail)" else "내비 종료 감지")
            listener?.invoke()
        }

        fun hasAccess(context: Context) = context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, NavWatcher::class.java))
    }
}
