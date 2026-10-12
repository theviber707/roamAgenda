/*
 * SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.roamagenda.data

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Information about an Org Mode repeater cookie (e.g. `++1w`, `+1d`, `.+1m`).
 *
 * @property type Repeater type: cumulative (`+`), catch-up (`++`), or restart (`.+`).
 * @property value Number of units (e.g. 1).
 * @property unit Unit character: 'd' (days), 'w' (weeks), 'm' (months), 'y' (years), 'h' (hours).
 * @property raw The full cookie string (e.g. "++1w").
 */
data class RepeaterInfo(
    val type: String,
    val value: Long,
    val unit: Char,
    val raw: String
)

/**
 * Represents an agenda item parsed from an Org-Roam file.
 *
 * @property filePath Absolute path to the origin .org file.
 * @property lineNumber 1-based line number of the headline in the file.
 * @property priority Priority letter (e.g. 'A', 'B', 'C').
 * @property todoKeyword TODO state keyword ("TODO" or "NEXT").
 * @property title Headline text following the priority cookie.
 * @property fileTitle Title extracted from `#+title:`, falling back to the filename.
 * @property stars The asterisk headline level markers (e.g. "*", "**").
 * @property scheduledDate Parsed scheduled date, if any.
 * @property scheduledTime Parsed scheduled time string (e.g. "11:45"), if any.
 * @property repeater Repeater info if this is a recurring task.
 * @property id Unique identifier based on file path and line number.
 */
data class RoamTask(
    val filePath: String,
    val lineNumber: Int,
    val priority: Char,
    val todoKeyword: String,
    val title: String,
    val fileTitle: String,
    val stars: String = "*",
    val scheduledDate: LocalDate? = null,
    val scheduledTime: String? = null,
    val repeater: RepeaterInfo? = null,
    val id: String = "$filePath:$lineNumber"
)

/**
 * Result of scanning the configured Org-Roam directory.
 */
sealed interface ParseResult {
    data class Success(
        val tasks: List<RoamTask>,
        val totalFilesScanned: Int,
        val agendaFilesFound: Int
    ) : ParseResult

    data class DirectoryNotFound(val path: String) : ParseResult
    data class NotADirectory(val path: String) : ParseResult
    data class NoOrgFilesFound(val path: String) : ParseResult
    data class Error(val message: String, val cause: Throwable? = null) : ParseResult
}

/**
 * Parser for Org-Roam files according to custom agenda specifications:
 * 1. Filter files containing `#+filetags:` with `:agenda:`.
 * 2. Extract headlines matching `^(\*+)\s+(TODO|NEXT)\s+\[#([A-Z])\]\s+(.*)`.
 * 3. Support repeating tasks with SCHEDULED repeaters (+, ++, .+), :PROPERTIES: :LAST_REPEAT:, and :LOGBOOK:.
 * 4. Update tasks in-place on disk when marked as DONE.
 */
object RoamAgendaParser {

    /**
     * Matches Org filetags containing the `agenda` tag.
     */
    val AGENDA_FILETAG_REGEX = Regex("""(?i)^#\+filetags:.*(?::|\b)agenda(?::|\b)""")

    /**
     * Matches Org title directive `#+title: <title>`.
     */
    val TITLE_REGEX = Regex("""(?i)^#\+title:\s*(.*)""")

    /**
     * Matches headline tasks with TODO/NEXT keywords and an explicit priority cookie.
     */
    val HEADLINE_REGEX = Regex("""^(\s*\*+)\s+(TODO|NEXT)\s+\[#([A-Z])\]\s+(.*)""")

    /**
     * Matches SCHEDULED line with date, optional day/time, and optional repeater.
     * Group 1: Date (YYYY-MM-DD)
     * Group 2: Time (HH:MM), optional
     * Group 3: Repeater type (+, ++, .+)
     * Group 4: Repeater value (digits)
     * Group 5: Repeater unit (h, d, w, m, y)
     */
    val SCHEDULED_REGEX = Regex(
        """SCHEDULED:\s*<(\d{4}-\d{2}-\d{2})(?:\s+[A-Za-z]+)?(?:\s+(\d{1,2}:\d{2}))?(?:\s*(\+\+|\+|\.\+)(\d+)([hdwmy]))?[^>]*>"""
    )

    /**
     * Recursively traverses [rootDir] for `.org` files, filters those with `:agenda:` filetag,
     * and extracts all prioritized TODO/NEXT tasks.
     */
    fun scanDirectory(rootDir: File): ParseResult {
        if (!rootDir.exists()) {
            return ParseResult.DirectoryNotFound(rootDir.absolutePath)
        }
        if (!rootDir.isDirectory) {
            return ParseResult.NotADirectory(rootDir.absolutePath)
        }

        return try {
            val orgFiles = rootDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("org", ignoreCase = true) }
                .toList()

            if (orgFiles.isEmpty()) {
                return ParseResult.NoOrgFilesFound(rootDir.absolutePath)
            }

            val tasks = mutableListOf<RoamTask>()
            var agendaFilesCount = 0

            for (file in orgFiles) {
                val fileTasks = parseOrgFile(file)
                if (fileTasks != null) {
                    agendaFilesCount++
                    tasks.addAll(fileTasks)
                }
            }

            ParseResult.Success(
                tasks = tasks,
                totalFilesScanned = orgFiles.size,
                agendaFilesFound = agendaFilesCount
            )
        } catch (e: Exception) {
            ParseResult.Error(e.message ?: "Unknown error scanning directory", e)
        }
    }

    /**
     * Parses a single Org file.
     * Returns null if the file does not contain the `:agenda:` filetag.
     * Returns a list of [RoamTask] found if it is an agenda file.
     */
    fun parseOrgFile(file: File): List<RoamTask>? {
        if (!file.exists() || !file.isFile) return null

        val lines = try {
            file.readLines(Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }

        var isAgendaFile = false
        var fileTitle: String? = null

        // First pass: inspect header directives
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#+filetags:", ignoreCase = true) && AGENDA_FILETAG_REGEX.containsMatchIn(trimmed)) {
                isAgendaFile = true
            }
            if (fileTitle == null && trimmed.startsWith("#+title:", ignoreCase = true)) {
                val match = TITLE_REGEX.find(trimmed)
                if (match != null) {
                    val extracted = match.groupValues[1].trim()
                    if (extracted.isNotEmpty()) {
                        fileTitle = extracted
                    }
                }
            }
        }

        if (!isAgendaFile) {
            return null
        }

        val effectiveFileTitle = fileTitle ?: file.name
        val tasks = mutableListOf<RoamTask>()

        // Second pass: extract matching headlines and their SCHEDULED planning lines
        for ((index, line) in lines.withIndex()) {
            val match = HEADLINE_REGEX.find(line) ?: continue
            val stars = match.groupValues[1]
            val keyword = match.groupValues[2]
            val priority = match.groupValues[3].firstOrNull() ?: continue
            val title = match.groupValues[4].trim()

            // Look ahead for SCHEDULED planning info under this headline
            var scheduledDate: LocalDate? = null
            var scheduledTime: String? = null
            var repeaterInfo: RepeaterInfo? = null

            var lookahead = index + 1
            while (lookahead < lines.size) {
                val nextLine = lines[lookahead]
                val trimmedNext = nextLine.trim()
                if (trimmedNext.startsWith("*")) break // Reached next headline

                val schedMatch = SCHEDULED_REGEX.find(nextLine)
                if (schedMatch != null) {
                    try {
                        val dateStr = schedMatch.groupValues[1]
                        scheduledDate = LocalDate.parse(dateStr)
                        val timeStr = schedMatch.groupValues[2].takeIf { it.isNotEmpty() }
                        scheduledTime = timeStr

                        val repType = schedMatch.groupValues[3]
                        val repVal = schedMatch.groupValues[4]
                        val repUnit = schedMatch.groupValues[5]
                        if (repType.isNotEmpty() && repVal.isNotEmpty() && repUnit.isNotEmpty()) {
                            repeaterInfo = RepeaterInfo(
                                type = repType,
                                value = repVal.toLong(),
                                unit = repUnit.first(),
                                raw = "$repType$repVal$repUnit"
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    break
                }

                // Stop scanning if content/body begins before planning line
                if (trimmedNext.isNotEmpty() && !trimmedNext.startsWith(":") &&
                    !trimmedNext.startsWith("SCHEDULED") && !trimmedNext.startsWith("DEADLINE") && !trimmedNext.startsWith("CLOSED")) {
                    break
                }
                lookahead++
            }

            tasks.add(
                RoamTask(
                    filePath = file.absolutePath,
                    lineNumber = index + 1,
                    priority = priority,
                    todoKeyword = keyword,
                    title = title,
                    fileTitle = effectiveFileTitle,
                    stars = stars,
                    scheduledDate = scheduledDate,
                    scheduledTime = scheduledTime,
                    repeater = repeaterInfo
                )
            )
        }

        return tasks
    }

    /**
     * Completes a task:
     * - If the task is a recurring task (has a repeater), advances the SCHEDULED date,
     *   records `:LAST_REPEAT:` in `:PROPERTIES:`, and logs a `- State "DONE"` entry in `:LOGBOOK:`.
     * - If normal, replaces `TODO` or `NEXT` with `DONE` in-place.
     */
    fun markTaskDone(task: RoamTask, now: LocalDateTime = LocalDateTime.now()): Boolean {
        return if (task.repeater != null && task.scheduledDate != null) {
            markRepeatingTaskDone(task, now)
        } else {
            rewriteTaskKeyword(task, "DONE")
        }
    }

    /**
     * Advances a repeating task according to Org Mode semantics:
     * 1. Calculates the next scheduled date (`+`, `++`, or `.+`).
     * 2. Replaces the timestamp in the `SCHEDULED:` line.
     * 3. Updates/creates `:LAST_REPEAT:` in the `:PROPERTIES:` drawer.
     * 4. Appends a new `- State "DONE"` entry to the `:LOGBOOK:` drawer.
     * 5. Keeps the headline state as `TODO`.
     */
    fun markRepeatingTaskDone(
        task: RoamTask,
        now: LocalDateTime = LocalDateTime.now()
    ): Boolean {
        val file = File(task.filePath)
        if (!file.exists() || !file.isFile || task.repeater == null || task.scheduledDate == null) {
            return false
        }

        return try {
            val rawText = file.readText(Charsets.UTF_8)
            val lineEnding = if (rawText.contains("\r\n")) "\r\n" else "\n"
            val endsWithNewline = rawText.endsWith("\n") || rawText.endsWith("\r\n")

            val lines = rawText.split(Regex("""\r\n|\r|\n""")).toMutableList()
            if (endsWithNewline && lines.isNotEmpty() && lines.last().isEmpty()) {
                lines.removeAt(lines.lastIndex)
            }

            val targetIndex = task.lineNumber - 1
            val headlineIndex = if (targetIndex in lines.indices && isMatchingTaskLine(lines[targetIndex], task)) {
                targetIndex
            } else {
                lines.indexOfFirst { isMatchingTaskLine(it, task) }
            }

            if (headlineIndex == -1) return false

            // Find bounds of this headline section (until next headline)
            var nextHeadlineIndex = lines.size
            for (i in (headlineIndex + 1) until lines.size) {
                if (lines[i].trim().startsWith("*")) {
                    nextHeadlineIndex = i
                    break
                }
            }

            val today = now.toLocalDate()
            val nextDate = calculateNextScheduledDate(task.scheduledDate, task.repeater, today)
            val nextDayName = nextDate.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            val timePart = if (task.scheduledTime != null) " ${task.scheduledTime}" else ""
            val nextScheduledTag = "<$nextDate $nextDayName$timePart ${task.repeater.raw}>"

            // 1. Advance SCHEDULED date line
            var scheduledLineIndex = -1
            for (i in (headlineIndex + 1) until nextHeadlineIndex) {
                if (SCHEDULED_REGEX.containsMatchIn(lines[i])) {
                    scheduledLineIndex = i
                    val updatedLine = lines[i].replaceFirst(
                        SCHEDULED_REGEX,
                        "SCHEDULED: $nextScheduledTag"
                    )
                    lines[i] = updatedLine
                    break
                }
            }

            // Inactive timestamp formatted like Org: [2026-10-12 Mon 11:45]
            val logDateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val logDayStr = now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            val logTimeStr = now.format(DateTimeFormatter.ofPattern("HH:mm"))
            val inactiveTimestamp = "[$logDateStr $logDayStr $logTimeStr]"

            // 2. Update :PROPERTIES: drawer with :LAST_REPEAT:
            var propStart = -1
            var propEnd = -1
            for (i in (headlineIndex + 1) until nextHeadlineIndex) {
                val trimmed = lines[i].trim()
                if (trimmed.equals(":PROPERTIES:", ignoreCase = true)) {
                    propStart = i
                } else if (propStart != -1 && trimmed.equals(":END:", ignoreCase = true)) {
                    propEnd = i
                    break
                }
            }

            if (propStart != -1 && propEnd != -1) {
                var lastRepeatIndex = -1
                for (i in (propStart + 1) until propEnd) {
                    if (lines[i].trim().startsWith(":LAST_REPEAT:", ignoreCase = true)) {
                        lastRepeatIndex = i
                        break
                    }
                }
                if (lastRepeatIndex != -1) {
                    lines[lastRepeatIndex] = ":LAST_REPEAT: $inactiveTimestamp"
                } else {
                    lines.add(propStart + 1, ":LAST_REPEAT: $inactiveTimestamp")
                    nextHeadlineIndex++
                    propEnd++
                }
            } else {
                // Insert new :PROPERTIES: drawer right after planning line / headline
                val insertAt = if (scheduledLineIndex != -1) scheduledLineIndex + 1 else headlineIndex + 1
                lines.add(insertAt, ":PROPERTIES:")
                lines.add(insertAt + 1, ":LAST_REPEAT: $inactiveTimestamp")
                lines.add(insertAt + 2, ":END:")
                nextHeadlineIndex += 3
            }

            // 3. Update :LOGBOOK: drawer with new state log entry
            var logbookStart = -1
            for (i in (headlineIndex + 1) until nextHeadlineIndex) {
                val trimmed = lines[i].trim()
                if (trimmed.equals(":LOGBOOK:", ignoreCase = true)) {
                    logbookStart = i
                    break
                }
            }

            val newLogEntry = "- State \"DONE\"       from \"${task.todoKeyword}\"       $inactiveTimestamp"

            if (logbookStart != -1) {
                // Insert at the top of the LOGBOOK drawer right after :LOGBOOK:
                lines.add(logbookStart + 1, newLogEntry)
            } else {
                // Find where to place the new :LOGBOOK: drawer:
                // Typically placed right after the :PROPERTIES: drawer (or after planning line)
                var insertLogbookAt = -1
                for (i in (headlineIndex + 1) until nextHeadlineIndex) {
                    if (lines[i].trim().equals(":END:", ignoreCase = true)) {
                        insertLogbookAt = i + 1
                        break
                    }
                }
                if (insertLogbookAt == -1) {
                    insertLogbookAt = if (scheduledLineIndex != -1) scheduledLineIndex + 1 else headlineIndex + 1
                }
                lines.add(insertLogbookAt, ":LOGBOOK:")
                lines.add(insertLogbookAt + 1, newLogEntry)
                lines.add(insertLogbookAt + 2, ":END:")
            }

            val updatedContent = lines.joinToString(lineEnding) + (if (endsWithNewline) lineEnding else "")
            file.writeText(updatedContent, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Calculates the next date for an Org repeater cookie.
     */
    fun calculateNextScheduledDate(
        currentDate: LocalDate,
        repeater: RepeaterInfo,
        today: LocalDate = LocalDate.now()
    ): LocalDate {
        return when (repeater.type) {
            "+" -> addStep(currentDate, repeater.value, repeater.unit)
            "++" -> {
                // Catch-up: shifts from scheduled date until it is strictly in the future
                var next = addStep(currentDate, repeater.value, repeater.unit)
                while (!next.isAfter(today)) {
                    next = addStep(next, repeater.value, repeater.unit)
                }
                next
            }
            ".+" -> {
                // Restart from today
                addStep(today, repeater.value, repeater.unit)
            }
            else -> addStep(currentDate, repeater.value, repeater.unit)
        }
    }

    private fun addStep(date: LocalDate, value: Long, unit: Char): LocalDate {
        return when (unit.lowercaseChar()) {
            'd' -> date.plusDays(value)
            'w' -> date.plusWeeks(value)
            'm' -> date.plusMonths(value)
            'y' -> date.plusYears(value)
            else -> date.plusDays(value)
        }
    }

    /**
     * In-place rewrites the headline keyword back to [newKeyword] (e.g. for undo or normal completion).
     */
    fun rewriteTaskKeyword(task: RoamTask, newKeyword: String): Boolean {
        val file = File(task.filePath)
        if (!file.exists() || !file.isFile) return false

        return try {
            val rawText = file.readText(Charsets.UTF_8)
            val lineEnding = if (rawText.contains("\r\n")) "\r\n" else "\n"
            val endsWithNewline = rawText.endsWith("\n") || rawText.endsWith("\r\n")

            val lines = rawText.split(Regex("""\r\n|\r|\n""")).toMutableList()
            if (endsWithNewline && lines.isNotEmpty() && lines.last().isEmpty()) {
                lines.removeAt(lines.lastIndex)
            }

            val targetIndex = task.lineNumber - 1
            val lineToUpdateIndex = if (targetIndex in lines.indices && isMatchingTaskLine(lines[targetIndex], task)) {
                targetIndex
            } else {
                lines.indexOfFirst { isMatchingTaskLine(it, task) }
            }

            if (lineToUpdateIndex == -1) {
                return false
            }

            val originalLine = lines[lineToUpdateIndex]
            val updatedLine = originalLine.replaceFirst(
                Regex("""^(\s*\*+\s+)(TODO|NEXT|DONE)(\s+)"""),
                "$1$newKeyword$3"
            )

            lines[lineToUpdateIndex] = updatedLine

            val updatedContent = lines.joinToString(lineEnding) + (if (endsWithNewline) lineEnding else "")
            file.writeText(updatedContent, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun isMatchingTaskLine(line: String, task: RoamTask): Boolean {
        val match = HEADLINE_REGEX.find(line) ?: return false
        val priority = match.groupValues[3].firstOrNull() ?: return false
        val title = match.groupValues[4].trim()
        return priority == task.priority && title == task.title.trim()
    }
}
