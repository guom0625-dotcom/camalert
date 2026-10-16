package com.gom.camalert

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 진단 기록. 앱 내부 저장소에만 쓰고 외부로 보내지 않는다.
 * 설정에서 끌 수 있고, 1MB를 넘으면 오래된 절반을 지운다.
 */
object DiagLog {
    private const val FILE = "diag.log"
    private const val MAX_BYTES = 1_000_000L
    private const val COPY_BYTES = 200_000L

    private val writer = Executors.newSingleThreadExecutor()
    // SimpleDateFormat은 스레드에 안전하지 않으므로 기록 스레드에서만 씀
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)

    fun log(context: Context, message: String) {
        Log.i("CamalertDiag", message)
        val app = context.applicationContext
        if (!Prefs(app).diagEnabled) return
        val now = System.currentTimeMillis()
        writer.execute {
            try {
                val file = File(app.filesDir, FILE)
                file.appendText("${time.format(Date(now))} $message\n")
                if (file.length() > MAX_BYTES) trim(file)
            } catch (e: Exception) {
                Log.w("CamalertDiag", "write failed", e)
            }
        }
    }

    /** 최근 기록 (클립보드에 넣기 좋은 크기로 자름) */
    fun recent(context: Context): String = tail(File(context.filesDir, FILE), COPY_BYTES)

    fun sizeBytes(context: Context) = File(context.filesDir, FILE).length()

    fun clear(context: Context) {
        writer.execute { File(context.applicationContext.filesDir, FILE).delete() }
    }

    private fun trim(file: File) {
        val keep = tail(file, MAX_BYTES / 2)
        file.writeText(keep)
    }

    private fun tail(file: File, bytes: Long): String {
        if (!file.exists()) return ""
        RandomAccessFile(file, "r").use { raf ->
            val start = (raf.length() - bytes).coerceAtLeast(0)
            raf.seek(start)
            val buffer = ByteArray((raf.length() - start).toInt())
            raf.readFully(buffer)
            val text = String(buffer, Charsets.UTF_8)
            // 잘린 첫 줄은 버림
            return if (start > 0) text.substringAfter('\n') else text
        }
    }
}
