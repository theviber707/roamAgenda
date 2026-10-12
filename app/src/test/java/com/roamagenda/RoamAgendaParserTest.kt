/*
 * SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.roamagenda

import com.roamagenda.data.ParseResult
import com.roamagenda.data.RepeaterInfo
import com.roamagenda.data.RoamAgendaParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

class RoamAgendaParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testFiletagsRegexMatching() {
        val matchingLines = listOf(
            "#+filetags: :agenda:",
            "#+filetags: :project:agenda:personal:",
            "#+FILETAGS: :agenda:",
            "#+filetags: :agenda",
            "#+filetags: :work:agenda",
            "#+filetags:  :agenda:  "
        )
        for (line in matchingLines) {
            assertTrue("Line should match: '$line'", RoamAgendaParser.AGENDA_FILETAG_REGEX.containsMatchIn(line))
        }

        val nonMatchingLines = listOf(
            "#+filetags: :personal:work:",
            "#+filetags: :notagenda:",
            "#+filetags: :agendawork:",
            "#+other_tag: :agenda:"
        )
        for (line in nonMatchingLines) {
            assertTrue("Line should NOT match: '$line'", !RoamAgendaParser.AGENDA_FILETAG_REGEX.containsMatchIn(line))
        }
    }

    @Test
    fun testParseOrgFileIgnoresNonAgendaFiles() {
        val file = tempFolder.newFile("notes.org")
        file.writeText(
            """
            #+title: Random Notes
            #+filetags: :notes:personal:
            
            * TODO [#A] Buy groceries
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)
        assertNull("Non-agenda file should return null", tasks)
    }

    @Test
    fun testParseOrgFileExtractsPrioritizedTasks() {
        val file = tempFolder.newFile("agenda_note.org")
        file.writeText(
            """
            #+title: Work Projects
            #+filetags: :work:agenda:
            
            * TODO [#A] Submit quarterly financial report
            * TODO Task without priority cookie
            ** NEXT [#B] Review pull request from team :dev:
            *** DONE [#A] Already completed task
            * NEXT [#C] Clean up documentation
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)
        assertNotNull(tasks)
        assertEquals(3, tasks!!.size)

        val taskA = tasks[0]
        assertEquals('A', taskA.priority)
        assertEquals("TODO", taskA.todoKeyword)
        assertEquals("Submit quarterly financial report", taskA.title)
        assertEquals("Work Projects", taskA.fileTitle)
        assertEquals(4, taskA.lineNumber)

        val taskB = tasks[1]
        assertEquals('B', taskB.priority)
        assertEquals("NEXT", taskB.todoKeyword)
        assertEquals("Review pull request from team :dev:", taskB.title)
        assertEquals("Work Projects", taskB.fileTitle)
        assertEquals(6, taskB.lineNumber)

        val taskC = tasks[2]
        assertEquals('C', taskC.priority)
        assertEquals("NEXT", taskC.todoKeyword)
        assertEquals("Clean up documentation", taskC.title)
        assertEquals(8, taskC.lineNumber)
    }

    @Test
    fun testFallbackToFileTitleWhenNoTitleDirective() {
        val file = tempFolder.newFile("untitled_agenda.org")
        file.writeText(
            """
            #+filetags: :agenda:
            
            * TODO [#B] Organize desk
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)
        assertNotNull(tasks)
        assertEquals(1, tasks!!.size)
        assertEquals("untitled_agenda.org", tasks[0].fileTitle)
    }

    @Test
    fun testMarkTaskDoneInPlaceRewrite() {
        val file = tempFolder.newFile("chores.org")
        file.writeText(
            """
            #+title: Daily Chores
            #+filetags: :agenda:
            
            * Some header info
            ** NEXT [#A] Fix the leaking kitchen pipe :plumbing:
            *** TODO [#B] Take out recycling
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)
        assertNotNull(tasks)
        val taskA = tasks!!.first { it.priority == 'A' }

        val success = RoamAgendaParser.markTaskDone(taskA)
        assertTrue("Mark done should succeed", success)

        val updatedLines = file.readLines()
        assertEquals("** DONE [#A] Fix the leaking kitchen pipe :plumbing:", updatedLines[4])
        assertEquals("*** TODO [#B] Take out recycling", updatedLines[5])

        // Verify taskA no longer appears in scanned agenda
        val rescanned = RoamAgendaParser.parseOrgFile(file)
        assertNotNull(rescanned)
        assertEquals(1, rescanned!!.size)
        assertEquals('B', rescanned[0].priority)
    }

    @Test
    fun testParseAndAdvanceRepeatingDailyTask() {
        val file = tempFolder.newFile("habits.org")
        file.writeText(
            """
            #+title: Habits
            #+filetags: :agenda:
            
            * TODO [#C] Blood Meridian
            SCHEDULED: <2026-10-12 Mon ++1w>
            :PROPERTIES:
            :LAST_REPEAT: [2026-10-05 Mon 11:45]
            :END:
            :LOGBOOK:
            - State "DONE"       from "TODO"       [2026-10-05 Mon 11:45]
            :END:
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)
        assertNotNull(tasks)
        assertEquals(1, tasks!!.size)

        val task = tasks[0]
        assertEquals('C', task.priority)
        assertEquals("Blood Meridian", task.title)
        assertEquals(LocalDate.of(2026, 10, 12), task.scheduledDate)
        assertEquals("++1w", task.repeater?.raw)

        // Complete the task on 2026-10-12
        val fixedNow = LocalDateTime.of(2026, 10, 12, 11, 45)
        val success = RoamAgendaParser.markTaskDone(task, now = fixedNow)
        assertTrue("Repeating task completion should succeed", success)

        val updatedContent = file.readText()

        // 1. Headline should stay TODO
        assertTrue("Headline must remain TODO", updatedContent.contains("* TODO [#C] Blood Meridian"))

        // 2. SCHEDULED date should advance by 1 week to 2026-10-19
        assertTrue(
            "SCHEDULED date must advance to 2026-10-19 Mon ++1w",
            updatedContent.contains("SCHEDULED: <2026-10-19 Mon ++1w>")
        )

        // 3. :LAST_REPEAT: updated to today's date
        assertTrue(
            ":LAST_REPEAT: must be updated",
            updatedContent.contains(":LAST_REPEAT: [2026-10-12 Mon 11:45]")
        )

        // 4. :LOGBOOK: drawer must contain new entry at top
        assertTrue(
            ":LOGBOOK: must have new state entry",
            updatedContent.contains("- State \"DONE\"       from \"TODO\"       [2026-10-12 Mon 11:45]")
        )
        assertTrue(
            ":LOGBOOK: must preserve older entry",
            updatedContent.contains("- State \"DONE\"       from \"TODO\"       [2026-10-05 Mon 11:45]")
        )
    }

    @Test
    fun testRepeaterCalculation() {
        val today = LocalDate.of(2026, 10, 12) // Monday

        // 1. Cumulative repeat (+)
        val repPlus = RepeaterInfo(type = "+", value = 1, unit = 'd', raw = "+1d")
        val nextPlus = RoamAgendaParser.calculateNextScheduledDate(LocalDate.of(2026, 10, 12), repPlus, today)
        assertEquals(LocalDate.of(2026, 10, 13), nextPlus)

        // 2. Catch-up repeat (++): when overdue by 2 weeks, catch up past today
        val repCatchup = RepeaterInfo(type = "++", value = 1, unit = 'w', raw = "++1w")
        val nextCatchup = RoamAgendaParser.calculateNextScheduledDate(LocalDate.of(2026, 9, 28), repCatchup, today)
        assertEquals(LocalDate.of(2026, 10, 19), nextCatchup)

        // 3. Restart repeat (.+): always steps from today
        val repRestart = RepeaterInfo(type = ".+", value = 2, unit = 'd', raw = ".+2d")
        val nextRestart = RoamAgendaParser.calculateNextScheduledDate(LocalDate.of(2026, 9, 1), repRestart, today)
        assertEquals(LocalDate.of(2026, 10, 14), nextRestart)
    }

    @Test
    fun testScanDirectoryRecursion() {
        val subDir = tempFolder.newFolder("subfolder", "nested")
        val file1 = File(tempFolder.root, "root_agenda.org")
        file1.writeText(
            """
            #+title: Root
            #+filetags: :agenda:
            * TODO [#A] Task 1
            """.trimIndent()
        )

        val file2 = File(subDir, "nested_agenda.org")
        file2.writeText(
            """
            #+title: Nested
            #+filetags: :agenda:
            * NEXT [#B] Task 2
            """.trimIndent()
        )

        val ignoredFile = File(subDir, "plain.org")
        ignoredFile.writeText(
            """
            #+title: Plain
            * TODO [#A] Not in agenda
            """.trimIndent()
        )

        val result = RoamAgendaParser.scanDirectory(tempFolder.root)
        assertTrue(result is ParseResult.Success)
        val success = result as ParseResult.Success
        assertEquals(2, success.tasks.size)
        assertEquals(3, success.totalFilesScanned)
        assertEquals(2, success.agendaFilesFound)
    }
}
