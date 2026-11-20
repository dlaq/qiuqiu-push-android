package io.heckel.ntfy.qiuqiu

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.msg.NotificationService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationTest {
    private val ctx:Context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun subscription()=Subscription(987654,"https://ntfy.example.com","instrumentation",false,0,0,0,0,null,null,null,null,"Test",false)
    private fun notification(id:String="test-msg",sound:String="alarm")=Notification(id,987654,1795107600,"qq-sequence","测试通知","中文正文","","",12345,4,"","https://example.com",null,null,null,false,qiuqiu="""{"message_id":"qq-test","business_type":"server","icon":"server","sound":"$sound","source_protocol":"native"}""")
    @Test fun businessIconSoundAndVisibleNotification(){
        val manager=ctx.getSystemService(NotificationManager::class.java)
        val n=notification()
        NotificationService(ctx).display(subscription(),n)
        val shown=manager.activeNotifications.firstOrNull {it.id==n.notificationId}
        assertNotNull(shown)
        assertEquals(R.drawable.ic_server,shown!!.notification.smallIcon.resId)
        val channel=manager.getNotificationChannel(shown.notification.channelId)
        assertTrue(channel.sound.toString().contains("/${R.raw.qiuqiu_alarm}"))
        assertEquals(NotificationManager.IMPORTANCE_HIGH,channel.importance)
        manager.cancel(n.notificationId)
    }
    @Test fun existingChannelSettingsArePreserved(){
        val manager=ctx.getSystemService(NotificationManager::class.java)
        val id="network_alert_soft_normal"
        val c=android.app.NotificationChannel(id,"既有渠道设置",NotificationManager.IMPORTANCE_LOW)
        c.setSound(null,null);manager.createNotificationChannel(c)
        QiuQiuChannels.ensure(ctx,"network","soft",3)
        assertNull(manager.getNotificationChannel(id).sound)
        assertEquals(NotificationManager.IMPORTANCE_LOW,manager.getNotificationChannel(id).importance)
    }
    @Test fun repeatedDeliveryDoesNotDuplicateHistory()=runBlocking {
        val repository=Repository.getInstance(ctx)
        val sub=subscription();repository.addSubscription(sub)
        val n=notification("dedup-one")
        assertTrue(repository.addNotification(n))
        assertFalse(repository.addNotification(n.copy(id="dedup-two")))
        val update=n.copy(id="dedup-three",qiuqiu="""{"message_id":"updated","business_type":"server"}""")
        assertTrue(repository.addNotification(update))
        repository.removeSubscription(sub)
    }
    @Test fun safeLinksAndKeystoreWrapping(){
        assertTrue(QiuQiuChannels.allowedUrl(ctx,Uri.parse("https://example.com")))
        assertFalse(QiuQiuChannels.allowedUrl(ctx,Uri.parse("javascript:alert(1)")))
        assertFalse(QiuQiuChannels.allowedUrl(ctx,Uri.parse("file:///private")))
        QiuQiuConfig.prefs(ctx).edit().putString("allowed_schemes","myapp").commit()
        assertTrue(QiuQiuChannels.allowedUrl(ctx,Uri.parse("myapp://open")))
        val secret="isolated-test-credential";val wrapped=SecretBox.encrypt(secret)
        assertTrue(wrapped.startsWith("qqenc1:"));assertFalse(wrapped.contains(secret));assertEquals(secret,SecretBox.decrypt(wrapped))
    }
}
