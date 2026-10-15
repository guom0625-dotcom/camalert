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

    /** 블로킹 호출. 내려받아 설치 세션을 커밋한다. */
    fun install(context: Context, release: Release): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) return false
        return try {
            val apk = File(context.cacheDir, "update.apk")
            get(release.apkUrl).use { input -> apk.outputStream().use { input.copyTo(it) } }

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
        }
    }

    /** 차량 연결이 끊긴 뒤처럼 아무도 안 볼 때 조용히 확인하고 설치 */
    fun autoUpdate(context: Context) {
        val app = context.applicationContext
        Thread {
            val release = findNewer(app) ?: return@Thread
            install(app, release)
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
                Log.i(TAG, "install status=$status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
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
                    .setSmallIcon(R.drawable.ic_stat_camera)
                    .setContentTitle("gomCamalert 새 버전")
                    .setContentText("눌러서 업데이트 설치")
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
