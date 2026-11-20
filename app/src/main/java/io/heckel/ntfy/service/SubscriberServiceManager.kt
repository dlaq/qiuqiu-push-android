package io.heckel.ntfy.service

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/** Compatibility surface for reused ntfy screens. QiuQiu never starts a subscriber service. */
class SubscriberServiceManager(private val context: Context) {
    fun refresh() { refresh(context) }
    class ServiceStartWorker(context: Context,params: WorkerParameters):CoroutineWorker(context,params) {
        override suspend fun doWork(): Result {
            refresh(applicationContext)
            return Result.success()
        }
    }
    companion object {
        const val WORK_NAME_ONCE="ServiceStartWorkerOnce"
        fun refresh(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_ONCE)
            context.stopService(Intent(context,SubscriberService::class.java))
        }
    }
}
