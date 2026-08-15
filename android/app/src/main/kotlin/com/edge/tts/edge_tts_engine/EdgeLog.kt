package com.edge.tts.edge_tts_engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志：输出到 /sdcard/Download/edge-tts-logs/edge-tts-<启动时间>.log。
 * 连点「使用说明」卡片 7 次启用（写入 SharedPreferences 持久化），
 * 启用后应用任何线程的未捕获异常也会写入该文件，并弹出错误页后退出。
 */
object EdgeLog {
    private const val TAG = "EdgeTtsLog"
    private const val LOG_DIR = "edge-tts-logs"
    private const val PREF = "edge_tts_log"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CRASH = "last_crash"

    @Volatile private var enabled = false
    @Volatile private var path: String = ""
    @Volatile private var uri: android.net.Uri? = null
    private var appCtx: Context? = null
    private var logName: String = ""

    fun isEnabled(): Boolean = enabled

    fun enable(context: Context): String {
        appCtx = context.applicationContext
        enabled = true
        appCtx!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, true).apply()
        val p = resolveSink()
        path = p
        d(TAG, "日志模式已启用, path=$p")
        return p
    }

    fun init(context: Context) {
        appCtx = context.applicationContext
        logName = "edge-tts-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
        enabled = appCtx!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
        if (enabled) {
            path = resolveSink()
            d(TAG, "日志模式已从持久化恢复")
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            e(TAG, "UNCAUGHT on ${thread.name}", t)
            showErrorAndExit(t)
        }
    }

    /** 记录到日志文件（仅日志模式启用时写入）。 */
    fun record(msg: String) {
        if (enabled) d(TAG, msg)
    }

    private fun showErrorAndExit(t: Throwable) {
        val ctx = appCtx ?: return
        try {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val stack = sw.toString()
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_CRASH, stack).commit()
            val intent = Intent(ctx, EdgeErrorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("error", stack)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "show error page failed", e)
            Runtime.getRuntime().halt(2)
        }
    }

    private fun resolveSink(): String {
        val ctx = appCtx ?: return ""
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, logName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$LOG_DIR",
                    )
                }
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    "下载/$LOG_DIR/$logName"
                } else {
                    fallbackFile()
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS,
                    ),
                    LOG_DIR,
                )
                if (dir.exists() || dir.mkdirs()) {
                    File(dir, logName).absolutePath
                } else {
                    fallbackFile()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveSink failed", e)
            fallbackFile()
        }
    }

    private fun fallbackFile(): String {
        val dir = appCtx?.getExternalFilesDir(null)
            ?: return "无可用日志路径"
        return File(dir, logName).absolutePath
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        if (enabled) write("[D][$tag] $msg")
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        if (enabled) write("[W][$tag] $msg")
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        if (enabled) {
            val sw = StringWriter()
            if (t != null) t.printStackTrace(PrintWriter(sw))
            write("[E][$tag] $msg" + if (t != null) "\n$sw" else "")
        }
    }

    private fun write(line: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val body = "$ts $line\n"
        try {
            val u = uri
            val ctx = appCtx ?: return
            if (u != null && Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.openOutputStream(u, "wa")?.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }
                return
            }
            val f = File(path)
            FileOutputStream(f, true).use { it.write(body.toByteArray(Charsets.UTF_8)) }
        } catch (ignored: Exception) {
        }
    }
}