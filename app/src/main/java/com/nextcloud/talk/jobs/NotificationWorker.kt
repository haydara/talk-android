/*
 * Nextcloud Talk application
 *
 * @author Andy Scherzinger
 * @author Mario Danic
 * @author Marcel Hibbe
 * Copyright (C) 2022 Marcel Hibbe <dev@mhibbe.de>
 * Copyright (C) 2022 Andy Scherzinger <info@andy-scherzinger.de>
 * Copyright (C) 2017-2018 Mario Danic <mario@lovelyhq.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.nextcloud.talk.jobs

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.emoji.text.EmojiCompat
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import autodagger.AutoInjector
import com.bluelinelabs.logansquare.LoganSquare
import com.nextcloud.talk.R
import com.nextcloud.talk.activities.CallNotificationActivity
import com.nextcloud.talk.activities.MainActivity
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.arbitrarystorage.ArbitraryStorageManager
import com.nextcloud.talk.models.SignatureVerification
import com.nextcloud.talk.models.json.chat.ChatUtils.Companion.getParsedMessage
import com.nextcloud.talk.models.json.conversations.RoomOverall
import com.nextcloud.talk.models.json.notifications.NotificationOverall
import com.nextcloud.talk.models.json.participants.Participant
import com.nextcloud.talk.models.json.participants.ParticipantsOverall
import com.nextcloud.talk.models.json.push.DecryptedPushMessage
import com.nextcloud.talk.models.json.push.NotificationUser
import com.nextcloud.talk.receivers.DirectReplyReceiver
import com.nextcloud.talk.receivers.MarkAsReadReceiver
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.DoNotDisturbUtils.shouldPlaySound
import com.nextcloud.talk.utils.NotificationUtils
import com.nextcloud.talk.utils.NotificationUtils.cancelAllNotificationsForAccount
import com.nextcloud.talk.utils.NotificationUtils.cancelNotification
import com.nextcloud.talk.utils.NotificationUtils.findNotificationForRoom
import com.nextcloud.talk.utils.NotificationUtils.getCallRingtoneUri
import com.nextcloud.talk.utils.NotificationUtils.getMessageRingtoneUri
import com.nextcloud.talk.utils.NotificationUtils.loadAvatarSync
import com.nextcloud.talk.utils.PushUtils
import com.nextcloud.talk.utils.bundle.BundleKeys
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_FROM_NOTIFICATION_START_CALL
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_INTERNAL_USER_ID
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_MESSAGE_ID
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_NOTIFICATION_ID
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_ROOM_TOKEN
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_SYSTEM_NOTIFICATION_ID
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_USER_ENTITY
import com.nextcloud.talk.utils.preferences.AppPreferences
import com.nextcloud.talk.utils.singletons.ApplicationWideCurrentRoomHolder
import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.IOException
import java.net.CookieManager
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.NoSuchPaddingException
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class NotificationWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    @Inject
    lateinit var appPreferences: AppPreferences

    @JvmField
    @Inject
    var arbitraryStorageManager: ArbitraryStorageManager? = null

    @JvmField
    @Inject
    var retrofit: Retrofit? = null

    @JvmField
    @Inject
    var okHttpClient: OkHttpClient? = null
    private lateinit var credentials: String
    private lateinit var ncApi: NcApi
    private lateinit var pushMessage: DecryptedPushMessage
    private lateinit var signatureVerification: SignatureVerification
    private var context: Context? = null
    private var conversationType: String? = "one2one"
    private var muteCall = false
    private var importantConversation = false
    private lateinit var notificationManager: NotificationManagerCompat

    override fun doWork(): Result {
        sharedApplication!!.componentApplication.inject(this)
        context = applicationContext

        initDecryptedData(inputData)
        initNcApiAndCredentials()

        notificationManager = NotificationManagerCompat.from(context!!)

        pushMessage.timestamp = System.currentTimeMillis()

        Log.d(TAG, pushMessage.toString())
        Log.d(TAG, "pushMessage.id (=KEY_ROOM_TOKEN): " + pushMessage.id)
        Log.d(TAG, "pushMessage.notificationId: " + pushMessage.notificationId)
        Log.d(TAG, "pushMessage.notificationIds: " + pushMessage.notificationIds)
        Log.d(TAG, "pushMessage.timestamp: " + pushMessage.timestamp)

        if (pushMessage.delete) {
            cancelNotification(context, signatureVerification.user!!, pushMessage.notificationId)
        } else if (pushMessage.deleteAll) {
            cancelAllNotificationsForAccount(context, signatureVerification.user!!)
        } else if (pushMessage.deleteMultiple) {
            for (notificationId in pushMessage.notificationIds!!) {
                cancelNotification(context, signatureVerification.user!!, notificationId)
            }
        } else if (isSpreedNotification()) {
            Log.d(TAG, "pushMessage.type: " + pushMessage.type)
            when (pushMessage.type) {
                "chat" -> handleChatNotification()
                "room" -> handleRoomNotification()
                "call" -> handleCallNotification()
                else -> Log.e(TAG, "unknown pushMessage.type")
            }
        } else {
            Log.d(TAG, "a pushMessage that is not for spreed was received.")
        }

        return Result.success()
    }

    private fun handleChatNotification() {
        val chatIntent = Intent(context, MainActivity::class.java)
        chatIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        val chatBundle = Bundle()
        chatBundle.putString(KEY_ROOM_TOKEN, pushMessage.id)
        chatBundle.putParcelable(KEY_USER_ENTITY, signatureVerification.user)
        chatBundle.putBoolean(KEY_FROM_NOTIFICATION_START_CALL, false)
        chatIntent.putExtras(chatBundle)
        if (pushMessage.notificationId != Long.MIN_VALUE) {
            showNotificationWithObjectData(chatIntent)
        } else {
            showNotification(chatIntent)
        }
    }

    /**
     * handle messages with type 'room', e.g. "xxx invited you to a group conversation"
     */
    private fun handleRoomNotification() {
        val intent = Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        val bundle = Bundle()
        bundle.putString(KEY_ROOM_TOKEN, pushMessage.id)
        bundle.putParcelable(KEY_USER_ENTITY, signatureVerification.user)
        bundle.putBoolean(KEY_FROM_NOTIFICATION_START_CALL, false)
        intent.putExtras(bundle)
        if (bundle.containsKey(KEY_ROOM_TOKEN)) {
            showNotificationWithObjectData(intent)
        }
    }

    private fun handleCallNotification() {
        val fullScreenIntent = Intent(context, CallNotificationActivity::class.java)
        val bundle = Bundle()
        bundle.putString(KEY_ROOM_TOKEN, pushMessage.id)
        bundle.putParcelable(KEY_USER_ENTITY, signatureVerification.user)
        bundle.putBoolean(KEY_FROM_NOTIFICATION_START_CALL, true)
        fullScreenIntent.putExtras(bundle)
        fullScreenIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK

        val requestCode = System.currentTimeMillis().toInt()

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            fullScreenIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val soundUri = getCallRingtoneUri(applicationContext, appPreferences)
        val notificationChannelId = NotificationUtils
            .NotificationChannels.NOTIFICATION_CHANNEL_CALLS_V4.name
        val uri = Uri.parse(signatureVerification.user!!.baseUrl)
        val baseUrl = uri.host

        val notification =
            NotificationCompat.Builder(applicationContext, notificationChannelId)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setSmallIcon(R.drawable.ic_call_black_24dp)
                .setSubText(baseUrl)
                .setShowWhen(true)
                .setWhen(pushMessage.timestamp)
                .setContentTitle(EmojiCompat.get().process(pushMessage.subject))
                .setAutoCancel(true)
                .setOngoing(true)
                .setContentIntent(fullScreenPendingIntent)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setSound(soundUri)
                .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT

        sendNotification(pushMessage.timestamp.toInt(), notification)

        checkIfCallIsActive(signatureVerification, pushMessage)
    }

    private fun initNcApiAndCredentials() {
        credentials = ApiUtils.getCredentials(
            signatureVerification.user!!.username,
            signatureVerification.user!!.token
        )
        ncApi = retrofit!!.newBuilder().client(
            okHttpClient!!.newBuilder().cookieJar(
                JavaNetCookieJar(
                    CookieManager()
                )
            ).build()
        ).build().create(
            NcApi::class.java
        )
    }

    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth", "ComplexMethod", "LongMethod")
    private fun initDecryptedData(inputData: Data) {
        val subject = inputData.getString(BundleKeys.KEY_NOTIFICATION_SUBJECT)
        val signature = inputData.getString(BundleKeys.KEY_NOTIFICATION_SIGNATURE)
        try {
            val base64DecodedSubject = Base64.decode(subject, Base64.DEFAULT)
            val base64DecodedSignature = Base64.decode(signature, Base64.DEFAULT)
            val pushUtils = PushUtils()
            val privateKey = pushUtils.readKeyFromFile(false) as PrivateKey
            try {
                signatureVerification = pushUtils.verifySignature(
                    base64DecodedSignature,
                    base64DecodedSubject
                )
                if (signatureVerification.signatureValid) {
                    val cipher = Cipher.getInstance("RSA/None/PKCS1Padding")
                    cipher.init(Cipher.DECRYPT_MODE, privateKey)
                    val decryptedSubject = cipher.doFinal(base64DecodedSubject)

                    pushMessage = LoganSquare.parse(
                        String(decryptedSubject),
                        DecryptedPushMessage::class.java
                    )
                }
            } catch (e: NoSuchAlgorithmException) {
                Log.e(TAG, "No proper algorithm to decrypt the message ", e)
            } catch (e: NoSuchPaddingException) {
                Log.e(TAG, "No proper padding to decrypt the message ", e)
            } catch (e: InvalidKeyException) {
                Log.e(TAG, "Invalid private key ", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error occurred while initializing decoded data ", e)
        }
    }

    private fun isSpreedNotification() = SPREED_APP == pushMessage.app

    private fun showNotificationWithObjectData(intent: Intent) {
        val user = signatureVerification.user

        // see https://github.com/nextcloud/notifications/blob/master/docs/ocs-endpoint-v2.md
        ncApi.getNotification(
            credentials,
            ApiUtils.getUrlForNotificationWithId(
                user!!.baseUrl,
                (pushMessage.notificationId!!).toString()
            )
        )
            .blockingSubscribe(object : Observer<NotificationOverall> {
                override fun onSubscribe(d: Disposable) {
                    // unused atm
                }

                override fun onNext(notificationOverall: NotificationOverall) {
                    val ncNotification = notificationOverall.ocs!!.notification

                    if (ncNotification!!.messageRichParameters != null &&
                        ncNotification.messageRichParameters!!.size > 0
                    ) {
                        pushMessage.text = getParsedMessage(
                            ncNotification.messageRich,
                            ncNotification.messageRichParameters
                        )
                    } else {
                        pushMessage.text = ncNotification.message
                    }

                    val subjectRichParameters = ncNotification.subjectRichParameters

                    pushMessage.timestamp = ncNotification.datetime!!.millis

                    if (subjectRichParameters != null && subjectRichParameters.size > 0) {
                        val callHashMap = subjectRichParameters["call"]
                        val userHashMap = subjectRichParameters["user"]
                        val guestHashMap = subjectRichParameters["guest"]
                        if (callHashMap != null && callHashMap.size > 0 && callHashMap.containsKey("name")) {
                            if (subjectRichParameters.containsKey("reaction")) {
                                pushMessage.subject = ""
                                pushMessage.text = ncNotification.subject
                            } else if (ncNotification.objectType == "chat") {
                                pushMessage.subject = callHashMap["name"]!!
                            } else {
                                pushMessage.subject = ncNotification.subject!!
                            }
                            if (callHashMap.containsKey("call-type")) {
                                conversationType = callHashMap["call-type"]
                            }
                        }
                        val notificationUser = NotificationUser()
                        if (userHashMap != null && userHashMap.isNotEmpty()) {
                            notificationUser.id = userHashMap["id"]
                            notificationUser.type = userHashMap["type"]
                            notificationUser.name = userHashMap["name"]
                            pushMessage.notificationUser = notificationUser
                        } else if (guestHashMap != null && guestHashMap.isNotEmpty()) {
                            notificationUser.id = guestHashMap["id"]
                            notificationUser.type = guestHashMap["type"]
                            notificationUser.name = guestHashMap["name"]
                            pushMessage.notificationUser = notificationUser
                        }
                    }
                    pushMessage.objectId = ncNotification.objectId
                    showNotification(intent)
                }

                override fun onError(e: Throwable) {
                    // unused atm
                }

                override fun onComplete() {
                    // unused atm
                }
            })
    }

    @Suppress("MagicNumber")
    private fun showNotification(intent: Intent) {
        val largeIcon: Bitmap
        val priority = NotificationCompat.PRIORITY_HIGH
        val smallIcon: Int = R.drawable.ic_logo
        val category: String = if (CHAT == pushMessage.type || ROOM == pushMessage.type) {
            Notification.CATEGORY_MESSAGE
        } else {
            Notification.CATEGORY_CALL
        }
        when (conversationType) {
            "one2one" -> {
                pushMessage.subject = ""
                largeIcon = ContextCompat.getDrawable(context!!, R.drawable.ic_people_group_black_24px)?.toBitmap()!!
            }
            "group" ->
                largeIcon = ContextCompat.getDrawable(context!!, R.drawable.ic_people_group_black_24px)?.toBitmap()!!
            "public" -> largeIcon = ContextCompat.getDrawable(context!!, R.drawable.ic_link_black_24px)?.toBitmap()!!
            else -> // assuming one2one
                largeIcon = if (CHAT == pushMessage.type || ROOM == pushMessage.type) {
                    ContextCompat.getDrawable(context!!, R.drawable.ic_comment)?.toBitmap()!!
                } else {
                    ContextCompat.getDrawable(context!!, R.drawable.ic_call_black_24dp)?.toBitmap()!!
                }
        }

        // Use unique request code to make sure that a new PendingIntent gets created for each notification
        // See https://github.com/nextcloud/talk-android/issues/2111
        val requestCode = System.currentTimeMillis().toInt()
        val intentFlag: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getActivity(context, requestCode, intent, intentFlag)
        val uri = Uri.parse(signatureVerification.user!!.baseUrl)
        val baseUrl = uri.host
        val notificationBuilder = NotificationCompat.Builder(context!!, "1")
            .setLargeIcon(largeIcon)
            .setSmallIcon(smallIcon)
            .setCategory(category)
            .setPriority(priority)
            .setSubText(baseUrl)
            .setWhen(pushMessage.timestamp)
            .setShowWhen(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        if (!TextUtils.isEmpty(pushMessage.subject)) {
            notificationBuilder.setContentTitle(
                EmojiCompat.get().process(pushMessage.subject)
            )
        }
        if (!TextUtils.isEmpty(pushMessage.text)) {
            notificationBuilder.setContentText(
                EmojiCompat.get().process(pushMessage.text!!)
            )
        }
        if (Build.VERSION.SDK_INT >= 23) {
            // This method should exist since API 21, but some phones don't have it
            // So as a safeguard, we don't use it until 23
            notificationBuilder.color = context!!.resources.getColor(R.color.colorPrimary)
        }
        val notificationInfoBundle = Bundle()
        notificationInfoBundle.putLong(KEY_INTERNAL_USER_ID, signatureVerification.user!!.id!!)
        // could be an ID or a TOKEN
        notificationInfoBundle.putString(KEY_ROOM_TOKEN, pushMessage.id)
        notificationInfoBundle.putLong(KEY_NOTIFICATION_ID, pushMessage.notificationId!!)
        notificationBuilder.setExtras(notificationInfoBundle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (CHAT == pushMessage.type || ROOM == pushMessage.type) {
                notificationBuilder.setChannelId(
                    NotificationUtils.NotificationChannels.NOTIFICATION_CHANNEL_MESSAGES_V4.name
                )
            }
        } else {
            // red color for the lights
            notificationBuilder.setLights(-0x10000, 200, 200)
        }

        notificationBuilder.setContentIntent(pendingIntent)
        val groupName = signatureVerification.user!!.id.toString() + "@" + pushMessage.id
        notificationBuilder.setGroup(calculateCRC32(groupName).toString())
        val activeStatusBarNotification = findNotificationForRoom(
            context,
            signatureVerification.user!!,
            pushMessage.id!!
        )

        // NOTE - systemNotificationId is an internal ID used on the device only.
        // It is NOT the same as the notification ID used in communication with the server.
        val systemNotificationId: Int =
            activeStatusBarNotification?.id ?: calculateCRC32(System.currentTimeMillis().toString()).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && CHAT == pushMessage.type &&
            pushMessage.notificationUser != null
        ) {
            prepareChatNotification(notificationBuilder, activeStatusBarNotification, systemNotificationId)
        }
        sendNotification(systemNotificationId, notificationBuilder.build())
    }

    private fun calculateCRC32(s: String): Long {
        val crc32 = CRC32()
        crc32.update(s.toByteArray())
        return crc32.value
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private fun prepareChatNotification(
        notificationBuilder: NotificationCompat.Builder,
        activeStatusBarNotification: StatusBarNotification?,
        systemNotificationId: Int
    ) {
        val notificationUser = pushMessage.notificationUser
        val userType = notificationUser!!.type
        var style: NotificationCompat.MessagingStyle? = null
        if (activeStatusBarNotification != null) {
            style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                activeStatusBarNotification.notification
            )
        }
        val person = Person.Builder()
            .setKey(signatureVerification.user!!.id.toString() + "@" + notificationUser.id)
            .setName(EmojiCompat.get().process(notificationUser.name!!))
            .setBot("bot" == userType)
        notificationBuilder.setOnlyAlertOnce(true)
        addReplyAction(notificationBuilder, systemNotificationId)
        addMarkAsReadAction(notificationBuilder, systemNotificationId)

        if ("user" == userType || "guest" == userType) {
            val baseUrl = signatureVerification.user!!.baseUrl
            val avatarUrl = if ("user" == userType) ApiUtils.getUrlForAvatar(
                baseUrl,
                notificationUser.id,
                false
            ) else ApiUtils.getUrlForGuestAvatar(baseUrl, notificationUser.name, false)
            person.setIcon(loadAvatarSync(avatarUrl))
        }
        notificationBuilder.setStyle(getStyle(person.build(), style))
    }

    private fun buildIntentForAction(cls: Class<*>, systemNotificationId: Int, messageId: Int): PendingIntent {
        val actualIntent = Intent(context, cls)

        // NOTE - systemNotificationId is an internal ID used on the device only.
        // It is NOT the same as the notification ID used in communication with the server.
        actualIntent.putExtra(KEY_SYSTEM_NOTIFICATION_ID, systemNotificationId)
        actualIntent.putExtra(KEY_INTERNAL_USER_ID, signatureVerification.user?.id)
        actualIntent.putExtra(KEY_ROOM_TOKEN, pushMessage.id)
        actualIntent.putExtra(KEY_MESSAGE_ID, messageId)

        val intentFlag: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, systemNotificationId, actualIntent, intentFlag)
    }

    private fun addMarkAsReadAction(notificationBuilder: NotificationCompat.Builder, systemNotificationId: Int) {
        if (pushMessage.objectId != null) {
            val messageId: Int = try {
                parseMessageId(pushMessage.objectId!!)
            } catch (nfe: NumberFormatException) {
                Log.e(TAG, "Failed to parse messageId from objectId, skip adding mark-as-read action.", nfe)
                return
            }

            val pendingIntent = buildIntentForAction(
                MarkAsReadReceiver::class.java,
                systemNotificationId,
                messageId
            )
            val action = NotificationCompat.Action.Builder(
                R.drawable.ic_eye,
                context!!.resources.getString(R.string.nc_mark_as_read),
                pendingIntent
            )
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build()
            notificationBuilder.addAction(action)
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private fun addReplyAction(notificationBuilder: NotificationCompat.Builder, systemNotificationId: Int) {
        val replyLabel = context!!.resources.getString(R.string.nc_reply)
        val remoteInput = RemoteInput.Builder(NotificationUtils.KEY_DIRECT_REPLY)
            .setLabel(replyLabel)
            .build()

        val replyPendingIntent = buildIntentForAction(DirectReplyReceiver::class.java, systemNotificationId, 0)
        val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_reply, replyLabel, replyPendingIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .addRemoteInput(remoteInput)
            .build()
        notificationBuilder.addAction(replyAction)
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private fun getStyle(person: Person, style: NotificationCompat.MessagingStyle?): NotificationCompat.MessagingStyle {
        val newStyle = NotificationCompat.MessagingStyle(person)
        newStyle.conversationTitle = pushMessage.subject
        newStyle.isGroupConversation = "one2one" != conversationType
        style?.messages?.forEach(
            Consumer { message: NotificationCompat.MessagingStyle.Message ->
                newStyle.addMessage(
                    NotificationCompat.MessagingStyle.Message(
                        message.text,
                        message.timestamp,
                        message.person
                    )
                )
            }
        )
        newStyle.addMessage(pushMessage.text, pushMessage.timestamp, person)
        return newStyle
    }

    @Throws(NumberFormatException::class)
    private fun parseMessageId(objectId: String): Int {
        val objectIdParts = objectId.split("/".toRegex()).toTypedArray()
        return if (objectIdParts.size < 2) {
            throw NumberFormatException("Invalid objectId, doesn't contain at least one '/'")
        } else {
            objectIdParts[1].toInt()
        }
    }

    private fun sendNotification(notificationId: Int, notification: Notification) {
        Log.d(TAG, "show notification with id $notificationId")
        notificationManager.notify(notificationId, notification)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // On devices with Android 8.0 (Oreo) or later, notification sound will be handled by the system
            // if notifications have not been disabled by the user.
            return
        }
        if (Notification.CATEGORY_CALL != notification.category || !muteCall) {
            val soundUri = getMessageRingtoneUri(context!!, appPreferences)
            if (soundUri != null && !ApplicationWideCurrentRoomHolder.getInstance().isInCall &&
                (shouldPlaySound() || importantConversation)
            ) {
                val audioAttributesBuilder =
                    AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                if (CHAT == pushMessage.type || ROOM == pushMessage.type) {
                    audioAttributesBuilder.setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                } else {
                    audioAttributesBuilder.setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST)
                }
                val mediaPlayer = MediaPlayer()
                try {
                    mediaPlayer.setDataSource(context!!, soundUri)
                    mediaPlayer.setAudioAttributes(audioAttributesBuilder.build())
                    mediaPlayer.setOnPreparedListener { mediaPlayer.start() }
                    mediaPlayer.setOnCompletionListener { obj: MediaPlayer -> obj.release() }
                    mediaPlayer.prepareAsync()
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to set data source")
                }
            }
        }
    }

    private fun removeNotification(notificationId: Int) {
        Log.d(TAG, "removed notification with id $notificationId")
        notificationManager.cancel(notificationId)
    }

    private fun checkIfCallIsActive(
        signatureVerification: SignatureVerification,
        decryptedPushMessage: DecryptedPushMessage
    ) {
        Log.d(TAG, "checkIfCallIsActive")
        var hasParticipantsInCall = true
        var inCallOnDifferentDevice = false

        val apiVersion = ApiUtils.getConversationApiVersion(
            signatureVerification.user,
            intArrayOf(ApiUtils.APIv4, 1)
        )

        var isCallNotificationVisible = true

        ncApi.getPeersForCall(
            credentials,
            ApiUtils.getUrlForCall(
                apiVersion,
                signatureVerification.user!!.baseUrl,
                decryptedPushMessage.id
            )
        )
            .repeatWhen { completed ->
                completed.zipWith(Observable.range(TIMER_START, TIMER_COUNT)) { _, i -> i }
                    .flatMap { Observable.timer(TIMER_DELAY, TimeUnit.SECONDS) }
                    .takeWhile { isCallNotificationVisible && hasParticipantsInCall && !inCallOnDifferentDevice }
            }
            .subscribeOn(Schedulers.io())
            .subscribe(object : Observer<ParticipantsOverall> {
                override fun onSubscribe(d: Disposable) = Unit

                @RequiresApi(Build.VERSION_CODES.M)
                override fun onNext(participantsOverall: ParticipantsOverall) {
                    val participantList: List<Participant> = participantsOverall.ocs!!.data!!
                    hasParticipantsInCall = participantList.isNotEmpty()
                    if (hasParticipantsInCall) {
                        for (participant in participantList) {
                            if (participant.actorId == signatureVerification.user!!.userId &&
                                participant.actorType == Participant.ActorType.USERS
                            ) {
                                inCallOnDifferentDevice = true
                                break
                            }
                        }
                    }
                    if (inCallOnDifferentDevice) {
                        Log.d(TAG, "inCallOnDifferentDevice is true")
                        removeNotification(decryptedPushMessage.timestamp.toInt())
                    }

                    if (!hasParticipantsInCall) {
                        showMissedCallNotification()
                        Log.d(TAG, "no participants in call")
                        removeNotification(decryptedPushMessage.timestamp.toInt())
                    }

                    isCallNotificationVisible = isCallNotificationVisible(decryptedPushMessage)
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "Error in getPeersForCall", e)
                }

                @RequiresApi(Build.VERSION_CODES.M)
                override fun onComplete() {

                    if (isCallNotificationVisible) {
                        // this state can be reached when call timeout is reached.
                        showMissedCallNotification()
                    }

                    removeNotification(decryptedPushMessage.timestamp.toInt())
                }
            })
    }

    fun showMissedCallNotification() {
        val apiVersion = ApiUtils.getConversationApiVersion(
            signatureVerification.user,
            intArrayOf(
                ApiUtils.APIv4,
                ApiUtils.APIv3, 1
            )
        )
        ncApi.getRoom(
            credentials,
            ApiUtils.getUrlForRoom(
                apiVersion, signatureVerification.user?.baseUrl,
                pushMessage.id
            )
        )
            .subscribeOn(Schedulers.io())
            .retry(GET_ROOM_RETRY_COUNT)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : Observer<RoomOverall> {
                override fun onSubscribe(d: Disposable) {
                    // unused atm
                }

                override fun onNext(roomOverall: RoomOverall) {
                    val currentConversation = roomOverall.ocs!!.data
                    val notificationBuilder: NotificationCompat.Builder?

                    notificationBuilder = NotificationCompat.Builder(
                        context!!,
                        NotificationUtils.NotificationChannels
                            .NOTIFICATION_CHANNEL_MESSAGES_V4.name
                    )

                    val notification: Notification = notificationBuilder
                        .setContentTitle(
                            String.format(
                                context!!.resources.getString(R.string.nc_missed_call),
                                currentConversation!!.displayName
                            )
                        )
                        .setSmallIcon(R.drawable.ic_baseline_phone_missed_24)
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setContentIntent(getIntentToOpenConversation())
                        .build()

                    val notificationId: Int = SystemClock.uptimeMillis().toInt()
                    notificationManager.notify(notificationId, notification)
                    Log.d(TAG, "'you missed a call' notification was created")
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "An error occurred while fetching room for the 'missed call' notification", e)
                }

                override fun onComplete() {
                    // unused atm
                }
            })
    }

    private fun getIntentToOpenConversation(): PendingIntent? {
        val bundle = Bundle()
        val intent = Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK

        bundle.putString(KEY_ROOM_TOKEN, pushMessage.id)
        bundle.putParcelable(KEY_USER_ENTITY, signatureVerification.user)
        bundle.putBoolean(KEY_FROM_NOTIFICATION_START_CALL, false)

        intent.putExtras(bundle)

        val requestCode = System.currentTimeMillis().toInt()
        val intentFlag: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getActivity(context, requestCode, intent, intentFlag)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun isCallNotificationVisible(decryptedPushMessage: DecryptedPushMessage): Boolean {
        var isVisible = false

        val notificationManager = context!!.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notifications = notificationManager.activeNotifications
        for (notification in notifications) {
            if (notification.id == decryptedPushMessage.timestamp.toInt()) {
                isVisible = true
                break
            }
        }
        return isVisible
    }

    companion object {
        val TAG = NotificationWorker::class.simpleName
        private const val CHAT = "chat"
        private const val ROOM = "room"
        private const val SPREED_APP = "spreed"
        private const val TIMER_START = 1
        private const val TIMER_COUNT = 12
        private const val TIMER_DELAY: Long = 5
        private const val GET_ROOM_RETRY_COUNT: Long = 3
    }
}
