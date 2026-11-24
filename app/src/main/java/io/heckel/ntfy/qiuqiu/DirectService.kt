package io.heckel.ntfy.qiuqiu

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.msg.NotificationDispatcher
import io.heckel.ntfy.msg.NotificationParser
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** User-enabled foreground connection. FCM mode never starts this service. */
class DirectService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val http=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).pingInterval(25,TimeUnit.SECONDS).build()
    private val api=http.newBuilder().readTimeout(20,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build()
    private var socket:WebSocket?=null
    private var running=false
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        val mode=QiuQiuConfig.prefs(this).getString("receive_mode","fcm")
        if(mode=="fcm"){stopSelf();return START_NOT_STICKY}
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("qiuqiu_direct","后台直连状态",NotificationManager.IMPORTANCE_LOW))
        val pending=PendingIntent.getActivity(this,0,Intent(this,QiuQiuActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        startForeground(30001,NotificationCompat.Builder(this,"qiuqiu_direct").setSmallIcon(R.drawable.ic_qiuqiu).setContentTitle("球球推送 · 后台直连").setContentText(if(mode=="auto")"自动选择：直连优先，FCM备用" else "正在维护后台消息连接").setOngoing(true).setContentIntent(pending).build())
        if(!running){running=true;scope.launch{connectLoop()}}
        return START_STICKY
    }
    private suspend fun connectLoop(){
        val backoff=AtomicLong(1000L)
        while(scope.isActive){
            val prefs=QiuQiuConfig.prefs(this)
            val mode=prefs.getString("receive_mode","fcm")?:"fcm"
            if(mode=="fcm"){stopSelf();return}
            val ended=CompletableDeferred<Unit>()
            try{
                val base=prefs.getString("push_url",null)?:throw IllegalStateException("请先配置服务器")
                val token=Receipts.credential(this,api)
                val profile=JSONObject().put("mode",mode).put("app_version",BuildConfig.VERSION_NAME)
                val request=Request.Builder().url("$base/api/v1/device/profile").header("Authorization","Bearer $token").post(profile.toString().toRequestBody("application/json".toMediaType())).build()
                api.newCall(request).execute().use{if(!it.isSuccessful)throw IllegalStateException("接收模式未保存")}
                socket=http.newWebSocket(Request.Builder().url(base.replaceFirst("https://","wss://")+"/api/v1/device/ws").build(),object:WebSocketListener(){
                    override fun onOpen(ws:WebSocket,response:Response){ws.send(JSONObject().put("token",token).toString())}
                    override fun onMessage(ws:WebSocket,text:String){
                        try{
                            val json=JSONObject(text)
                            when(json.optString("event")){
                                "ping"->ws.send("{\"event\":\"pong\"}")
                                "authenticated"->{prefs.edit().putString("direct_state","已连接").apply();backoff.set(1000L);Receipts.flush(this@DirectService)}
                                "message"->scope.launch{
                                    val repository=Repository.getInstance(this@DirectService)
                                    val sub=repository.getSubscriptions().find{it.topic==json.optString("topic")}?:return@launch
                                    val notification=NotificationParser().parse(text,sub.id,sub.baseUrl)?:return@launch
                                    if(repository.addNotification(notification,updatePollingCursor=false))NotificationDispatcher(this@DirectService,repository).dispatch(sub,notification)
                                    else Receipts.record(this@DirectService,notification,"received")
                                }
                                else->if(json.has("error")){ws.close(1008,"设备授权失败");ended.complete(Unit)}
                            }
                        }catch(_:Exception){prefs.edit().putString("direct_state","消息解析失败").apply()}
                    }
                    override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){ended.complete(Unit)}
                    override fun onClosed(ws:WebSocket,code:Int,reason:String){ended.complete(Unit)}
                })
                ended.await()
            }catch(_:Exception){}finally{socket?.cancel();socket=null;prefs.edit().putString("direct_state","连接恢复中").apply()}
            delay(backoff.get()+kotlin.random.Random.nextLong(500));backoff.updateAndGet{(it*2).coerceAtMost(60000)}
        }
    }
    override fun onDestroy(){scope.cancel();socket?.cancel();QiuQiuConfig.prefs(this).edit().putString("direct_state","未连接").apply();super.onDestroy()}
}
