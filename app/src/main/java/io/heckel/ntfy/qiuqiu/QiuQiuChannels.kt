package io.heckel.ntfy.qiuqiu

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import com.google.gson.JsonParser
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification

object QiuQiuChannels {
    val businesses=linkedMapOf("server" to "服务器","security" to "安全","email" to "邮件","task" to "任务","network" to "网络","custom" to "自定义")
    val baseIds=mapOf("server" to "server_alert","security" to "security_alert","email" to "email_notice","task" to "task_complete","network" to "network_alert","custom" to "custom_notice")
    fun meta(n:Notification,key:String):String? = try { n.qiuqiu?.let { JsonParser.parseString(it).asJsonObject.get(key)?.asString } }catch(_:Exception){null}
    fun icon(n:Notification):Int = when(meta(n,"icon")?:meta(n,"business_type")) {"server"->R.drawable.ic_server;"security"->R.drawable.ic_security;"email"->R.drawable.ic_email;"task"->R.drawable.ic_task;"network"->R.drawable.ic_network;"custom"->R.drawable.ic_custom;else->R.drawable.ic_qiuqiu}
    fun ensureDefaults(context:Context) { businesses.keys.forEach { ensure(context,it,"default",if(it=="server"||it=="security"||it=="network")4 else 3) } }
    fun channel(context:Context,n:Notification):String {
        val business=(meta(n,"business_type")?:"custom").takeIf { businesses.containsKey(it) }?:"custom"
        val configured=QiuQiuConfig.prefs(context).getString("sound_$business","default")?:"default"
        val sound=(meta(n,"sound")?.takeUnless { it=="default" }?:configured).takeIf { it in listOf("default","alarm","soft","silent") }?:"default"
        return ensure(context,business,sound,n.priority)
    }
    fun ensure(context:Context,business:String,sound:String,priority:Int):String {
        val base=baseIds[business]?:"custom_notice"
        val initialHigh=business in listOf("server","security","network")
        val band=if(priority<=2)"quiet" else if(priority>=4)"high" else "normal"
        val defaultBand=if(initialHigh)"high" else "normal"
        val id=if(sound=="default"&&band==defaultBand)base else "${base}_${sound}_$band"
        val manager=context.getSystemService(NotificationManager::class.java)
        if(manager.getNotificationChannel(id)!=null)return id // Android/user owns existing channel settings.
        val importance=if(priority<=2)NotificationManager.IMPORTANCE_LOW else if(priority>=4)NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
        val channel=NotificationChannel(id,"${businesses[business]?:"自定义"} · $sound · $band",importance)
        val uri=when(sound){"silent"->null;"alarm"->Uri.parse("android.resource://${context.packageName}/${R.raw.qiuqiu_alarm}");"soft"->Uri.parse("android.resource://${context.packageName}/${R.raw.qiuqiu_soft}");else->RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)}
        channel.setSound(uri,AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        channel.enableVibration(priority>=4)
        manager.createNotificationChannel(channel)
        return id
    }
    fun allowedUrl(context:Context,uri:Uri):Boolean {
        if(uri.toString().length>2048||uri.toString().any { it=='\n'||it=='\r' })return false
        val scheme=uri.scheme?.lowercase()?:return false
        if(scheme=="https")return !uri.host.isNullOrEmpty()&&uri.userInfo==null
        if(scheme in listOf("http","javascript","data","file","content","intent"))return false
        return scheme in (QiuQiuConfig.prefs(context).getString("allowed_schemes","")?:"").split(',').map { it.trim().lowercase() }.filter { it.matches(Regex("[a-z][a-z0-9+.-]*")) }
    }
}
