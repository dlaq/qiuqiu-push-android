package io.heckel.ntfy.qiuqiu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Only resumes a foreground connection explicitly enabled by the user. */
class DirectBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val prefs = QiuQiuConfig.prefs(context)
        if (prefs.getString("receive_mode", "fcm") !in listOf("direct", "auto")) return
        if (prefs.getString("device_id", null) == null) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, DirectService::class.java))
        } catch (_: Exception) {
            prefs.edit().putString("direct_state", "系统未允许后台启动，请打开应用恢复连接").apply()
        }
    }
}
