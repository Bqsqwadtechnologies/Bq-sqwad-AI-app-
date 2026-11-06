package com.bqsqwad.ai.core

import android.app.PendingIntent
import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.bqsqwad.ai.R

/**
 * Conversation-style notification with full-color BQ Sqwad AI logo as Person avatar (Prompt I Part 3).
 * Small icon remains alpha silhouette (system requirement); no large icon on the right.
 */
object BqConversationNotification {
    fun build(
        context: Context,
        channelId: String,
        title: String,
        preview: String,
        conversationId: String?,
        contentIntent: PendingIntent,
        shortcutId: String? = conversationId
    ): android.app.Notification {
        val logo = runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.bq_notification_logo)
        }.getOrNull()
        val personBuilder = Person.Builder()
            .setName("BQ Sqwad AI")
            .setKey("bq-sqwad-ai")
        if (logo != null) {
            personBuilder.setIcon(IconCompat.createWithBitmap(logo))
        }
        val me = personBuilder.build()
        val body = if (preview.length > 180) preview.take(177) + "…More" else preview
        val style = NotificationCompat.MessagingStyle(me)
            .addMessage(body, System.currentTimeMillis(), me)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.bq_notification_mark)
            .setColor(0xFF246BCE.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setSubText("AI reply")
            .setStyle(style)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (!shortcutId.isNullOrBlank()) {
            builder.setShortcutId(shortcutId)
        }
        return builder.build()
    }
}
