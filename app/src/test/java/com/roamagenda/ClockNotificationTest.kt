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
        assertEquals("com.roamagenda.action.CLOCK_PAUSE", ClockNotificationManager.ACTION_CLOCK_PAUSE)
        assertEquals("com.roamagenda.action.CLOCK_RESUME", ClockNotificationManager.ACTION_CLOCK_RESUME)
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

    @Test
    fun testPauseAndResumeGeneratesMultipleClockSegmentsAndSumsTotalTime() {
        val tempFile = File.createTempFile("clock_pause_resume_test", ".org")
        tempFile.deleteOnExit()
        tempFile.writeText(
            """
            #+title: Work Notes
            #+filetags: :agenda:

            * TODO [#A] Task with pause and resume
            """.trimIndent()
        )

        // 1. Clock in at 10:00
        val t0 = RoamAgendaParser.parseOrgFile(tempFile)!![0]
        val start1 = LocalDateTime.of(2026, 10, 15, 10, 0)
        RoamAgendaParser.clockIn(t0, start1)

        val t1 = RoamAgendaParser.parseOrgFile(tempFile)!![0]
        assertTrue(t1.isClockedIn)
        assertEquals(start1, t1.clockStartTime)

        // 2. Pause at 10:15 (clocks out first interval: 15 min)
        val pauseTime = LocalDateTime.of(2026, 10, 15, 10, 15)
        RoamAgendaParser.clockOut(t1, pauseTime)

        val t2 = RoamAgendaParser.parseOrgFile(tempFile)!![0]
        org.junit.Assert.assertFalse(t2.isClockedIn)
        assertEquals(15L, t2.totalClockedMinutes)

        // 3. Resume at 10:30 (clocks in second interval)
        val resumeTime = LocalDateTime.of(2026, 10, 15, 10, 30)
        RoamAgendaParser.clockIn(t2, resumeTime)

        val t3 = RoamAgendaParser.parseOrgFile(tempFile)!![0]
        assertTrue(t3.isClockedIn)
        assertEquals(resumeTime, t3.clockStartTime)
        assertEquals(15L, t3.totalClockedMinutes)

        // 4. Final clock out at 10:45 (another 15 min, total 30 min)
        val stopTime = LocalDateTime.of(2026, 10, 15, 10, 45)
        RoamAgendaParser.clockOut(t3, stopTime)

        val t4 = RoamAgendaParser.parseOrgFile(tempFile)!![0]
        org.junit.Assert.assertFalse(t4.isClockedIn)
        assertEquals(30L, t4.totalClockedMinutes)

        val finalContent = tempFile.readText()
        assertTrue(finalContent.contains("CLOCK: [2026-10-15 Thu 10:00]--[2026-10-15 Thu 10:15] =>  0:15"))
        assertTrue(finalContent.contains("CLOCK: [2026-10-15 Thu 10:30]--[2026-10-15 Thu 10:45] =>  0:15"))
    }
}
