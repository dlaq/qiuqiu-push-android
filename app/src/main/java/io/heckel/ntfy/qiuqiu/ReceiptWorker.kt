package io.heckel.ntfy.qiuqiu

import android.content.Context
import androidx.work.*
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persist first; message-triggered jobs make at most three network attempts. */
object Receipts {
    private val lock=Any()
    private val authLock=Mutex()
    fun record(context:Context,n:Notification,stage:String,reason:String="") {
        val id=QiuQiuChannels.meta(n,"message_id")?:n.id
        recordId(context,id,stage,reason)
    }
    fun recordId(context:Context,id:String,stage:String,reason:String="") {
        if(id.isBlank())return
        synchronized(lock) {
            val prefs=QiuQiuConfig.prefs(context)
            val queue=JSONArray(prefs.getString("receipt_queue","[]"))
            if((0 until queue.length()).none {val a=queue.getJSONObject(it);a.getString("message_id")==id&&a.getString("stage")==stage}) {
                queue.put(JSONObject().put("message_id",id).put("stage",stage).put("event_time",System.currentTimeMillis()/1000).put("reason",reason.take(500)))
                prefs.edit().putString("receipt_queue",queue.toString()).commit()
            }
        }
        flush(context)
    }
    fun flush(context:Context) {
        if(QiuQiuConfig.prefs(context).getString("receipt_queue","[]")=="[]")return
        val work=OneTimeWorkRequestBuilder<ReceiptWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("QiuQiuReceipts",ExistingWorkPolicy.APPEND_OR_REPLACE,work)
    }
    fun batch(context:Context):JSONArray=synchronized(lock) {val q=JSONArray(QiuQiuConfig.prefs(context).getString("receipt_queue","[]"));JSONArray().apply{for(i in 0 until minOf(100,q.length()))put(q.get(i))}}
    fun complete(context:Context,batch:JSONArray)=synchronized(lock){
        val keys=(0 until batch.length()).map {val a=batch.getJSONObject(it);a.getString("message_id")+":"+a.getString("stage")}.toSet()
        val prefs=QiuQiuConfig.prefs(context);val q=JSONArray(prefs.getString("receipt_queue","[]"));val rest=JSONArray()
        for(i in 0 until q.length()){val a=q.getJSONObject(i);if(a.getString("message_id")+":"+a.getString("stage") !in keys)rest.put(a)}
        prefs.edit().putString("receipt_queue",rest.toString()).commit()
    }
    suspend fun credential(context:Context,http:OkHttpClient):String = authLock.withLock {
        val prefs=QiuQiuConfig.prefs(context)
        prefs.getString("device_api_token",null)?.let{return@withLock SecretBox.decrypt(it)}
        val base=prefs.getString("push_url",null)?:throw IllegalStateException("请先配置服务器")
        val ntfy=prefs.getString("ntfy_url",null)?:throw IllegalStateException("请先配对")
        val user=Repository.getInstance(context).getUser(ntfy)?:throw IllegalStateException("设备读取凭证不存在")
        val req=Request.Builder().url("$base/api/v1/device/upgrade").header("Authorization","Bearer ${user.password}").post("{}".toRequestBody("application/json".toMediaType())).build()
        val token=http.newCall(req).execute().use{response->if(!response.isSuccessful)throw IllegalStateException("设备接口授权失败");JSONObject(response.body!!.string()).getString("device_token")}
        prefs.edit().putString("device_api_token",SecretBox.encrypt(token)).commit();token
    }
}

class ReceiptWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val prefs=QiuQiuConfig.prefs(applicationContext)
        val base=prefs.getString("push_url",null)?:return Result.success()
        val http=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build()
        return try {
            val credential=Receipts.credential(applicationContext,http)
            val batch=Receipts.batch(applicationContext);if(batch.length()==0)return Result.success()
            val req=Request.Builder().url("$base/api/v1/device/receipts").header("Authorization","Bearer $credential").post(JSONObject().put("receipts",batch).toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use{if(!it.isSuccessful)throw IllegalStateException("回执暂未送达")}
            Receipts.complete(applicationContext,batch)
            Result.success()
        }catch(_:Exception){if(runAttemptCount<2)Result.retry() else Result.success()}
    }
}
