package io.heckel.ntfy.qiuqiu

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.heckel.ntfy.R
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.db.*
import io.heckel.ntfy.firebase.FirebaseMessenger
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.Poller
import io.heckel.ntfy.ui.DetailActivity
import io.heckel.ntfy.util.randomSubscriptionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/** QiuQiu shell; transport, message database and rendering reuse the pinned ntfy client. */
class QiuQiuActivity:AppCompatActivity() {
    private val repository by lazy { Repository.getInstance(this) }
    private val prefs by lazy { QiuQiuConfig.prefs(this) }
    private val http=OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS).followRedirects(false).build()
    private lateinit var body:LinearLayout
    private lateinit var status:TextView
    private var page="首页"
    private var session=""
    private var account:JsonObject?=null
    private var busy=false
    private val ink=Color.rgb(36,52,62)
    private val pink=Color.rgb(199,67,113)
    private val muted=Color.rgb(97,113,124)

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(intent.getBooleanExtra("show_history",false))page="通知"
        QiuQiuChannels.ensureDefaults(this)
        Receipts.flush(this)
        if(prefs.getString("receive_mode","fcm")!="fcm")androidx.core.content.ContextCompat.startForegroundService(this,Intent(this,DirectService::class.java))
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),12)
        render()
        intent.getStringExtra("focus_message")?.let { id ->
            lifecycleScope.launch { val n=withContext(Dispatchers.IO){repository.getNotification(id)};if(n!=null)showMessage(n) }
        }
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun rounded(color:Int,r:Int=18)=GradientDrawable().apply {setColor(color);cornerRadius=dp(r).toFloat()}
    private fun text(s:String,size:Float=16f,color:Int=ink,bold:Boolean=false)=TextView(this).apply {text=s;textSize=size;setTextColor(color);if(bold)setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(5),0,dp(7));setTextIsSelectable(true)}
    private fun card(title:String):LinearLayout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(17),dp(20),dp(17));background=rounded(Color.WHITE);layoutParams=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(14)};addView(text(title,18f,ink,true));body.addView(this)}
    private fun button(parent:LinearLayout,label:String,click:()->Unit){parent.addView(Button(this).apply {text=label;isAllCaps=false;setTextColor(Color.WHITE);backgroundTintList=null;background=rounded(pink,12);elevation=0f;minHeight=dp(46);setPadding(dp(14),dp(10),dp(14),dp(10));layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10);bottomMargin=dp(4)};setOnClickListener{if(!busy)click()}})}
    private fun input(parent:LinearLayout,hint:String,password:Boolean=false,value:String=""):EditText=EditText(this).apply {this.hint=hint;setText(value);setTextColor(ink);setHintTextColor(muted);setSingleLine(true);if(password)inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;parent.addView(this,LinearLayout.LayoutParams(-1,dp(52)))}
    private fun notice(s:String){status.text=s}
    private fun task(action:suspend()->Unit){if(busy)return;busy=true;notice("正在处理…");lifecycleScope.launch {try{action();if(!isFinishing)notice("已完成")}catch(e:Exception){if(!isFinishing)notice(e.message?:"操作失败")}finally{busy=false}}}
    private fun render(){
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(247,248,250));setPadding(dp(18),dp(14),dp(18),0)}
        ViewCompat.setOnApplyWindowInsetsListener(root){v,insets->val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(dp(18),bars.top+dp(14),dp(18),bars.bottom);insets}
        val header=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        header.addView(ImageView(this).apply {setImageResource(R.drawable.qiuqiu_brand)},LinearLayout.LayoutParams(dp(55),dp(55)))
        header.addView(text("球球推送",24f,ink,true));root.addView(header)
        val nav=HorizontalScrollView(this).apply {isHorizontalScrollBarEnabled=false};val row=LinearLayout(this)
        for(label in listOf("首页","服务器","订阅","通知","业务设置","API","关于")){row.addView(TextView(this).apply {text=label;textSize=14f;gravity=Gravity.CENTER;setTextColor(if(label==page)pink else muted);background=rounded(if(label==page)Color.rgb(253,234,241) else Color.TRANSPARENT,12);setPadding(dp(16),dp(12),dp(16),dp(12));layoutParams=LinearLayout.LayoutParams(-2,dp(46)).apply{marginEnd=dp(6)};isClickable=true;isFocusable=true;setOnClickListener{page=label;render()}})};nav.addView(row);root.addView(nav)
        status=text("",14f,muted);root.addView(status)
        val scroll=ScrollView(this);body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        when(page){"首页"->home();"服务器"->serverPage();"订阅"->subscriptionsPage();"通知"->history();"业务设置"->businessPage();"API"->apiPage();else->about()}
    }
    private fun home(){
        val hero=card("每一条重要消息，都有自己的声音。")
        hero.background=rounded(Color.rgb(253,234,241))
        hero.addView(text("FCM 按需唤醒 · 独立业务通知",15f,muted))
        val firebaseReady=FirebaseStatus.ready(this)
        val notifications=getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val state=card("接收状态")
        state.addView(text("Firebase：${if(firebaseReady)"已配置" else "未配置，需要使用自己的 google-services.json 构建"}",15f))
        state.addView(text("通知权限：${if(notifications)"已允许" else "未允许"}",15f))
        state.addView(text("设备：${prefs.getString("device_name",null)?:prefs.getString("device_id",null)?.takeLast(6)?:"尚未配对"}",14f,muted))
        state.addView(text("服务器：${prefs.getString("ntfy_url",null)?:"尚未设置"}",14f,muted))
        button(state,"打开通知权限设置"){startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))}
        button(state,"管理服务器与设备配对"){page="服务器";render()}
        state.addView(text("状态来自本地配置和最近接收记录，不表示与服务器保持连接。",14f,muted))
        lifecycleScope.launch {val last=withContext(Dispatchers.IO){repository.getNotifications().maxByOrNull {it.timestamp}};if(last!=null&&page=="首页"){val c=card("最近收到");c.addView(text(last.title.ifEmpty{"无标题消息"},17f,ink,true));c.addView(text(last.message.take(300),15f));c.addView(text(DateFormat.getDateTimeInstance().format(Date(last.timestamp*1000)),13f,muted))}}
    }
    private fun serverPage(){
        val c=card("连接你的服务器")
        val url=input(c,"https://push.你的域名",value=prefs.getString("push_url","")?:"")
        button(c,"保存并测试连接"){task {val base=validBase(url.text.toString());withContext(Dispatchers.IO){val req=Request.Builder().url("$base/healthz").build();http.newCall(req).execute().use {if(!it.isSuccessful)throw IllegalStateException("服务器连接失败")}};prefs.edit().putString("push_url",base).apply()}}
        val modes=listOf("fcm","direct","auto")
        val modeCard=card("消息接收模式")
        modeCard.addView(text("FCM：省电按需唤醒。后台直连：常驻通知、心跳和重连。自动：直连优先，FCM备用。直连会增加后台网络与功耗。",14f,muted))
        val selector=Spinner(this).apply{adapter=ArrayAdapter(this@QiuQiuActivity,android.R.layout.simple_spinner_dropdown_item,listOf("FCM 省电","后台直连","自动选择"));setSelection(modes.indexOf(prefs.getString("receive_mode","fcm")).coerceAtLeast(0))};modeCard.addView(selector)
        modeCard.addView(text("直连状态：${prefs.getString("direct_state","未连接")}",14f,muted))
        button(modeCard,"应用接收模式"){task{
            val selected=modes[selector.selectedItemPosition]
            withContext(Dispatchers.IO){val base=validBase(prefs.getString("push_url","")?:"");val key=Receipts.credential(this@QiuQiuActivity,http);val body=JsonObject().apply{addProperty("mode",selected);addProperty("app_version",BuildConfig.VERSION_NAME)};val req=Request.Builder().url("$base/api/v1/device/profile").header("Authorization","Bearer $key").post(body.toString().toRequestBody("application/json".toMediaType())).build();http.newCall(req).execute().use{if(!it.isSuccessful)throw IllegalStateException("接收模式保存失败")}}
            prefs.edit().putString("receive_mode",selected).apply()
            val subs=withContext(Dispatchers.IO){repository.getSubscriptions()};for(sub in subs){if(selected=="direct")FirebaseMessenger().unsubscribe(sub.topic) else FirebaseMessenger().subscribe(sub.topic)}
            stopService(Intent(this,DirectService::class.java));if(selected!="fcm")androidx.core.content.ContextCompat.startForegroundService(this,Intent(this,DirectService::class.java));render()
        }}
        val pair=card("一次性设备配对")
        pair.addView(text("在管理后台生成配对码；配对后设备仅能读取自己的私有收件箱。",14f,muted))
        val code=input(pair,"配对码",true);val deviceName=input(pair,"设备名称",value=Build.MODEL)
        button(pair,"配对设备"){task {
            if(prefs.getString("device_id",null)!=null)throw IllegalStateException("此安装已配对，请先在后台撤销旧设备并清除应用数据，避免遗留读取凭证")
            val result=api("pair/claim","POST",JsonObject().apply {addProperty("code",code.text.toString().trim());addProperty("name",deviceName.text.toString())},false)
            val ntfy=validBase(result.get("ntfy_url").asString);val topic=result.get("topic").asString
            withContext(Dispatchers.IO){repository.addUser(User(ntfy,"token",result.get("ntfy_token").asString));repository.addSubscription(Subscription(id=randomSubscriptionId(),baseUrl=ntfy,topic=topic,instant=false,mutedUntil=0,minPriority=Repository.MIN_PRIORITY_USE_GLOBAL,autoDelete=Repository.AUTO_DELETE_USE_GLOBAL,insistent=0,lastNotificationId=null,icon=null,upAppId=null,upConnectorToken=null,displayName="我的消息",dedicatedChannels=false));repository.setDefaultBaseUrl(ntfy)}
            prefs.edit().putString("ntfy_url",ntfy).putString("device_id",result.get("device_id").asString).putString("device_name",deviceName.text.toString()).putString("device_api_token",SecretBox.encrypt(result.get("device_token").asString)).putString("uid",result.get("user_id").asString).apply()
            FirebaseMessenger().subscribe(topic);code.setText("");Toast.makeText(this,"已绑定设备，请从后台发送测试通知",Toast.LENGTH_LONG).show()
        }}
        accountLogin()
    }
    private fun accountLogin(){
        val c=card(if(session.isEmpty())"登录管理账户" else "管理账户：${account?.get("name")?.asString?:"已登录"}")
        if(session.isNotEmpty()){button(c,"退出管理账户"){task{api("logout","POST",JsonObject());session="";account=null;render()}};return}
        c.addView(text("登录后管理自己的订阅和 API 凭证。管理会话仅保存在内存，关闭应用后需重新登录。",14f,muted))
        val name=input(c,"用户名");val pass=input(c,"密码",true)
        button(c,"登录"){task{val result=api("login","POST",JsonObject().apply{addProperty("name",name.text.toString());addProperty("password",pass.text.toString())},false);session=result.get("token").asString;account=result.getAsJsonObject("user");pass.setText("");render()}}
    }
    private fun subscriptionsPage(){
        val local=card("设备收件箱")
        lifecycleScope.launch {val subs=withContext(Dispatchers.IO){repository.getSubscriptions()};for(sub in subs){local.addView(text(sub.displayName?:sub.topic,16f,ink,true));local.addView(text(sub.baseUrl+"/"+sub.topic,13f,muted));button(local,"手动获取一次消息"){task{withContext(Dispatchers.IO){Poller(ApiService(this@QiuQiuActivity),repository).poll(sub)};Toast.makeText(this@QiuQiuActivity,"已同步，请在通知页查看",Toast.LENGTH_SHORT).show()}}};if(subs.isEmpty())local.addView(text("尚未配对。请先设置服务器并绑定设备。",15f,muted))}
        if(session.isEmpty()){accountLogin();return}
        val c=card("业务主题")
        val name=input(c,"新主题，例如 server-alert")
        button(c,"创建主题"){task{api("manage/topics","POST",JsonObject().apply{addProperty("name",name.text.toString())});render()}}
        lifecycleScope.launch {try{val topics=api("manage/topics").getAsJsonArray("data");val bindings=api("manage/bindings").getAsJsonArray("data");val device=prefs.getString("device_id","")?:""
            for(item in topics){val t=item.asJsonObject;val id=t.get("id").asInt;val bound=bindings.any {it.asJsonObject.get("topic_id").asInt==id&&it.asJsonObject.get("device_id").asString==device};c.addView(text(t.get("name").asString,16f,ink,true));button(c,if(bound)"取消本机订阅" else "订阅到本机"){task{if(device.isEmpty())throw IllegalStateException("请先配对设备");api("manage/bindings",if(bound)"DELETE" else "POST",JsonObject().apply{addProperty("topic_id",id);addProperty("device_id",device)});render()}}}
        }catch(e:Exception){notice(e.message?:"加载失败")}}
    }
    private fun history(){
        val c=card("通知历史")
        lifecycleScope.launch {val all=withContext(Dispatchers.IO){repository.getNotifications().filterNot{it.deleted}.sortedByDescending{it.timestamp}.take(200)};if(all.isEmpty())c.addView(text("还没有消息。",15f,muted));for(n in all){c.addView(text(n.title.ifEmpty{"无标题消息"},17f,ink,true));c.addView(text(android.text.Html.fromHtml(n.message,android.text.Html.FROM_HTML_MODE_COMPACT).toString().take(250),15f));c.addView(text(DateFormat.getDateTimeInstance().format(Date(n.timestamp*1000)),13f,muted));button(c,"查看详情"){showMessage(n)}}}
    }
    private fun showMessage(n:Notification){
        Receipts.record(this,n,"opened")
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(12));addView(text(if(QiuQiuChannels.meta(n,"content_type")=="html")android.text.Html.fromHtml(n.message,android.text.Html.FROM_HTML_MODE_COMPACT).toString() else n.message,16f))}
        if(n.contentType=="text/markdown"||QiuQiuChannels.meta(n,"content_type")=="markdown"){
 val messageView=content.getChildAt(0) as TextView;io.noties.markwon.Markwon.create(this).setMarkdown(messageView,n.message)
}
val scroll=ScrollView(this).apply{addView(content)}
        val dialog=androidx.appcompat.app.AlertDialog.Builder(this).setTitle(n.title.ifEmpty{"消息详情"}).setView(scroll).setNegativeButton("关闭",null)
        if(n.click.isNotBlank())dialog.setPositiveButton("打开链接"){_,_->val uri=Uri.parse(n.click);if(QiuQiuChannels.allowedUrl(this,uri)){try{startActivity(Intent(Intent.ACTION_VIEW,uri))}catch(_:Exception){Toast.makeText(this,"没有可打开此链接的应用",Toast.LENGTH_SHORT).show()}}else Toast.makeText(this,"此链接协议未允许，请在业务设置中配置",Toast.LENGTH_LONG).show()}
        dialog.show()
    }
    private fun businessPage(){
        val intro=card("业务通知设置");intro.addView(text("每种业务独立图标、声音和通知渠道。已有渠道由 Android 系统管理；发送消息不会覆盖你设置的声音、振动或重要性。",15f,muted))
        for((id,label)in QiuQiuChannels.businesses){val c=card(label);val sound=prefs.getString("sound_$id","default")?:"default";c.addView(text("默认声音配置：$sound",14f,muted));val spinner=Spinner(this);val values=listOf("default","alarm","soft","silent");spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,values);spinner.setSelection(values.indexOf(sound).coerceAtLeast(0));c.addView(spinner);button(c,"保存并打开系统渠道设置"){val selected=values[spinner.selectedItemPosition];prefs.edit().putString("sound_$id",selected).apply();val channel=QiuQiuChannels.ensure(this,id,selected,if(id in listOf("server","security","network"))4 else 3);startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName).putExtra(Settings.EXTRA_CHANNEL_ID,channel))}}
        val c=card("允许的 Deep Link 协议");val schemes=input(c,"例如 myapp,weixin",value=prefs.getString("allowed_schemes","")?:"");c.addView(text("HTTPS 默认允许。仅添加你信任的应用协议，多个协议使用英文逗号分隔。",14f,muted));button(c,"保存协议"){prefs.edit().putString("allowed_schemes",schemes.text.toString()).apply();notice("已保存")}
    }
    private fun apiPage(){
        if(session.isEmpty()){accountLogin();return}
        val c=card("自己的推送凭证")
        c.addView(text("选择接口类型，签发仅能向本机推送的密钥。完整密钥只显示一次。",15f,muted))
        val kinds=listOf("native","serverchan","sc3","pushplus","bark","wxpusher");val spinner=Spinner(this).apply{adapter=ArrayAdapter(this@QiuQiuActivity,android.R.layout.simple_spinner_dropdown_item,kinds)};c.addView(spinner)
        val label=input(c,"凭证名称")
        button(c,"为本机签发凭证"){task{val device=prefs.getString("device_id","")?:"";if(device.isEmpty())throw IllegalStateException("请先绑定设备");val targets=JsonObject().apply{add("device_ids",com.google.gson.JsonArray().apply{add(device)});add("topic_ids",com.google.gson.JsonArray())};val result=api("manage/keys","POST",JsonObject().apply{addProperty("kind",kinds[spinner.selectedItemPosition]);addProperty("label",label.text.toString());add("targets",targets)});val value=result.get("token").asString;androidx.appcompat.app.AlertDialog.Builder(this).setTitle("请保存完整密钥").setMessage(value).setPositiveButton("复制"){_,_->val clip=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager;clip.setPrimaryClip(android.content.ClipData.newPlainText("球球推送密钥",value))}.setNegativeButton("关闭",null).show()}}
        val list=card("已签发密钥")
        lifecycleScope.launch {try{for(item in api("manage/keys").getAsJsonArray("data")){val k=item.asJsonObject;list.addView(text("${k.get("label").asString} · ${k.get("kind").asString}",16f,ink,true));list.addView(text(k.get("hint").asString,14f,muted));if(k.get("disabled").asInt==0)button(list,"禁用此凭证"){task{api("manage/keys","PATCH",JsonObject().apply{addProperty("id",k.get("id").asString);addProperty("disabled",true)});render()}}}}catch(e:Exception){notice(e.message?:"加载失败")}}
        card("调用示例").addView(text("POST ${prefs.getString("push_url","")}/api/v1/push\nAuthorization: Bearer YOUR_LOCAL_TOKEN\nContent-Type: application/json\n\n{\"title\":\"任务完成\",\"body\":\"部署成功\",\"business_type\":\"task\"}\n\n兼容接口及 curl 示例见服务器管理后台。",14f))
    }
    private fun about(){val c=card("球球推送 QiuQiu Push");c.addView(text("版本 ${BuildConfig.VERSION_NAME}\n基于 ntfy Android v1.25.2\nFCM 是唯一后台推送通道。\n流体云：尚未完成 Find N5 实机验证。",15f));button(c,"查看 ntfy 开源项目与许可"){startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/binwiederhier/ntfy-android")))};val license=assets.open("qiuqiu-licenses.txt").bufferedReader().use{it.readText()};card("开源许可").addView(text(license,13f,muted))}
    private fun validBase(raw:String):String {val value=raw.trim().trimEnd('/');val uri=Uri.parse(value);require(uri.scheme=="https"&&!uri.host.isNullOrEmpty()&&uri.userInfo==null&&uri.query==null&&uri.fragment==null&&(uri.path.isNullOrEmpty()||uri.path=="/")){"服务器地址必须是 HTTPS 根地址"};return value}
    private suspend fun api(path:String,method:String="GET",payload:JsonObject?=null,auth:Boolean=true):JsonObject=withContext(Dispatchers.IO){val base=validBase(prefs.getString("push_url","")?:"");val builder=Request.Builder().url("$base/api/v1/$path");if(auth&&session.isNotEmpty())builder.header("Authorization","Bearer $session");if(method!="GET")builder.method(method,(payload?.toString()?:"{}").toRequestBody("application/json".toMediaType()));http.newCall(builder.build()).execute().use{response->val parsed=JsonParser.parseString(response.body.string()).asJsonObject;if(!response.isSuccessful)throw IllegalStateException(parsed.get("message")?.asString?:"请求失败 (${response.code})");parsed}}
}
