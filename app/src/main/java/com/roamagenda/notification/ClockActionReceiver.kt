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

/**
 * Handles actions from the ongoing clock notification:
 * - "Clock Out & Done"
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
                    ClockNotificationManager.ACTION_CLOCK_OUT_AND_DONE -> {
                        RoamAgendaParser.markTaskDone(fullTask)
                    }
                    ClockNotificationManager.ACTION_CLOCK_OUT -> {
                        RoamAgendaParser.clockOut(fullTask)
                    }
                    ClockNotificationManager.ACTION_CLOCK_CANCEL -> {
                        RoamAgendaParser.cancelClock(fullTask)
                    }
                }
            } finally {
                ClockNotificationManager.cancel(context)
                ClockNotificationManager.notifyClockStateChanged()
                pendingResult.finish()
            }
        }
    }
}
