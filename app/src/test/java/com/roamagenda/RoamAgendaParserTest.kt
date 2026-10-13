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

    @Test
    fun testScanDirectorySkipsHiddenDirectoriesAndConflictFiles() {
        val root = tempFolder.newFolder("org_roam")

        // 1. Valid agenda file
        val validFile = File(root, "valid.org")
        validFile.writeText(
            """
            #+title: Valid Note
            #+filetags: :agenda:
            * TODO [#A] Valid Task
            SCHEDULED: <2026-10-13 Tue>
            """.trimIndent()
        )

        // 2. Syncthing .stversions directory
        val stversionsDir = File(root, ".stversions").apply { mkdirs() }
        File(stversionsDir, "valid~20261010-090235.org").writeText(
            """
            #+title: Old Version
            #+filetags: :agenda:
            * TODO [#A] Duplicate Old Task
            SCHEDULED: <2026-10-10 Sat>
            """.trimIndent()
        )

        // 3. Git directory
        val gitDir = File(root, ".git").apply { mkdirs() }
        File(gitDir, "ignored.org").writeText(
            """
            #+filetags: :agenda:
            * TODO [#A] Git Ignored
            """.trimIndent()
        )

        // 4. Syncthing conflict file
        File(root, "valid.sync-conflict-20261012-120000-ABCDEF.org").writeText(
            """
            #+filetags: :agenda:
            * TODO [#A] Conflict Duplicate
            """.trimIndent()
        )

        // 5. Emacs backup file and auto-save
        File(root, "valid.org~").writeText(
            """
            #+filetags: :agenda:
            * TODO [#A] Backup Duplicate
            """.trimIndent()
        )
        File(root, "#valid.org#").writeText(
            """
            #+filetags: :agenda:
            * TODO [#A] Auto-save Duplicate
            """.trimIndent()
        )

        // 6. Org-mode archive file
        File(root, "valid.org_archive").writeText(
            """
            #+filetags: :agenda:
            * TODO [#A] Archived Task
            """.trimIndent()
        )

        val result = RoamAgendaParser.scanDirectory(root)
        assertTrue(result is ParseResult.Success)
        val success = result as ParseResult.Success
        assertEquals(1, success.totalFilesScanned)
        assertEquals(1, success.agendaFilesFound)
        assertEquals(1, success.tasks.size)
        assertEquals("Valid Task", success.tasks[0].title)
    }

    @Test
    fun testInactiveTimestampDoesNotCreateScheduledDate() {
        val file = tempFolder.newFile("disabled.org")
        file.writeText(
            """
            #+title: Disabled Tasks
            #+filetags: :agenda:

            * TODO [#A] Research into the current FSF distribution method [2/7]
            SCHEDULED: [2025-06-23 Mon +1w]
            :PROPERTIES:
            :LAST_REPEAT: [2025-05-05 Mon 23:33]
            :END:

            * TODO [#A] Watch Nubank Content
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)
        assertNotNull(tasks)
        assertEquals(2, tasks!!.size)

        // Both tasks must have scheduledDate == null
        assertNull("Inactive bracket timestamp must not count as active schedule", tasks[0].scheduledDate)
        assertNull("Inactive bracket repeater must not be parsed", tasks[0].repeater)
        assertNull("Unscheduled task must have null scheduledDate", tasks[1].scheduledDate)
    }

    @Test
    fun testBareActiveTimestampIsParsed() {
        val file = tempFolder.newFile("brush_teeth.org")
        file.writeText(
            """
            #+title: Habits
            #+filetags: :agenda:

            *** TODO [#B] Brush Teeth [1/5]
            :PROPERTIES:
            :LAST_REPEAT: [2026-10-12 Mon 21:00]
            :END:
            <2026-10-13 Tue ++1d>
            - [ ] After waking up <2026-10-13 Tue 07:00>
            - [X] After lunch <2026-10-13 Tue 12:40>
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)
        assertNotNull(tasks)
        assertEquals(1, tasks!!.size)

        val task = tasks[0]
        assertEquals(LocalDate.of(2026, 10, 13), task.scheduledDate)
        assertNotNull(task.repeater)
        assertEquals("++1d", task.repeater?.raw)

        // Advance task
        val fixedNow = LocalDateTime.of(2026, 10, 13, 21, 0)
        val success = RoamAgendaParser.markTaskDone(task, fixedNow)
        assertTrue(success)

        val content = file.readText()
        // Bare timestamp should advance without prepending SCHEDULED:
        assertTrue(content.contains("<2026-10-14 Wed ++1d>"))
        assertTrue(!content.contains("SCHEDULED: <2026-10-14"))
    }

    @Test
    fun testParseTaskWithChecklistSteps() {
        val file = tempFolder.newFile("checklist_test.org")
        file.writeText(
            """
            #+title: Habits
            #+filetags: :agenda:

            *** TODO [#B] Brush Teeth [1/5]
            :PROPERTIES:
            :LAST_REPEAT: [2026-10-12 Mon 21:00]
            :END:
            <2026-10-13 Tue ++1d>
            - [ ] After waking up <2026-10-13 Tue 07:00>
            - [ ] After breakfast <2026-10-13 Tue 09:00>
            - [X] After lunch <2026-10-13 Tue 12:40>
            - [ ] After snacks <2026-10-13 Tue 16:20>
            - [ ] After dinner <2026-10-13 Tue 20:40>
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)
        assertNotNull(tasks)
        assertEquals(1, tasks!!.size)

        val task = tasks[0]
        assertEquals(5, task.steps.size)

        val step1 = task.steps[0]
        assertEquals(false, step1.isChecked)
        assertEquals("After waking up", step1.text)
        assertEquals("07:00", step1.time)

        val step3 = task.steps[2]
        assertEquals(true, step3.isChecked)
        assertEquals("After lunch", step3.text)
        assertEquals("12:40", step3.time)
    }

    @Test
    fun testToggleTaskStepUpdatesCheckboxAndHeadlineCookie() {
        val file = tempFolder.newFile("step_toggle.org")
        file.writeText(
            """
            #+title: Habits
            #+filetags: :agenda:

            *** TODO [#B] Brush Teeth [1/2]
            <2026-10-13 Tue ++1d>
            - [ ] After waking up <2026-10-13 Tue 07:00>
            - [X] After lunch <2026-10-13 Tue 12:40>
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)!!
        val task = tasks[0]
        val step1 = task.steps[0]

        // Toggle first step to checked
        val success = RoamAgendaParser.toggleTaskStep(task, step1, true)
        assertTrue(success)

        var content = file.readText()
        assertTrue(content.contains("- [X] After waking up <2026-10-13 Tue 07:00>"))
        assertTrue(content.contains("*** TODO [#B] Brush Teeth [2/2]"))

        // Re-parse and toggle second step to unchecked
        val updatedTasks = RoamAgendaParser.parseOrgFile(file)!!
        val step2 = updatedTasks[0].steps[1]
        val success2 = RoamAgendaParser.toggleTaskStep(updatedTasks[0], step2, false)
        assertTrue(success2)

        content = file.readText()
        assertTrue(content.contains("- [ ] After lunch <2026-10-13 Tue 12:40>"))
        assertTrue(content.contains("*** TODO [#B] Brush Teeth [1/2]"))
    }

    @Test
    fun testMarkRepeatingTaskResetsCheckboxesAndCookie() {
        val file = tempFolder.newFile("repeat_reset.org")
        file.writeText(
            """
            #+title: Habits
            #+filetags: :agenda:

            *** TODO [#B] Brush Teeth [2/2]
            <2026-10-13 Tue ++1d>
            - [X] After waking up <2026-10-13 Tue 07:00>
            - [X] After lunch <2026-10-13 Tue 12:40>
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)!!
        val task = tasks[0]

        val fixedNow = LocalDateTime.of(2026, 10, 13, 22, 0)
        val success = RoamAgendaParser.markTaskDone(task, fixedNow)
        assertTrue(success)

        val content = file.readText()
        assertTrue(content.contains("*** TODO [#B] Brush Teeth [0/2]"))
        assertTrue(content.contains("- [ ] After waking up <2026-10-13 Tue 07:00>"))
        assertTrue(content.contains("- [ ] After lunch <2026-10-13 Tue 12:40>"))
        assertTrue(content.contains("<2026-10-14 Wed ++1d>"))
    }

    @Test
    fun testFormatOrgTitleUnwrapsLinks() {
        assertEquals(
            "Nubank Content",
            RoamAgendaParser.formatOrgTitle("[[https://www.youtube.com/@building.nubank/playlists][Nubank Content]]")
        )
        assertEquals(
            "Watch Nubank Content",
            RoamAgendaParser.formatOrgTitle("Watch [[https://www.youtube.com/@building.nubank/playlists][Nubank Content]]")
        )
        assertEquals(
            "https://go.dev",
            RoamAgendaParser.formatOrgTitle("[[https://go.dev]]")
        )
        assertEquals(
            "Plain title without links",
            RoamAgendaParser.formatOrgTitle("Plain title without links")
        )
    }

    @Test
    fun testParseClockEntries() {
        val file = tempFolder.newFile("clocking_test.org")
        file.writeText(
            """
            #+title: Work
            #+filetags: :agenda:

            * TODO [#A] Important feature
            :LOGBOOK:
            CLOCK: [2026-10-13 Tue 14:00]
            CLOCK: [2025-04-11 Fri 16:58]--[2025-04-11 Fri 17:32] =>  0:34
            CLOCK: [2025-05-05 Mon 18:39]--[2025-05-05 Mon 20:39] =>  2:00
            :END:
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)!!
        assertEquals(1, tasks.size)
        val task = tasks[0]

        assertTrue(task.isClockedIn)
        assertEquals(LocalDateTime.of(2026, 10, 13, 14, 0), task.clockStartTime)
        // 34 minutes + 120 minutes = 154 minutes
        assertEquals(154L, task.totalClockedMinutes)
    }

    @Test
    fun testClockInCreatesLogbookAndOpenClock() {
        val file = tempFolder.newFile("clock_in_test.org")
        file.writeText(
            """
            #+title: Work
            #+filetags: :agenda:

            * TODO [#A] Write unit tests
            <2026-10-13 Tue>
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)!!
        val task = tasks[0]

        val fixedNow = LocalDateTime.of(2026, 10, 13, 15, 30)
        val success = RoamAgendaParser.clockIn(task, fixedNow)
        assertTrue(success)

        val content = file.readText()
        assertTrue(content.contains(":LOGBOOK:"))
        assertTrue(content.contains("CLOCK: [2026-10-13 Tue 15:30]"))
        assertTrue(content.contains(":END:"))

        val updatedTasks = RoamAgendaParser.parseOrgFile(file)!!
        assertTrue(updatedTasks[0].isClockedIn)
        assertEquals(fixedNow, updatedTasks[0].clockStartTime)
    }

    @Test
    fun testClockOutClosesRunningClockWithDuration() {
        val file = tempFolder.newFile("clock_out_test.org")
        file.writeText(
            """
            #+title: Work
            #+filetags: :agenda:

            * TODO [#A] Write unit tests
            :LOGBOOK:
            CLOCK: [2026-10-13 Tue 14:00]
            :END:
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)!!
        val task = tasks[0]

        val fixedNow = LocalDateTime.of(2026, 10, 13, 15, 45) // 1h 45m = 105 mins
        val success = RoamAgendaParser.clockOut(task, fixedNow)
        assertTrue(success)

        val content = file.readText()
        assertTrue(content.contains("CLOCK: [2026-10-13 Tue 14:00]--[2026-10-13 Tue 15:45] =>  1:45"))

        val updatedTasks = RoamAgendaParser.parseOrgFile(file)!!
        assertEquals(false, updatedTasks[0].isClockedIn)
        assertEquals(105L, updatedTasks[0].totalClockedMinutes)
    }

    @Test
    fun testCancelClockRemovesRunningClock() {
        val file = tempFolder.newFile("cancel_clock_test.org")
        file.writeText(
            """
            #+title: Work
            #+filetags: :agenda:

            * TODO [#A] Write unit tests
            :LOGBOOK:
            CLOCK: [2026-10-13 Tue 14:00]
            CLOCK: [2026-10-12 Mon 10:00]--[2026-10-12 Mon 11:00] =>  1:00
            :END:
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)!!
        val task = tasks[0]

        val success = RoamAgendaParser.cancelClock(task)
        assertTrue(success)

        val content = file.readText()
        assertEquals(false, content.contains("CLOCK: [2026-10-13 Tue 14:00]"))
        assertTrue(content.contains("CLOCK: [2026-10-12 Mon 10:00]--[2026-10-12 Mon 11:00] =>  1:00"))

        val updatedTasks = RoamAgendaParser.parseOrgFile(file)!!
        assertEquals(false, updatedTasks[0].isClockedIn)
        assertEquals(60L, updatedTasks[0].totalClockedMinutes)
    }

    @Test
    fun testMarkTaskDoneClocksOutRunningClock() {
        val file = tempFolder.newFile("done_clocks_out_test.org")
        file.writeText(
            """
            #+title: Work
            #+filetags: :agenda:

            * TODO [#A] Task with active clock
            :LOGBOOK:
            CLOCK: [2026-10-13 Tue 14:00]
            :END:
            """.trimIndent()
        )

        val tasks = RoamAgendaParser.parseOrgFile(file)!!
        val task = tasks[0]

        val fixedNow = LocalDateTime.of(2026, 10, 13, 14, 30)
        val success = RoamAgendaParser.markTaskDone(task, fixedNow)
        assertTrue(success)

        val content = file.readText()
        assertTrue(content.contains("* DONE [#A] Task with active clock"))
        assertTrue(content.contains("CLOCK: [2026-10-13 Tue 14:00]--[2026-10-13 Tue 14:30] =>  0:30"))
    }
}
