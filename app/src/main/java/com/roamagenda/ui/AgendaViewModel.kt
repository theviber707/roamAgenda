/*
 * SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.roamagenda.ui

import android.app.Application
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roamagenda.data.AgendaPreferences
import com.roamagenda.data.ParseResult
import com.roamagenda.data.RoamAgendaParser
import com.roamagenda.data.RoamTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Domain error representations for user display.
 */
sealed interface AgendaError {
    data class DirectoryNotFound(val path: String) : AgendaError
    data class NotADirectory(val path: String) : AgendaError
    data class NoOrgFilesFound(val path: String) : AgendaError
    data class Generic(val message: String) : AgendaError
}

/**
 * Snapshot of file before a task was completed, allowing exact undo.
 */
data class CompletedTaskRecord(
    val task: RoamTask,
    val previousFileContent: String
)

/**
 * UI State for the Roam Agenda screen.
 */
data class AgendaUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasStoragePermission: Boolean = false,
    val rootDirectoryPath: String = AgendaPreferences.DEFAULT_PATH,
    val showFutureTasks: Boolean = false,
    val tasksByPriority: Map<Char, List<RoamTask>> = emptyMap(),
    val rawTasks: List<RoamTask> = emptyList(),
    val totalTaskCount: Int = 0,
    val totalFilesScanned: Int = 0,
    val agendaFilesCount: Int = 0,
    val error: AgendaError? = null,
    val lastCompletedRecord: CompletedTaskRecord? = null
)

/**
 * One-off events (e.g. snackbars, toasts).
 */
sealed interface AgendaUiEvent {
    data class TaskCompleted(val task: RoamTask) : AgendaUiEvent
    data class TaskUndone(val task: RoamTask) : AgendaUiEvent
    data class ShowMessage(val message: String) : AgendaUiEvent
}

class AgendaViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val preferences = AgendaPreferences(application)

    private val _uiState = MutableStateFlow(
        AgendaUiState(hasStoragePermission = isStoragePermissionGranted())
    )
    val uiState: StateFlow<AgendaUiState> = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<AgendaUiEvent>()
    val uiEvents: SharedFlow<AgendaUiEvent> = _uiEvents.asSharedFlow()

    init {
        // Collect saved directory path and re-scan when it changes
        viewModelScope.launch {
            preferences.orgDirectoryPathFlow
                .distinctUntilChanged()
                .collect { path ->
                    _uiState.update { it.copy(rootDirectoryPath = path) }
                    if (checkStoragePermission()) {
                        refreshTasks(isSilent = false)
                    }
                }
        }
    }

    /**
     * Checks whether MANAGE_EXTERNAL_STORAGE (API 30+) or legacy storage permission is granted.
     */
    fun checkStoragePermission(): Boolean {
        val granted = isStoragePermissionGranted()
        _uiState.update { it.copy(hasStoragePermission = granted) }
        return granted
    }

    companion object {
        fun isStoragePermissionGranted(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        }
    }

    /**
     * Called when the app returns from system settings or resumes.
     */
    fun onResumeCheckPermission() {
        val wasGranted = _uiState.value.hasStoragePermission
        val isGranted = checkStoragePermission()
        if (!wasGranted && isGranted) {
            refreshTasks(isSilent = false)
        }
    }

    /**
     * Toggles whether to display future scheduled tasks or only due/unscheduled tasks.
     */
    fun toggleShowFutureTasks() {
        _uiState.update { current ->
            val newShowFuture = !current.showFutureTasks
            val visible = filterVisibleTasks(current.rawTasks, newShowFuture)
            current.copy(
                showFutureTasks = newShowFuture,
                tasksByPriority = visible.groupBy { it.priority }.toSortedMap(),
                totalTaskCount = visible.size
            )
        }
    }

    private fun filterVisibleTasks(tasks: List<RoamTask>, showFuture: Boolean): List<RoamTask> {
        if (showFuture) return tasks
        val today = LocalDate.now()
        return tasks.filter { it.scheduledDate == null || !it.scheduledDate.isAfter(today) }
    }

    /**
     * Refreshes the task list by traversing the configured directory.
     *
     * @param isSilent If true, updates [AgendaUiState.isRefreshing] instead of full-screen loading.
     */
    fun refreshTasks(isSilent: Boolean = false) {
        if (!_uiState.value.hasStoragePermission) {
            return
        }

        val dirPath = _uiState.value.rootDirectoryPath

        viewModelScope.launch {
            if (isSilent) {
                _uiState.update { it.copy(isRefreshing = true, error = null) }
            } else {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }

            val result = withContext(Dispatchers.IO) {
                RoamAgendaParser.scanDirectory(File(dirPath))
            }

            when (result) {
                is ParseResult.Success -> {
                    val showFuture = _uiState.value.showFutureTasks
                    val visible = filterVisibleTasks(result.tasks, showFuture)
                    val grouped = visible.groupBy { it.priority }.toSortedMap()

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            rawTasks = result.tasks,
                            tasksByPriority = grouped,
                            totalTaskCount = visible.size,
                            totalFilesScanned = result.totalFilesScanned,
                            agendaFilesCount = result.agendaFilesFound,
                            error = null
                        )
                    }
                }

                is ParseResult.DirectoryNotFound -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            rawTasks = emptyList(),
                            tasksByPriority = emptyMap(),
                            totalTaskCount = 0,
                            error = AgendaError.DirectoryNotFound(result.path)
                        )
                    }
                }

                is ParseResult.NotADirectory -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            rawTasks = emptyList(),
                            tasksByPriority = emptyMap(),
                            totalTaskCount = 0,
                            error = AgendaError.NotADirectory(result.path)
                        )
                    }
                }

                is ParseResult.NoOrgFilesFound -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            rawTasks = emptyList(),
                            tasksByPriority = emptyMap(),
                            totalTaskCount = 0,
                            error = AgendaError.NoOrgFilesFound(result.path)
                        )
                    }
                }

                is ParseResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            rawTasks = emptyList(),
                            tasksByPriority = emptyMap(),
                            totalTaskCount = 0,
                            error = AgendaError.Generic(result.message)
                        )
                    }
                }
            }
        }
    }

    /**
     * Updates the Org-Roam root directory path and re-scans.
     */
    fun updateDirectoryPath(newPath: String) {
        val trimmed = newPath.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            preferences.setOrgDirectoryPath(trimmed)
            _uiState.update { it.copy(rootDirectoryPath = trimmed) }
            refreshTasks(isSilent = false)
        }
    }

    /**
     * Marks a task as DONE (or advances recurring task) in-place on disk.
     */
    fun markTaskDone(task: RoamTask) {
        viewModelScope.launch {
            // Snapshot file content for lossless undo
            val file = File(task.filePath)
            val previousContent = withContext(Dispatchers.IO) {
                if (file.exists() && file.isFile) {
                    try { file.readText(Charsets.UTF_8) } catch (e: Exception) { null }
                } else null
            }
            val record = previousContent?.let { CompletedTaskRecord(task, it) }

            // Optimistic update
            val currentGrouped = _uiState.value.tasksByPriority.toMutableMap()
            val listForPriority = currentGrouped[task.priority]?.filterNot { it.id == task.id } ?: emptyList()
            if (listForPriority.isEmpty()) {
                currentGrouped.remove(task.priority)
            } else {
                currentGrouped[task.priority] = listForPriority
            }

            _uiState.update {
                it.copy(
                    tasksByPriority = currentGrouped,
                    totalTaskCount = (it.totalTaskCount - 1).coerceAtLeast(0),
                    lastCompletedRecord = record
                )
            }

            val success = withContext(Dispatchers.IO) {
                RoamAgendaParser.markTaskDone(task)
            }

            if (success) {
                _uiEvents.emit(AgendaUiEvent.TaskCompleted(task))
                refreshTasks(isSilent = true)
            } else {
                _uiEvents.emit(AgendaUiEvent.ShowMessage("Failed to update task in ${task.filePath}"))
                refreshTasks(isSilent = true)
            }
        }
    }

    /**
     * Restores the exact prior state of the file before last completion.
     */
    fun undoLastCompletion() {
        val record = _uiState.value.lastCompletedRecord ?: return

        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val file = File(record.task.filePath)
                    file.writeText(record.previousFileContent, Charsets.UTF_8)
                    true
                } catch (e: Exception) {
                    false
                }
            }

            if (success) {
                _uiState.update { it.copy(lastCompletedRecord = null) }
                _uiEvents.emit(AgendaUiEvent.TaskUndone(record.task))
                refreshTasks(isSilent = true)
            } else {
                _uiEvents.emit(AgendaUiEvent.ShowMessage("Failed to undo task"))
            }
        }
    }
}
