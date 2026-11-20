package io.heckel.ntfy.qiuqiu

import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import io.heckel.ntfy.R
import io.heckel.ntfy.service.SubscriberService
import io.heckel.ntfy.firebase.FirebaseMessenger
import io.heckel.ntfy.msg.ApiService

object QiuQiuConfig {
    fun prefs(context: Context) = context.getSharedPreferences("qiuqiu", Context.MODE_PRIVATE)
    fun server(context: Context): String = prefs(context).getString("ntfy_url",null) ?: context.getString(R.string.app_base_url)
    fun cancelLegacyWork(context: Context) {
        val work=WorkManager.getInstance(context)
        listOf("NtfyPollWorkerPeriodic","ServiceStartWorkerPeriodic","NtfySubscriberServiceStart","NtfyDeleteWorkerPeriodic",SubscriberService.SERVICE_START_WORKER_WORK_NAME_PERIODIC,"ServiceStartWorkerOnce").forEach { work.cancelUniqueWork(it) }
        context.stopService(Intent(context,SubscriberService::class.java))
        FirebaseMessenger().unsubscribe(ApiService.CONTROL_TOPIC)
    }
}
