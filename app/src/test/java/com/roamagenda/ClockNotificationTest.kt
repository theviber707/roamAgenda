/*
 * SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.roamagenda

import com.roamagenda.data.RoamAgendaParser
import com.roamagenda.notification.ClockNotificationManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class ClockNotificationTest {

    @Test
    fun testClockNotificationConstants() {
        assertEquals("active_clock_channel", ClockNotificationManager.CHANNEL_ID_CLOCK)
        assertEquals(1001, ClockNotificationManager.NOTIFICATION_ID_CLOCK)
        assertEquals("com.roamagenda.action.CLOCK_OUT_AND_DONE", ClockNotificationManager.ACTION_CLOCK_OUT_AND_DONE)
        assertEquals("com.roamagenda.action.CLOCK_OUT", ClockNotificationManager.ACTION_CLOCK_OUT)
        assertEquals("com.roamagenda.action.CLOCK_CANCEL", ClockNotificationManager.ACTION_CLOCK_CANCEL)
        assertEquals("extra_task_file_path", ClockNotificationManager.EXTRA_TASK_FILE_PATH)
        assertEquals("extra_task_line_number", ClockNotificationManager.EXTRA_TASK_LINE_NUMBER)
        assertEquals("extra_task_priority", ClockNotificationManager.EXTRA_TASK_PRIORITY)
        assertEquals("extra_task_title", ClockNotificationManager.EXTRA_TASK_TITLE)
    }

    @Test
    fun testClockEventFlowNotification() = runTest {
        var received = false
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ClockNotificationManager.clockEvents.collect {
                received = true
            }
        }
        ClockNotificationManager.notifyClockStateChanged()
        assertTrue(received)
        job.cancel()
    }

    @Test
    fun testTaskTitleFormattingForNowBarNotification() {
        val rawTitle = "[[id:1234][Deploy Release]] [1/2] :work:"
        val cleanTitle = RoamAgendaParser.formatOrgTitle(rawTitle)
        assertEquals("Deploy Release [1/2] :work:", cleanTitle)
    }

    @Test
    fun testClockedInDetectionTriggersNotificationEligibility() {
        val tempFile = File.createTempFile("clock_noti_test", ".org")
        tempFile.deleteOnExit()
        tempFile.writeText(
            """
            #+title: Work Notes
            #+filetags: :agenda:

            * TODO [#A] Urgent task with open clock
            :LOGBOOK:
            CLOCK: [2026-10-14 Wed 20:00]
            :END:
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(tempFile)
        assertNotNull(tasks)
        assertEquals(1, tasks!!.size)
        val task = tasks[0]
        assertTrue(task.isClockedIn)
        assertEquals(LocalDateTime.of(2026, 10, 14, 20, 0), task.clockStartTime)
        assertEquals("Urgent task with open clock", RoamAgendaParser.formatOrgTitle(task.title))
    }

    @Test
    fun testClockOutAndMarkDoneClosesClockAndSetsKeywordDone() {
        val tempFile = File.createTempFile("clock_done_test", ".org")
        tempFile.deleteOnExit()
        tempFile.writeText(
            """
            #+title: Work Notes
            #+filetags: :agenda:

            * TODO [#A] Task to clock out and finish
            :LOGBOOK:
            CLOCK: [2026-10-14 Wed 20:00]
            :END:
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(tempFile)!!
        val task = tasks[0]
        assertTrue(task.isClockedIn)

        val fixedEnd = LocalDateTime.of(2026, 10, 14, 20, 45)
        val success = RoamAgendaParser.markTaskDone(task, fixedEnd)
        assertTrue(success)

        val updatedContent = tempFile.readText()
        assertTrue("Headline should transition to DONE", updatedContent.contains("* DONE [#A] Task to clock out and finish"))
        assertTrue("Active clock should be closed with duration", updatedContent.contains("CLOCK: [2026-10-14 Wed 20:00]--[2026-10-14 Wed 20:45] =>  0:45"))
    }
}
