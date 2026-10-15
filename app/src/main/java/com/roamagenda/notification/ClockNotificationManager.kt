/*
 * SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.roamagenda.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.roamagenda.MainActivity
import com.roamagenda.R
import com.roamagenda.data.RoamAgendaParser
import com.roamagenda.data.RoamTask
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.ZoneId

/**
 * Manages the ongoing foreground clocking notification with Samsung One UI 7 Now Bar
 * and Android 16 Live Updates (ProgressStyle) support.
 */
object ClockNotificationManager {

    const val NOTIFICATION_ID_CLOCK = 1001
    const val CHANNEL_ID_CLOCK = "active_clock_channel"

    const val ACTION_CLOCK_OUT_AND_DONE = "com.roamagenda.action.CLOCK_OUT_AND_DONE"
    const val ACTION_CLOCK_OUT = "com.roamagenda.action.CLOCK_OUT"
    const val ACTION_CLOCK_CANCEL = "com.roamagenda.action.CLOCK_CANCEL"

    const val EXTRA_TASK_FILE_PATH = "extra_task_file_path"
    const val EXTRA_TASK_LINE_NUMBER = "extra_task_line_number"
    const val EXTRA_TASK_PRIORITY = "extra_task_priority"
    const val EXTRA_TASK_TITLE = "extra_task_title"
    const val EXTRA_TASK_TODO_KEYWORD = "extra_task_todo_keyword"
    const val EXTRA_TASK_FILE_TITLE = "extra_task_file_title"

    private val _clockEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val clockEvents: SharedFlow<Unit> = _clockEvents.asSharedFlow()

    fun notifyClockStateChanged() {
        _clockEvents.tryEmit(Unit)
    }

    /**
     * Creates the notification channel for ongoing clocks if running on Android 8.0+.
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID_CLOCK)
            if (existing == null) {
                val name = context.getString(R.string.notification_channel_clock_name)
                val descriptionText = context.getString(R.string.notification_channel_clock_desc)
                val channel = NotificationChannel(
                    CHANNEL_ID_CLOCK,
                    name,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = descriptionText
                    setShowBadge(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * Builds the NotificationCompat.Builder for an ongoing clock notification.
     */
    fun buildNotification(context: Context, task: RoamTask): NotificationCompat.Builder {
        val startTimeMillis = task.clockStartTime?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            ?: System.currentTimeMillis()

        val displayTitle = RoamAgendaParser.formatOrgTitle(task.title)

        val contentText = if (task.steps.isNotEmpty()) {
            val completed = task.steps.count { it.isChecked }
            val stepsProgress = context.getString(R.string.steps_progress, completed, task.steps.size)
            if (task.fileTitle.isNotBlank()) "$stepsProgress • ${task.fileTitle}" else stepsProgress
        } else {
            task.fileTitle.ifBlank { context.getString(R.string.app_name) }
        }

        // Tap notification to open MainActivity
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            100,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 1: Clock Out & Done
        val clockOutAndDoneIntent = Intent(context, ClockActionReceiver::class.java).apply {
            action = ACTION_CLOCK_OUT_AND_DONE
            putExtra(EXTRA_TASK_FILE_PATH, task.filePath)
            putExtra(EXTRA_TASK_LINE_NUMBER, task.lineNumber)
            putExtra(EXTRA_TASK_PRIORITY, task.priority)
            putExtra(EXTRA_TASK_TITLE, task.title)
            putExtra(EXTRA_TASK_TODO_KEYWORD, task.todoKeyword)
            putExtra(EXTRA_TASK_FILE_TITLE, task.fileTitle)
        }
        val clockOutAndDonePendingIntent = PendingIntent.getBroadcast(
            context,
            103,
            clockOutAndDoneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 2: Clock Out
        val clockOutIntent = Intent(context, ClockActionReceiver::class.java).apply {
            action = ACTION_CLOCK_OUT
            putExtra(EXTRA_TASK_FILE_PATH, task.filePath)
            putExtra(EXTRA_TASK_LINE_NUMBER, task.lineNumber)
            putExtra(EXTRA_TASK_PRIORITY, task.priority)
            putExtra(EXTRA_TASK_TITLE, task.title)
            putExtra(EXTRA_TASK_TODO_KEYWORD, task.todoKeyword)
            putExtra(EXTRA_TASK_FILE_TITLE, task.fileTitle)
        }
        val clockOutPendingIntent = PendingIntent.getBroadcast(
            context,
            101,
            clockOutIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 3: Cancel Clock
        val cancelIntent = Intent(context, ClockActionReceiver::class.java).apply {
            action = ACTION_CLOCK_CANCEL
            putExtra(EXTRA_TASK_FILE_PATH, task.filePath)
            putExtra(EXTRA_TASK_LINE_NUMBER, task.lineNumber)
            putExtra(EXTRA_TASK_PRIORITY, task.priority)
            putExtra(EXTRA_TASK_TITLE, task.title)
            putExtra(EXTRA_TASK_TODO_KEYWORD, task.todoKeyword)
            putExtra(EXTRA_TASK_FILE_TITLE, task.fileTitle)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            102,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val primaryColor = 0xFF6650A4.toInt()

        // Samsung One UI 7 Now Bar and Android 16 Live Updates configuration bundle
        val extras = Bundle().apply {
            // Samsung One UI 7 Live Notifications & Now Bar
            putInt("android.ongoingActivityNoti.style", 1)
            putInt("android.ongoingActivityNoti.actionType", 1)
            putInt("android.ongoingActivityNoti.actionPrimarySet", 0)
            putString("android.ongoingActivityNoti.primaryInfo", displayTitle)
            putString("android.ongoingActivityNoti.secondaryInfo", contentText)
            putString("android.ongoingActivityNoti.nowbarPrimaryInfo", displayTitle)
            putString("android.ongoingActivityNoti.nowbarSecondaryInfo", contentText)
            putInt("android.ongoingActivityNoti.chipBgColor", primaryColor)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putParcelable(
                    "android.ongoingActivityNoti.chipIcon",
                    Icon.createWithResource(context, R.drawable.ic_stat_clock)
                )
            }
            // Android 16 Promoted Ongoing Notification (Live Update)
            putBoolean("android.requestPromotedOngoing", true)
            // Explicitly preserve chronometer flag so OEM builders do not strip it
            putBoolean(NotificationCompat.EXTRA_SHOW_CHRONOMETER, true)
            putBoolean("android.showChronometer", true)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_CLOCK)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(displayTitle)
            .setContentText(contentText)
            .setSubText(context.getString(R.string.clock_notification_org_clock))
            .setUsesChronometer(true)
            .setChronometerCountDown(false)
            .setShowWhen(true)
            .setWhen(startTimeMillis)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(primaryColor)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_action_done,
                context.getString(R.string.clock_out_and_done),
                clockOutAndDonePendingIntent
            )
            .addAction(
                R.drawable.ic_action_stop,
                context.getString(R.string.clock_out),
                clockOutPendingIntent
            )
            .addAction(
                R.drawable.ic_action_cancel,
                context.getString(R.string.cancel),
                cancelPendingIntent
            )
            .addExtras(extras)

        // Android 16 Live Updates promoted ongoing method (reflection for forward compatibility)
        try {
            val method = builder.javaClass.getMethod(
                "setRequestPromotedOngoing",
                Boolean::class.javaPrimitiveType
            )
            method.invoke(builder, true)
        } catch (_: Throwable) {
            // Handled via extras
        }

        // Android 16 ProgressStyle progressive enhancement (reflection if available)
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                val psClass = Class.forName("android.app.Notification\$ProgressStyle")
                val ps = psClass.getConstructor().newInstance()
                if (task.steps.isNotEmpty()) {
                    val completed = task.steps.count { it.isChecked }
                    val total = task.steps.size
                    val progressPercent = ((completed.toFloat() / total) * 100).toInt()
                    val setProgressMethod = psClass.getMethod("setProgress", Int::class.javaPrimitiveType)
                    setProgressMethod.invoke(ps, progressPercent)
                }
                val setStyledByProgress = psClass.getMethod("setStyledByProgress", Boolean::class.javaPrimitiveType)
                setStyledByProgress.invoke(ps, false)
            } catch (_: Throwable) {
                // Fall back to standard chronometer
            }
        }

        return builder
    }

    /**
     * Shows or updates the ongoing clock notification.
     */
    fun show(context: Context, task: RoamTask) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        createNotificationChannel(context)

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val builder = buildNotification(context, task)
        notificationManager.notify(NOTIFICATION_ID_CLOCK, builder.build())
    }

    /**
     * Cancels / dismisses the ongoing clock notification.
     */
    fun cancel(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_CLOCK)
    }
}
