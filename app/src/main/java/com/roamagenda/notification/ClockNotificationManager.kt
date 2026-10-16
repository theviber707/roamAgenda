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
import android.os.SystemClock
import android.widget.RemoteViews
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
    const val ACTION_CLOCK_PAUSE = "com.roamagenda.action.CLOCK_PAUSE"
    const val ACTION_CLOCK_RESUME = "com.roamagenda.action.CLOCK_RESUME"
    const val ACTION_CLOCK_OUT = "com.roamagenda.action.CLOCK_OUT"
    const val ACTION_CLOCK_CANCEL = "com.roamagenda.action.CLOCK_CANCEL"

    const val EXTRA_TASK_FILE_PATH = "extra_task_file_path"
    const val EXTRA_TASK_LINE_NUMBER = "extra_task_line_number"
    const val EXTRA_TASK_PRIORITY = "extra_task_priority"
    const val EXTRA_TASK_TITLE = "extra_task_title"
    const val EXTRA_TASK_TODO_KEYWORD = "extra_task_todo_keyword"
    const val EXTRA_TASK_FILE_TITLE = "extra_task_file_title"

    data class ClockSession(
        val filePath: String,
        val lineNumber: Int,
        val title: String,
        val fileTitle: String,
        val priority: Char,
        val todoKeyword: String,
        val isPaused: Boolean,
        val accumulatedSeconds: Long
    )

    private const val PREFS_NAME = "clock_session"
    private const val KEY_FILE_PATH = "file_path"
    private const val KEY_LINE_NUMBER = "line_number"
    private const val KEY_TITLE = "title"
    private const val KEY_FILE_TITLE = "file_title"
    private const val KEY_PRIORITY = "priority"
    private const val KEY_TODO_KEYWORD = "todo_keyword"
    private const val KEY_IS_PAUSED = "is_paused"
    private const val KEY_ACCUMULATED_SECONDS = "accumulated_seconds"

    fun saveSession(context: Context, session: ClockSession) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_FILE_PATH, session.filePath)
            .putInt(KEY_LINE_NUMBER, session.lineNumber)
            .putString(KEY_TITLE, session.title)
            .putString(KEY_FILE_TITLE, session.fileTitle)
            .putString(KEY_PRIORITY, session.priority.toString())
            .putString(KEY_TODO_KEYWORD, session.todoKeyword)
            .putBoolean(KEY_IS_PAUSED, session.isPaused)
            .putLong(KEY_ACCUMULATED_SECONDS, session.accumulatedSeconds)
            .apply()
    }

    fun getSession(context: Context): ClockSession? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_FILE_PATH, null) ?: return null
        val line = prefs.getInt(KEY_LINE_NUMBER, -1)
        if (line == -1) return null
        return ClockSession(
            filePath = path,
            lineNumber = line,
            title = prefs.getString(KEY_TITLE, "") ?: "",
            fileTitle = prefs.getString(KEY_FILE_TITLE, "") ?: "",
            priority = prefs.getString(KEY_PRIORITY, "A")?.firstOrNull() ?: 'A',
            todoKeyword = prefs.getString(KEY_TODO_KEYWORD, "TODO") ?: "TODO",
            isPaused = prefs.getBoolean(KEY_IS_PAUSED, false),
            accumulatedSeconds = prefs.getLong(KEY_ACCUMULATED_SECONDS, 0L)
        )
    }

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

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
    fun buildNotification(
        context: Context,
        task: RoamTask,
        isPaused: Boolean = false,
        accumulatedSeconds: Long = 0L
    ): NotificationCompat.Builder {
        val nowEpoch = System.currentTimeMillis()
        val startTimeMillis = task.clockStartTime?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            ?: nowEpoch

        val chronometerBase = if (isPaused) {
            SystemClock.elapsedRealtime() - (accumulatedSeconds * 1000L)
        } else {
            val currentSegmentMillis = (nowEpoch - startTimeMillis).coerceAtLeast(0L)
            val totalElapsedMillis = (accumulatedSeconds * 1000L) + currentSegmentMillis
            SystemClock.elapsedRealtime() - totalElapsedMillis
        }

        val displayTitle = RoamAgendaParser.formatOrgTitle(task.title)

        val rawContentText = if (task.steps.isNotEmpty()) {
            val completed = task.steps.count { it.isChecked }
            val stepsProgress = context.getString(R.string.steps_progress, completed, task.steps.size)
            if (task.fileTitle.isNotBlank()) "$stepsProgress • ${task.fileTitle}" else stepsProgress
        } else {
            task.fileTitle.ifBlank { context.getString(R.string.app_name) }
        }

        val contentText = if (isPaused) {
            "${context.getString(R.string.clock_paused)} • $rawContentText"
        } else {
            rawContentText
        }

        // Custom RemoteViews with prominent live ticking Chronometer (matching stopwatch UI)
        val collapsedView = RemoteViews(context.packageName, R.layout.notification_clock).apply {
            setChronometer(R.id.notification_chronometer, chronometerBase, null, !isPaused)
            setTextViewText(R.id.notification_task_title, displayTitle)
            setTextViewText(R.id.notification_task_subtitle, contentText)
        }
        val expandedView = RemoteViews(context.packageName, R.layout.notification_clock_expanded).apply {
            setChronometer(R.id.notification_chronometer, chronometerBase, null, !isPaused)
            setTextViewText(R.id.notification_task_title, displayTitle)
            setTextViewText(R.id.notification_task_subtitle, contentText)
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

        // Action 2: Pause or Resume
        val pauseOrResumePendingIntent = if (isPaused) {
            val resumeIntent = Intent(context, ClockActionReceiver::class.java).apply {
                action = ACTION_CLOCK_RESUME
                putExtra(EXTRA_TASK_FILE_PATH, task.filePath)
                putExtra(EXTRA_TASK_LINE_NUMBER, task.lineNumber)
                putExtra(EXTRA_TASK_PRIORITY, task.priority)
                putExtra(EXTRA_TASK_TITLE, task.title)
                putExtra(EXTRA_TASK_TODO_KEYWORD, task.todoKeyword)
                putExtra(EXTRA_TASK_FILE_TITLE, task.fileTitle)
            }
            PendingIntent.getBroadcast(
                context,
                105,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val pauseIntent = Intent(context, ClockActionReceiver::class.java).apply {
                action = ACTION_CLOCK_PAUSE
                putExtra(EXTRA_TASK_FILE_PATH, task.filePath)
                putExtra(EXTRA_TASK_LINE_NUMBER, task.lineNumber)
                putExtra(EXTRA_TASK_PRIORITY, task.priority)
                putExtra(EXTRA_TASK_TITLE, task.title)
                putExtra(EXTRA_TASK_TODO_KEYWORD, task.todoKeyword)
                putExtra(EXTRA_TASK_FILE_TITLE, task.fileTitle)
            }
            PendingIntent.getBroadcast(
                context,
                104,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Action 3: Clock Out / Stop
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

        // Action 4: Cancel Clock
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
            putString("android.ongoingActivityNoti.chipExpandedText", displayTitle)
            // Samsung One UI Custom Chronometer RemoteViews
            putParcelable("android.ongoingActivityNoti.chronometerRemoteView", collapsedView)
            putInt("android.ongoingActivityNoti.chronometerRemoteViewPosition", 1)
            putString("android.ongoingActivityNoti.chronometerRemoteViewTag", "ongoing_remote_views_tag")
            putInt("android.ongoingActivityNoti.nowbarChronometerPosition", 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putParcelable(
                    "android.ongoingActivityNoti.chipIcon",
                    Icon.createWithResource(context, R.drawable.ic_stat_clock)
                )
            }
            // Android 16 Promoted Ongoing Notification (Live Update)
            putBoolean("android.requestPromotedOngoing", true)
            // Explicitly preserve chronometer flag so OEM builders do not strip it
            putBoolean(NotificationCompat.EXTRA_SHOW_CHRONOMETER, !isPaused)
            putBoolean("android.showChronometer", !isPaused)
            if (isPaused) {
                putBoolean("android.ongoingActivityNoti.isPaused", true)
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_CLOCK)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .setContentTitle(displayTitle)
            .setContentText(contentText)
            .setSubText(
                if (isPaused) "${context.getString(R.string.clock_notification_org_clock)} • ${context.getString(R.string.clock_paused)}"
                else context.getString(R.string.clock_notification_org_clock)
            )
            .setUsesChronometer(!isPaused)
            .setChronometerCountDown(false)
            .setShowWhen(!isPaused)
            .apply {
                if (!isPaused) {
                    val totalElapsed = (accumulatedSeconds * 1000L) + (nowEpoch - startTimeMillis).coerceAtLeast(0L)
                    setWhen(nowEpoch - totalElapsed)
                }
            }
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
                if (isPaused) R.drawable.ic_action_play else R.drawable.ic_action_pause,
                if (isPaused) context.getString(R.string.resume_clock) else context.getString(R.string.pause_clock),
                pauseOrResumePendingIntent
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
    fun show(
        context: Context,
        task: RoamTask,
        isPaused: Boolean = false,
        accumulatedSeconds: Long = 0L
    ) {
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

        val builder = buildNotification(context, task, isPaused, accumulatedSeconds)
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
