/*
 * SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.roamagenda.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.roamagenda.data.RoamAgendaParser
import com.roamagenda.data.RoamTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.LocalDateTime

/**
 * Handles actions from the ongoing clock notification:
 * - "Clock Out & Done"
 * - "Pause"
 * - "Resume"
 * - "Clock Out"
 * - "Cancel Clock"
 */
class ClockActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val filePath = intent.getStringExtra(ClockNotificationManager.EXTRA_TASK_FILE_PATH) ?: return
        val lineNumber = intent.getIntExtra(ClockNotificationManager.EXTRA_TASK_LINE_NUMBER, -1)
        if (lineNumber == -1) return

        val priority = intent.getCharExtra(ClockNotificationManager.EXTRA_TASK_PRIORITY, 'A')
        val title = intent.getStringExtra(ClockNotificationManager.EXTRA_TASK_TITLE) ?: ""
        val todoKeyword = intent.getStringExtra(ClockNotificationManager.EXTRA_TASK_TODO_KEYWORD) ?: "TODO"
        val fileTitle = intent.getStringExtra(ClockNotificationManager.EXTRA_TASK_FILE_TITLE) ?: ""

        val fallbackTask = RoamTask(
            filePath = filePath,
            lineNumber = lineNumber,
            priority = priority,
            todoKeyword = todoKeyword,
            title = title,
            fileTitle = fileTitle,
            isClockedIn = true
        )

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(filePath)
                val fullTask = if (file.exists() && file.isFile) {
                    val parsedTasks = RoamAgendaParser.parseOrgFile(file)
                    parsedTasks?.firstOrNull { it.lineNumber == lineNumber }
                        ?: parsedTasks?.firstOrNull { it.isClockedIn }
                        ?: fallbackTask
                } else {
                    fallbackTask
                }

                when (action) {
                    ClockNotificationManager.ACTION_CLOCK_PAUSE -> {
                        val session = ClockNotificationManager.getSession(context)
                        val priorAccumulated = session?.accumulatedSeconds ?: 0L
                        val segmentSecs = fullTask.clockStartTime?.let {
                            Duration.between(it, LocalDateTime.now()).seconds.coerceAtLeast(0L)
                        } ?: 0L
                        val totalAccumulated = priorAccumulated + segmentSecs

                        RoamAgendaParser.clockOut(fullTask)

                        val updatedSession = ClockNotificationManager.ClockSession(
                            filePath = fullTask.filePath,
                            lineNumber = fullTask.lineNumber,
                            title = fullTask.title,
                            fileTitle = fullTask.fileTitle,
                            priority = fullTask.priority,
                            todoKeyword = fullTask.todoKeyword,
                            isPaused = true,
                            accumulatedSeconds = totalAccumulated
                        )
                        ClockNotificationManager.saveSession(context, updatedSession)
                        ClockNotificationManager.show(context, fullTask, isPaused = true, accumulatedSeconds = totalAccumulated)
                        ClockNotificationManager.notifyClockStateChanged()
                    }

                    ClockNotificationManager.ACTION_CLOCK_RESUME -> {
                        val session = ClockNotificationManager.getSession(context)
                        val accumulated = session?.accumulatedSeconds ?: 0L

                        RoamAgendaParser.clockIn(fullTask)

                        val now = LocalDateTime.now()
                        val updatedSession = ClockNotificationManager.ClockSession(
                            filePath = fullTask.filePath,
                            lineNumber = fullTask.lineNumber,
                            title = fullTask.title,
                            fileTitle = fullTask.fileTitle,
                            priority = fullTask.priority,
                            todoKeyword = fullTask.todoKeyword,
                            isPaused = false,
                            accumulatedSeconds = accumulated
                        )
                        ClockNotificationManager.saveSession(context, updatedSession)
                        val resumedTask = fullTask.copy(isClockedIn = true, clockStartTime = now)
                        ClockNotificationManager.show(context, resumedTask, isPaused = false, accumulatedSeconds = accumulated)
                        ClockNotificationManager.notifyClockStateChanged()
                    }

                    ClockNotificationManager.ACTION_CLOCK_OUT_AND_DONE -> {
                        if (fullTask.isClockedIn) {
                            RoamAgendaParser.clockOut(fullTask)
                        }
                        RoamAgendaParser.markTaskDone(fullTask)
                        ClockNotificationManager.clearSession(context)
                        ClockNotificationManager.cancel(context)
                        ClockNotificationManager.notifyClockStateChanged()
                    }

                    ClockNotificationManager.ACTION_CLOCK_OUT -> {
                        if (fullTask.isClockedIn) {
                            RoamAgendaParser.clockOut(fullTask)
                        }
                        ClockNotificationManager.clearSession(context)
                        ClockNotificationManager.cancel(context)
                        ClockNotificationManager.notifyClockStateChanged()
                    }

                    ClockNotificationManager.ACTION_CLOCK_CANCEL -> {
                        if (fullTask.isClockedIn) {
                            RoamAgendaParser.cancelClock(fullTask)
                        }
                        ClockNotificationManager.clearSession(context)
                        ClockNotificationManager.cancel(context)
                        ClockNotificationManager.notifyClockStateChanged()
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
