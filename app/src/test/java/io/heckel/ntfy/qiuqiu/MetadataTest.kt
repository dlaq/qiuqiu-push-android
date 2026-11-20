package io.heckel.ntfy.qiuqiu
import io.heckel.ntfy.msg.NotificationParser
import org.junit.Assert.*
import org.junit.Test
class MetadataTest {
 @Test fun preservesMetadataAndChinese(){
  val n=NotificationParser().parse("""{"id":"id1","event":"message","time":123,"topic":"private","sequence_id":"qq-uuid","title":"中文","message":"**正文**","content_type":"text/markdown","qiuqiu":{"business_type":"server","icon":"server","sound":"alarm","source_protocol":"bark"}}""",1,"https://ntfy.example.com")!!
  assertEquals("中文",n.title);assertEquals("qq-uuid",n.sequenceId);assertEquals("server",QiuQiuChannels.meta(n,"business_type"));assertEquals("alarm",QiuQiuChannels.meta(n,"sound"));assertEquals("bark",QiuQiuChannels.meta(n,"source_protocol"))
 }
 @Test fun ordinaryNtfyStillParses(){val n=NotificationParser().parse("""{"id":"id2","event":"message","time":123,"topic":"a","message":"plain"}""")!!;assertNull(n.qiuqiu);assertEquals("plain",n.message)}
 @Test fun ignoresKeepalive(){assertNull(NotificationParser().parse("""{"id":"id3","event":"keepalive","time":123,"topic":"a"}"""))}
}
