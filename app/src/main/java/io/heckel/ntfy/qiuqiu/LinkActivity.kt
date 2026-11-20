package io.heckel.ntfy.qiuqiu
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
/** An internal trampoline gives ACTION_VIEW a safe fallback if the destination app is absent. */
class LinkActivity:Activity(){override fun onCreate(state:Bundle?){super.onCreate(state);val uri=Uri.parse(intent.getStringExtra("url")?:"");if(QiuQiuChannels.allowedUrl(this,uri)){try{startActivity(Intent(Intent.ACTION_VIEW,uri));finish();return}catch(_:Exception){}}
startActivity(Intent(this,QiuQiuActivity::class.java).putExtra("show_history",true));Toast.makeText(this,"链接不可用，已打开消息历史",Toast.LENGTH_SHORT).show();finish()}}
