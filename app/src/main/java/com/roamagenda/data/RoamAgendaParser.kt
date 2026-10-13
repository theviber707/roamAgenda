/*
 * SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.roamagenda.data

import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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
 * Represents a child checklist item / step under an Org headline.
 *
 * @property lineNumber 1-based line number of the checklist item in the file.
 * @property isChecked Whether the step is checked ([X]).
 * @property text Clean display text (stripped of timestamp and unwrapped org links).
 * @property time Optional scheduled/target time string (e.g. "07:00") extracted from step timestamp.
 * @property rawText Full raw line in the file.
 */
data class TaskStep(
    val lineNumber: Int,
    val isChecked: Boolean,
    val text: String,
    val time: String? = null,
    val rawText: String
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
 * @property steps Checklist items/steps belonging to this task.
 * @property isClockedIn True if an open CLOCK: entry is currently running on this task.
 * @property clockStartTime The start timestamp of the currently running clock, if any.
 * @property totalClockedMinutes Cumulative closed clock duration in minutes recorded on this task.
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
    val steps: List<TaskStep> = emptyList(),
    val isClockedIn: Boolean = false,
    val clockStartTime: LocalDateTime? = null,
    val totalClockedMinutes: Long = 0L,
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
     * Matches active planning or timestamp line (SCHEDULED:, DEADLINE:, or bare active timestamp).
     * Group 1: Date (YYYY-MM-DD)
     * Group 2: Time (HH:MM), optional
     * Group 3: Repeater type (+, ++, .+)
     * Group 4: Repeater value (digits)
     * Group 5: Repeater unit (h, d, w, m, y)
     *
     * Inactive timestamps using square brackets `[...]` are strictly excluded, as they
     * do not produce agenda items in Org Mode.
     */
    val ACTIVE_TIMESTAMP_REGEX = Regex(
        """^\s*(?:(?:SCHEDULED|DEADLINE):\s*<|<)(\d{4}-\d{2}-\d{2})(?:\s+[A-Za-z]+)?(?:\s+(\d{1,2}:\d{2}))?(?:\s*(\+\+|\+|\.\+)(\d+)([hdwmy]))?[^>]*>"""
    )

    /**
     * Backward-compatible alias for [ACTIVE_TIMESTAMP_REGEX].
     */
    val SCHEDULED_REGEX = ACTIVE_TIMESTAMP_REGEX

    /**
     * Matches Org Mode checklist item lines (e.g. `- [ ] Item`, `- [X] Done item`).
     * Group 1: Bullet marker with indent (e.g. `-`, `+`, `*`)
     * Group 2: Check state character (` ` for unchecked, `X` or `x` for checked, `-` for intermediate)
     * Group 3: Item content/description
     */
    val CHECKLIST_REGEX = Regex("""^(\s*[-+*])\s+\[([ Xx-])\]\s+(.*)""")

    /**
     * Matches inline timestamps inside step items (e.g. `<2026-10-13 Tue 07:00>` or `<07:00>`).
     * Group 1: Extracted time (HH:MM), if present
     */
    val STEP_TIMESTAMP_REGEX = Regex("""<(?:\d{4}-\d{2}-\d{2})?(?:\s+[A-Za-z]+)?(?:\s+(\d{1,2}:\d{2}))?[^>]*>""")

    /**
     * Matches an open, currently running Org Mode clock line:
     * `CLOCK: [2026-10-13 Tue 12:00]`
     */
    val OPEN_CLOCK_REGEX = Regex(
        """^\s*CLOCK:\s*\[(\d{4}-\d{2}-\d{2})\s+[^\d\]]+\s+(\d{1,2}:\d{2})\]\s*$"""
    )

    /**
     * Matches a closed Org Mode clock entry:
     * `CLOCK: [2025-04-11 Fri 16:58]--[2025-04-11 Fri 17:32] =>  0:34`
     */
    val CLOSED_CLOCK_REGEX = Regex(
        """^\s*CLOCK:\s*\[(\d{4}-\d{2}-\d{2})\s+[^\d\]]+\s+(\d{1,2}:\d{2})\]--\[(\d{4}-\d{2}-\d{2})\s+[^\d\]]+\s+(\d{1,2}:\d{2})\]\s*=>\s*(\d+):(\d{2})"""
    )

    /**
     * Formats an Org headline title for user display by unwrapping Org Mode links:
     * `[[url][description]]` -> `description`
     * `[[url]]` -> `url`
     */
    fun formatOrgTitle(title: String): String {
        return title
            .replace(Regex("""\[\[[^\]]+\]\[([^\]]+)\]\]""")) { it.groupValues[1] }
            .replace(Regex("""\[\[([^\]]+)\]\]""")) { it.groupValues[1] }
    }

    /**
     * Recursively traverses [rootDir] for `.org` files, filters those with `:agenda:` filetag,
     * and extracts all prioritized TODO/NEXT tasks.
     *
     * Automatically skips hidden directories (e.g. `.stversions`, `.git`, `.stfolder`),
     * hidden files, Syncthing sync-conflict files, Org archive files, and editor backups.
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
                .onEnter { dir -> !dir.name.startsWith(".") }
                .filter { file ->
                    file.isFile &&
                    !file.name.startsWith(".") &&
                    !file.name.startsWith("#") &&
                    !file.name.endsWith("~") &&
                    !file.name.contains(".sync-conflict-") &&
                    !file.name.endsWith(".org_archive", ignoreCase = true) &&
                    file.extension.equals("org", ignoreCase = true)
                }
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

            // Look ahead for planning info, checklist steps, and clock entries under this headline
            var scheduledDate: LocalDate? = null
            var scheduledTime: String? = null
            var repeaterInfo: RepeaterInfo? = null
            val steps = mutableListOf<TaskStep>()
            var isClockedIn = false
            var clockStartTime: LocalDateTime? = null
            var totalClockedMinutes = 0L

            var inDrawer = false
            var lookahead = index + 1
            while (lookahead < lines.size) {
                val nextLine = lines[lookahead]
                val trimmedNext = nextLine.trim()
                if (trimmedNext.startsWith("*")) break // Reached next headline

                if (trimmedNext.equals(":END:", ignoreCase = true)) {
                    inDrawer = false
                } else if (trimmedNext.startsWith(":") && trimmedNext.endsWith(":") && trimmedNext.length > 2) {
                    inDrawer = true
                }

                // Check for clock entries
                val openClockMatch = OPEN_CLOCK_REGEX.find(nextLine)
                if (openClockMatch != null) {
                    try {
                        val dStr = openClockMatch.groupValues[1]
                        val tStr = openClockMatch.groupValues[2]
                        val paddedTimeStr = if (tStr.length == 4) "0$tStr" else tStr
                        clockStartTime = LocalDateTime.of(LocalDate.parse(dStr), LocalTime.parse(paddedTimeStr))
                        isClockedIn = true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    val closedClockMatch = CLOSED_CLOCK_REGEX.find(nextLine)
                    if (closedClockMatch != null) {
                        try {
                            val hours = closedClockMatch.groupValues[5].toLong()
                            val minutes = closedClockMatch.groupValues[6].toLong()
                            totalClockedMinutes += (hours * 60 + minutes)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                // Check for planning / active timestamp line (if not already found)
                if (scheduledDate == null) {
                    val schedMatch = ACTIVE_TIMESTAMP_REGEX.find(nextLine)
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
                    }
                }

                // Check for checklist items (outside drawers)
                if (!inDrawer) {
                    val chkMatch = CHECKLIST_REGEX.find(nextLine)
                    if (chkMatch != null) {
                        val state = chkMatch.groupValues[2]
                        val isChecked = state.equals("X", ignoreCase = true)
                        val rawContent = chkMatch.groupValues[3].trim()

                        val timeMatch = STEP_TIMESTAMP_REGEX.find(rawContent)
                        val stepTime = timeMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                        val cleanText = formatOrgTitle(rawContent.replace(STEP_TIMESTAMP_REGEX, "").trim())

                        steps.add(
                            TaskStep(
                                lineNumber = lookahead + 1,
                                isChecked = isChecked,
                                text = cleanText,
                                time = stepTime,
                                rawText = nextLine
                            )
                        )
                    }
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
                    repeater = repeaterInfo,
                    steps = steps,
                    isClockedIn = isClockedIn,
                    clockStartTime = clockStartTime,
                    totalClockedMinutes = totalClockedMinutes
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
        // If this task has an active clock, clock it out first (org-clock-out-when-done)
        clockOut(task, now)

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
                if (ACTIVE_TIMESTAMP_REGEX.containsMatchIn(lines[i])) {
                    scheduledLineIndex = i
                    val indent = lines[i].takeWhile { it.isWhitespace() }
                    val prefix = when {
                        lines[i].contains("SCHEDULED:") -> "SCHEDULED: "
                        lines[i].contains("DEADLINE:") -> "DEADLINE: "
                        else -> ""
                    }
                    lines[i] = "$indent$prefix$nextScheduledTag"
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

            // 4. Reset child checklist items to unchecked for next repeating cycle
            var currentNextHeadline = lines.size
            for (i in (headlineIndex + 1) until lines.size) {
                if (lines[i].trim().startsWith("*")) {
                    currentNextHeadline = i
                    break
                }
            }

            var totalSteps = 0
            for (i in (headlineIndex + 1) until currentNextHeadline) {
                if (CHECKLIST_REGEX.containsMatchIn(lines[i])) {
                    totalSteps++
                    lines[i] = lines[i].replaceFirst(Regex("""\[[Xx-]\]"""), "[ ]")
                }
            }
            if (totalSteps > 0) {
                lines[headlineIndex] = lines[headlineIndex]
                    .replaceFirst(Regex("""\[(?:\d+/\d+|/)\]"""), "[0/$totalSteps]")
                    .replaceFirst(Regex("""\[(?:\d+%|%)\]"""), "[0%]")
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
     * Toggles the completion state of a child checklist item / step under [task].
     *
     * 1. Updates `[ ]` to `[X]` (or vice versa) on the step line in [task.filePath].
     * 2. Recalculates and updates the headline's progress cookie (e.g. `[1/5]` -> `[2/5]`).
     *
     * @return true if successfully modified on disk, false otherwise.
     */
    fun toggleTaskStep(
        task: RoamTask,
        step: TaskStep,
        newChecked: Boolean
    ): Boolean {
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

            // Find parent headline first
            val headlineTargetIndex = task.lineNumber - 1
            val headlineIndex = if (headlineTargetIndex in lines.indices && isMatchingTaskLine(lines[headlineTargetIndex], task)) {
                headlineTargetIndex
            } else {
                lines.indexOfFirst { isMatchingTaskLine(it, task) }
            }

            var nextHeadlineIndex = lines.size
            if (headlineIndex != -1) {
                for (i in (headlineIndex + 1) until lines.size) {
                    if (lines[i].trim().startsWith("*")) {
                        nextHeadlineIndex = i
                        break
                    }
                }
            }

            // Locate the step line
            val targetIndex = step.lineNumber - 1
            val stepLineIndex = if (targetIndex in (headlineIndex + 1) until nextHeadlineIndex &&
                (lines[targetIndex] == step.rawText || (CHECKLIST_REGEX.containsMatchIn(lines[targetIndex]) && lines[targetIndex].contains(step.text)))
            ) {
                targetIndex
            } else {
                val searchRange = if (headlineIndex != -1) (headlineIndex + 1) until nextHeadlineIndex else lines.indices
                searchRange.firstOrNull { i ->
                    val line = lines[i]
                    CHECKLIST_REGEX.containsMatchIn(line) && line.contains(step.text)
                } ?: -1
            }

            if (stepLineIndex == -1) return false

            val currentLine = lines[stepLineIndex]
            val updatedStepLine = if (newChecked) {
                currentLine.replaceFirst(Regex("""\[[ -]\]"""), "[X]")
            } else {
                currentLine.replaceFirst(Regex("""\[[Xx-]\]"""), "[ ]")
            }
            lines[stepLineIndex] = updatedStepLine

            // Recalculate progress cookie on headline if present
            if (headlineIndex != -1) {
                var totalSteps = 0
                var checkedSteps = 0
                for (i in (headlineIndex + 1) until nextHeadlineIndex) {
                    val m = CHECKLIST_REGEX.find(lines[i])
                    if (m != null) {
                        totalSteps++
                        val st = m.groupValues[2]
                        if (st.equals("X", ignoreCase = true)) {
                            checkedSteps++
                        }
                    }
                }

                if (totalSteps > 0) {
                    val headline = lines[headlineIndex]
                    val updatedHeadline = headline
                        .replaceFirst(Regex("""\[(?:\d+/\d+|/)\]"""), "[$checkedSteps/$totalSteps]")
                        .replaceFirst(Regex("""\[(?:\d+%|%)\]"""), "[${(checkedSteps * 100) / totalSteps}%]")
                    lines[headlineIndex] = updatedHeadline
                }
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
     * Clocks in to [task] by appending a new active `CLOCK: [YYYY-MM-DD Day HH:MM]`
     * entry at the top of the task's `:LOGBOOK:` drawer.
     */
    fun clockIn(
        task: RoamTask,
        now: LocalDateTime = LocalDateTime.now()
    ): Boolean {
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
            val headlineIndex = if (targetIndex in lines.indices && isMatchingTaskLine(lines[targetIndex], task)) {
                targetIndex
            } else {
                lines.indexOfFirst { isMatchingTaskLine(it, task) }
            }

            if (headlineIndex == -1) return false

            var nextHeadlineIndex = lines.size
            for (i in (headlineIndex + 1) until lines.size) {
                if (lines[i].trim().startsWith("*")) {
                    nextHeadlineIndex = i
                    break
                }
            }

            // Check if there is already an open clock under this headline
            for (i in (headlineIndex + 1) until nextHeadlineIndex) {
                if (OPEN_CLOCK_REGEX.containsMatchIn(lines[i])) {
                    return true
                }
            }

            val dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val dayStr = now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            val timeStr = now.format(DateTimeFormatter.ofPattern("HH:mm"))
            val clockLine = "CLOCK: [$dateStr $dayStr $timeStr]"

            // Locate existing :LOGBOOK: drawer
            var logbookStart = -1
            for (i in (headlineIndex + 1) until nextHeadlineIndex) {
                if (lines[i].trim().equals(":LOGBOOK:", ignoreCase = true)) {
                    logbookStart = i
                    break
                }
            }

            if (logbookStart != -1) {
                val indent = lines[logbookStart].takeWhile { it.isWhitespace() }
                lines.add(logbookStart + 1, "${indent}$clockLine")
            } else {
                // Find insertion point for new :LOGBOOK: drawer
                var insertAt = -1
                for (i in (headlineIndex + 1) until nextHeadlineIndex) {
                    if (lines[i].trim().equals(":END:", ignoreCase = true)) {
                        insertAt = i + 1
                        break
                    }
                }
                if (insertAt == -1) {
                    for (i in (headlineIndex + 1) until nextHeadlineIndex) {
                        if (ACTIVE_TIMESTAMP_REGEX.containsMatchIn(lines[i])) {
                            insertAt = i + 1
                            break
                        }
                    }
                }
                if (insertAt == -1) {
                    insertAt = headlineIndex + 1
                }

                val indent = lines[headlineIndex].takeWhile { it.isWhitespace() }
                lines.add(insertAt, "${indent}:LOGBOOK:")
                lines.add(insertAt + 1, "${indent}$clockLine")
                lines.add(insertAt + 2, "${indent}:END:")
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
     * Clocks out of [task] by closing any active `CLOCK: [start]` line under the headline
     * with `--[end] => H:MM`.
     */
    fun clockOut(
        task: RoamTask,
        now: LocalDateTime = LocalDateTime.now()
    ): Boolean {
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
            val headlineIndex = if (targetIndex in lines.indices && isMatchingTaskLine(lines[targetIndex], task)) {
                targetIndex
            } else {
                lines.indexOfFirst { isMatchingTaskLine(it, task) }
            }

            if (headlineIndex == -1) return false

            var nextHeadlineIndex = lines.size
            for (i in (headlineIndex + 1) until lines.size) {
                if (lines[i].trim().startsWith("*")) {
                    nextHeadlineIndex = i
                    break
                }
            }

            var openClockIndex = -1
            var openMatch: MatchResult? = null
            for (i in (headlineIndex + 1) until nextHeadlineIndex) {
                val match = OPEN_CLOCK_REGEX.find(lines[i])
                if (match != null) {
                    openClockIndex = i
                    openMatch = match
                    break
                }
            }

            if (openClockIndex == -1 || openMatch == null) return false

            val startDateStr = openMatch.groupValues[1]
            val startTimeStr = openMatch.groupValues[2]
            val paddedStartTimeStr = if (startTimeStr.length == 4) "0$startTimeStr" else startTimeStr
            val startDateTime = LocalDateTime.of(LocalDate.parse(startDateStr), LocalTime.parse(paddedStartTimeStr))

            val duration = Duration.between(startDateTime, now)
            val totalMinutes = duration.toMinutes().coerceAtLeast(0)
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            val durationStr = String.format(Locale.ROOT, "%2d:%02d", hours, minutes)

            val endDateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val endDayStr = now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            val endTimeStr = now.format(DateTimeFormatter.ofPattern("HH:mm"))

            val indent = lines[openClockIndex].takeWhile { it.isWhitespace() }
            val rawLine = lines[openClockIndex]
            val startBracket = rawLine.substringAfter("CLOCK:").trim()

            val closedLine = "${indent}CLOCK: $startBracket--[$endDateStr $endDayStr $endTimeStr] => $durationStr"
            lines[openClockIndex] = closedLine

            val updatedContent = lines.joinToString(lineEnding) + (if (endsWithNewline) lineEnding else "")
            file.writeText(updatedContent, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Cancels an active clock on [task] by removing the open `CLOCK:` entry without recording duration.
     */
    fun cancelClock(task: RoamTask): Boolean {
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
            val headlineIndex = if (targetIndex in lines.indices && isMatchingTaskLine(lines[targetIndex], task)) {
                targetIndex
            } else {
                lines.indexOfFirst { isMatchingTaskLine(it, task) }
            }

            if (headlineIndex == -1) return false

            var nextHeadlineIndex = lines.size
            for (i in (headlineIndex + 1) until lines.size) {
                if (lines[i].trim().startsWith("*")) {
                    nextHeadlineIndex = i
                    break
                }
            }

            var openClockIndex = -1
            for (i in (headlineIndex + 1) until nextHeadlineIndex) {
                if (OPEN_CLOCK_REGEX.containsMatchIn(lines[i])) {
                    openClockIndex = i
                    break
                }
            }

            if (openClockIndex == -1) return false

            lines.removeAt(openClockIndex)

            val updatedContent = lines.joinToString(lineEnding) + (if (endsWithNewline) lineEnding else "")
            file.writeText(updatedContent, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
        val cleanCurrent = title.replace(Regex("""\[(?:\d+/\d+|\d+%|/|%)\]"""), "").trim()
        val cleanTask = task.title.replace(Regex("""\[(?:\d+/\d+|\d+%|/|%)\]"""), "").trim()
        return priority == task.priority && cleanCurrent == cleanTask
    }
}
