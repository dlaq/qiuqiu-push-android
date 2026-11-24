package io.heckel.ntfy.firebase

import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Attachment
import io.heckel.ntfy.db.Icon
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.NotificationDispatcher
import io.heckel.ntfy.msg.NotificationParser
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.service.SubscriberService
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.deriveNotificationId
import io.heckel.ntfy.util.nullIfZero
import io.heckel.ntfy.util.toPriority
import io.heckel.ntfy.util.topicShortUrl
import io.heckel.ntfy.work.PollWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FirebaseService : FirebaseMessagingService() {
    private val repository by lazy { (application as Application).repository }
    private val dispatcher by lazy { NotificationDispatcher(this, repository) }
    private val job = SupervisorJob()
    private val messenger = FirebaseMessenger()
    private val parser = NotificationParser()

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Init log (this is done in all entrypoints)
        Log.init(this)

        // We only process data messages
        if (remoteMessage.data.isEmpty()) {
            Log.d(TAG, "Discarding unexpected message (1): from=${remoteMessage.from}")
            return
        }

        // Dispatch event
        //
        // This logic is (partially) duplicated in
        // - Android: SubscriberService::onNotificationReceived()
        // - Android: FirebaseService::onMessageReceived()
        // - Web app: hooks.js:handleNotification()
        // - Web app: sw.js:handleMessage(), sw.js:handleMessageClear(), ...
        val data = remoteMessage.data
        when (data["event"]) {
            ApiService.EVENT_MESSAGE -> handleMessage(remoteMessage)
            ApiService.EVENT_MESSAGE_DELETE -> handleMessageDelete(remoteMessage)
            ApiService.EVENT_MESSAGE_CLEAR -> handleMessageClear(remoteMessage)
            ApiService.EVENT_KEEPALIVE -> handleKeepalive(remoteMessage)
            ApiService.EVENT_POLL_REQUEST -> handlePollRequest(remoteMessage)
            else -> Log.d(TAG, "Discarding unexpected message (2): from=${remoteMessage.from}")
        }
    }

    private fun handleKeepalive(remoteMessage: RemoteMessage) {
        // QiuQiu never uses silent FCM as a periodic keepalive trigger.
    }

    private fun handlePollRequest(remoteMessage: RemoteMessage) {
        val baseUrl = io.heckel.ntfy.qiuqiu.QiuQiuConfig.server(this@FirebaseService) // Everything from Firebase comes from main service URL!
        val topic = remoteMessage.data["topic"] ?: return
        io.heckel.ntfy.qiuqiu.Receipts.flush(this)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workName = "${PollWorker.WORK_NAME_ONCE_SINGE_PREFIX}_${baseUrl}_${topic}"
        val workManager = WorkManager.getInstance(this)
        val requestBuilder = OneTimeWorkRequest.Builder(PollWorker::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 31 && remoteMessage.priority == RemoteMessage.PRIORITY_HIGH) {
            requestBuilder.setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        val workRequest = requestBuilder
            .setInputData(workDataOf(
                PollWorker.INPUT_DATA_BASE_URL to baseUrl,
                PollWorker.INPUT_DATA_TOPIC to topic
            ))
            .setConstraints(constraints)
            .build()
        Log.d(TAG, "Poll request for ${topicShortUrl(baseUrl, topic)} received, scheduling unique poll worker with name $workName")

        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)
    }

    private fun handleMessageDelete(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val id = data["id"] ?: return
        val topic = data["topic"] ?: return
        val sequenceId = data["sequence_id"] ?: return
        Log.d(TAG, "Received message_delete: from=${remoteMessage.from}, id=$id, topic=$topic, sequenceId=$sequenceId")

        CoroutineScope(job).launch {
            val baseUrl = io.heckel.ntfy.qiuqiu.QiuQiuConfig.server(this@FirebaseService)
            val subscription = repository.getSubscription(baseUrl, topic) ?: return@launch

            // Mark all notifications with this sequenceId as deleted
            repository.markAsDeletedBySequenceId(subscription.id, sequenceId)

            // Update lastNotificationId to track message stream position
            repository.updateLastNotificationId(subscription.id, id)

            // Cancel the Android notification (scoped by baseUrl/topic/sequenceId)
            val notificationId = deriveNotificationId(baseUrl, topic, sequenceId)
            val notifier = NotificationService(this@FirebaseService)
            notifier.cancel(notificationId)
        }
    }

    private fun handleMessageClear(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val id = data["id"] ?: return
        val topic = data["topic"] ?: return
        val sequenceId = data["sequence_id"] ?: return
        Log.d(TAG, "Received message_clear: from=${remoteMessage.from}, id=$id, topic=$topic, sequenceId=$sequenceId")

        CoroutineScope(job).launch {
            val baseUrl = io.heckel.ntfy.qiuqiu.QiuQiuConfig.server(this@FirebaseService)
            val subscription = repository.getSubscription(baseUrl, topic) ?: return@launch

            // Mark all notifications with this sequenceId as read
            repository.markAsReadBySequenceId(subscription.id, sequenceId)

            // Update lastNotificationId to track message stream position
            repository.updateLastNotificationId(subscription.id, id)

            // Cancel the Android notification (scoped by baseUrl/topic/sequenceId)
            val notificationId = deriveNotificationId(baseUrl, topic, sequenceId)
            val notifier = NotificationService(this@FirebaseService)
            notifier.cancel(notificationId)
        }
    }

    private fun handleMessage(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val id = data["id"]
        val timestamp = data["time"]?.toLongOrNull()
        val topic = data["topic"]
        val title = data["title"]
        val message = data["message"]
        val priority = data["priority"]?.toIntOrNull()
        val tags = data["tags"]
        val click = data["click"]
        val iconUrl = data["icon"]
        val actions = data["actions"] // JSON array as string, sigh ...
        val contentType = data["content_type"]
        val encoding = data["encoding"]
        val attachmentName = data["attachment_name"] ?: "attachment.bin"
        val attachmentType = data["attachment_type"]
        val attachmentSize = data["attachment_size"]?.toLongOrNull()?.nullIfZero()
        val attachmentExpires = data["attachment_expires"]?.toLongOrNull()?.nullIfZero()
        val attachmentUrl = data["attachment_url"]
        val sequenceId = data["sequence_id"]
        val truncated = (data["truncated"] ?: "") == "1"
        if (id == null || topic == null || message == null || timestamp == null) {
            Log.d(TAG, "Discarding unexpected message: from=${remoteMessage.from}, fcmprio=${remoteMessage.priority}, fcmprio_orig=${remoteMessage.originalPriority}")
            return
        }
        Log.d(TAG, "Received message: from=${remoteMessage.from}, fcmprio=${remoteMessage.priority}, fcmprio_orig=${remoteMessage.originalPriority}")

        CoroutineScope(job).launch {
            val baseUrl = io.heckel.ntfy.qiuqiu.QiuQiuConfig.server(this@FirebaseService) // Everything from Firebase comes from main service URL!

            // Check if notification was truncated and discard if it will (or likely already did) arrive via instant delivery
            val subscription = repository.getSubscription(baseUrl, topic) ?: return@launch
            if (truncated && subscription.instant) {
                Log.d(TAG, "Discarding truncated message that did/will arrive via instant delivery: from=${remoteMessage.from}, fcmprio=${remoteMessage.priority}, fcmprio_orig=${remoteMessage.originalPriority}")
                return@launch
            }

            // Add notification
            val attachment = if (attachmentUrl != null) {
                Attachment(
                    name = attachmentName,
                    type = attachmentType,
                    size = attachmentSize,
                    expires = attachmentExpires,
                    url = attachmentUrl,
                )
            } else null
            val icon: Icon? = if (iconUrl != null && iconUrl != "") Icon(url = iconUrl) else null
            val actualSequenceId = sequenceId ?: id
            val notification = Notification(
                id = id,
                subscriptionId = subscription.id,
                timestamp = timestamp,
                sequenceId = actualSequenceId,
                title = title ?: "",
                message = message,
                contentType = contentType ?: "",
                encoding = encoding ?: "",
                priority = toPriority(priority),
                tags = tags ?: "",
                click = click ?: "",
                icon = icon,
                actions = parser.parseActions(actions),
                attachment = attachment,
                notificationId = deriveNotificationId(baseUrl, topic, actualSequenceId),
                deleted = false,
                qiuqiu = data["qiuqiu"],
                event = ApiService.EVENT_MESSAGE
            )

            val added = repository.addNotification(notification)
            if (added) {
                Log.d(TAG, "Dispatching notification: from=${remoteMessage.from}, fcmprio=${remoteMessage.priority}, fcmprio_orig=${remoteMessage.originalPriority}")
                dispatcher.dispatch(subscription, notification)
            } else {
                io.heckel.ntfy.qiuqiu.Receipts.record(this@FirebaseService,notification,"received")
            }
        }
    }

    override fun onNewToken(token: String) {
        // Called if the FCM registration token is updated
        // We don't actually use or care about the token, since we're using topics
        Log.d(TAG, "Registration token was updated")
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    companion object {
        private const val TAG = "NtfyFirebase"
    }
}
