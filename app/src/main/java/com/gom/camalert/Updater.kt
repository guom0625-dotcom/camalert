package com.gom.camalert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub Releases에서 새 버전을 받아 설치한다.
 * 한 번 이 앱이 직접 설치한 뒤부터는 Android 12+에서 확인 창 없이 업데이트된다.
 * 업데이트하면 앱 프로세스가 재시작되므로 운전 중(서비스 실행 중)에는 설치하지 않는다.
 */
object Updater {

    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String)

    private const val TAG = "Updater"
    private const val LATEST = "https://api.github.com/repos/guom0625-dotcom/camalert/releases/latest"
    private const val ASSET = "camalert.apk"
    private const val CHANNEL = "update"
    private const val NOTIFICATION_ID = 3

    /** 화면이 떠 있으면 설치 확인 창을 바로 띄우고, 아니면 알림으로 */
    @Volatile var foreground = false

    /** 내려받기/설치가 동시에 두 번 돌지 않게 */
    private val busy = AtomicBoolean(false)
    val installing get() = busy.get()

    fun currentVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** 블로킹 호출. 새 버전이 없거나 실패하면 null */
    fun findNewer(context: Context): Release? = try {
        val json = JSONObject(get(LATEST).bufferedReader().use { it.readText() })
        // 태그는 워크플로가 v0.1.<run_number> 형식으로 만들고, run_number가 versionCode
        val tag = json.getString("tag_name").removePrefix("v")
        val code = tag.substringAfterLast('.').toInt()
        val assets = json.getJSONArray("assets")
        val url = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name") == ASSET }
            ?.getString("browser_download_url")
        if (url != null && code > currentVersionCode(context)) Release(code, tag, url) else null
    } catch (e: Exception) {
        Log.w(TAG, "update check failed", e)
        null
    }

    /**
     * 블로킹 호출. 내려받아 설치 세션을 커밋한다.
     * onProgress(받은 바이트, 전체 바이트). 전체 크기를 모르면 -1.
     * skipIfDriving: 내려받는 사이 다시 차에 타서 카메라 알림이 켜졌으면 설치하지 않음 (앱이 재시작되므로)
     */
    fun install(
        context: Context,
        release: Release,
        skipIfDriving: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) return false
        if (!busy.compareAndSet(false, true)) return false
        return try {
            val apk = File(context.cacheDir, "update.apk")
            download(release.apkUrl, apk, onProgress)
            if (skipIfDriving && AlertService.statusLine != null) {
                DiagLog.log(context, "자동 업데이트: 카메라 알림이 다시 켜져서 설치를 미룸")
                apk.delete()
                return false
            }

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply {
                    setAppPackageName(context.packageName)
                    if (Build.VERSION.SDK_INT >= 31) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = PendingIntent.getBroadcast(
                    context, sessionId,
                    Intent(context, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(callback.intentSender)
            }
            apk.delete()
            true
        } catch (e: Exception) {
            Log.w(TAG, "update install failed", e)
            false
        } finally {
            busy.set(false)
        }
    }

    private fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        val total = connection.contentLengthLong
        var received = 0L
        var lastReported = -1L
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    received += n
                    // 1%마다만 알림 (UI 갱신 과다 방지)
                    val step = if (total > 0) received * 100 / total else received / (512 * 1024)
                    if (step != lastReported) {
                        lastReported = step
                        onProgress(received, total)
                    }
                }
            }
        }
    }

    /** 차량 연결이 끊긴 뒤처럼 아무도 안 볼 때 조용히 확인하고 설치 */
    fun autoUpdate(context: Context) {
        val app = context.applicationContext
        Thread {
            val release = findNewer(app) ?: return@Thread
            // 확인하는 사이 다시 차에 탔으면 다음 하차 때로 미룸
            if (AlertService.statusLine != null) return@Thread
            DiagLog.log(app, "자동 업데이트: ${release.versionName} 내려받기 시작")
            val ok = install(app, release, skipIfDriving = true)
            if (ok) DiagLog.log(app, "자동 업데이트: 설치 요청됨")
            else if (AlertService.statusLine == null) DiagLog.log(app, "자동 업데이트: 실패")
        }.start()
    }

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Accept", "application/vnd.github+json")
        inputStream
    }

    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.i(TAG, "install status=$status $message")
                if (status != PackageInstaller.STATUS_SUCCESS) DiagLog.log(context, "업데이트 설치 실패: status=$status $message")
                return
            }
            // 처음 한 번은 사용자 확인이 필요함
            val confirm = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            } ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (foreground) {
                context.startActivity(confirm)
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "업데이트", NotificationManager.IMPORTANCE_DEFAULT))
            val pending = PendingIntent.getActivity(
                context, 0, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_dog)
                    .setContentTitle("gomCamalert 새 버전")
                    .setContentText("눌러서 업데이트 설치")
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
