/*
 * SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.roamagenda.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roamagenda.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roamagenda.data.RoamTask
import com.roamagenda.ui.components.DirectoryDialog
import com.roamagenda.ui.components.PermissionPrompt
import com.roamagenda.ui.components.PriorityHeader
import com.roamagenda.ui.components.TaskCard
import com.roamagenda.ui.components.formatElapsedSeconds
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AgendaScreen(
    viewModel: AgendaViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.onNotificationPermissionGranted()
        }
    }
    var showDirectoryDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Listen for UI events (such as task completion with Undo action)
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is AgendaUiEvent.TaskCompleted -> {
                    val displayTitle = com.roamagenda.data.RoamAgendaParser.formatOrgTitle(event.task.title)
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.completed_task, displayTitle),
                        actionLabel = context.getString(R.string.undo),
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.undoLastCompletion()
                    }
                }
                is AgendaUiEvent.TaskUndone -> {
                    val displayTitle = com.roamagenda.data.RoamAgendaParser.formatOrgTitle(event.task.title)
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.restored_task, displayTitle),
                        duration = SnackbarDuration.Short
                    )
                }
                is AgendaUiEvent.StringResMessage -> {
                    val message = context.getString(event.messageRes, *event.formatArgs.toTypedArray())
                    snackbarHostState.showSnackbar(
                        message = message,
                        duration = SnackbarDuration.Short
                    )
                }
                is AgendaUiEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    if (showDirectoryDialog) {
        DirectoryDialog(
            currentPath = uiState.rootDirectoryPath,
            onDismiss = { showDirectoryDialog = false },
            onSavePath = { newPath ->
                viewModel.updateDirectoryPath(newPath)
                showDirectoryDialog = false
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        if (uiState.hasStoragePermission && uiState.error == null) {
                            val filterMode = if (uiState.showFutureTasks) stringResource(R.string.filter_all) else stringResource(R.string.filter_due)
                            val subtitle = stringResource(R.string.agenda_subtitle, uiState.totalTaskCount, filterMode, uiState.agendaFilesCount)
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.toggleShowFutureTasks() },
                        enabled = uiState.hasStoragePermission
                    ) {
                        Icon(
                            imageVector = if (uiState.showFutureTasks) Icons.Default.EventAvailable else Icons.Default.Today,
                            contentDescription = if (uiState.showFutureTasks) stringResource(R.string.filter_desc_show_due) else stringResource(R.string.filter_desc_show_all),
                            tint = if (uiState.showFutureTasks) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showDirectoryDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = stringResource(R.string.configure_folder),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = { viewModel.refreshTasks(isSilent = false) },
                        enabled = uiState.hasStoragePermission && !uiState.isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh_agenda)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                // 1. Permission check
                !uiState.hasStoragePermission -> {
                    PermissionPrompt(
                        onPermissionGrantedCheck = { viewModel.onResumeCheckPermission() }
                    )
                }

                // 2. Main content with pull-to-refresh
                else -> {
                    val pullRefreshState = rememberPullToRefreshState()

                    Column(modifier = Modifier.fillMaxSize()) {
                        AnimatedVisibility(
                            visible = uiState.activeClockedTask != null,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            uiState.activeClockedTask?.let { clockedTask ->
                                ActiveClockBanner(
                                    task = clockedTask,
                                    onClockOutAndDone = { viewModel.markTaskDone(clockedTask) },
                                    onClockOut = { viewModel.clockOut(clockedTask) },
                                    onCancelClock = { viewModel.cancelClock(clockedTask) }
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            PullToRefreshBox(
                                isRefreshing = uiState.isRefreshing,
                                onRefresh = { viewModel.refreshTasks(isSilent = true) },
                                state = pullRefreshState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 24.dp)
                                ) {
                                    // Error Banner item (directory not found or no org files)
                                    if (uiState.error != null) {
                                        item {
                                            ErrorBanner(
                                                error = uiState.error!!,
                                                onConfigurePath = { showDirectoryDialog = true },
                                                onRetry = { viewModel.refreshTasks(isSilent = false) }
                                            )
                                        }
                                    }

                                    // Empty state item
                                    if (uiState.error == null && !uiState.isLoading && uiState.totalTaskCount == 0) {
                                        item {
                                            EmptyStateView(
                                                agendaFilesCount = uiState.agendaFilesCount,
                                                onRefresh = { viewModel.refreshTasks(isSilent = false) }
                                            )
                                        }
                                    }

                                    // Grouped Tasks by Priority
                                    uiState.tasksByPriority.forEach { (priority, tasks) ->
                                        val isCollapsed = priority in uiState.collapsedPriorities
                                        stickyHeader(key = "header_$priority") {
                                            PriorityHeader(
                                                priority = priority,
                                                taskCount = tasks.size,
                                                isCollapsed = isCollapsed,
                                                onToggleCollapse = { viewModel.togglePriorityCollapse(priority) }
                                            )
                                        }

                                        if (!isCollapsed) {
                                            items(
                                                items = tasks,
                                                key = { it.id }
                                            ) { task ->
                                                TaskCard(
                                                    task = task,
                                                    onMarkDone = { viewModel.markTaskDone(it) },
                                                    onToggleStep = { t, s, checked -> viewModel.toggleTaskStep(t, s, checked) },
                                                    onClockIn = {
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                            if (ContextCompat.checkSelfPermission(
                                                                    context,
                                                                    Manifest.permission.POST_NOTIFICATIONS
                                                                ) != PackageManager.PERMISSION_GRANTED
                                                            ) {
                                                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                            }
                                                        }
                                                        viewModel.clockIn(it)
                                                    },
                                                    onClockOut = { viewModel.clockOut(it) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Full-screen loading overlay on initial scan
                            androidx.compose.animation.AnimatedVisibility(
                                visible = uiState.isLoading,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier.align(Alignment.Center)
                            ) {
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                                    modifier = Modifier.padding(32.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(20.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = stringResource(R.string.scanning_org_files),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    error: AgendaError,
    onConfigurePath: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val message = when (error) {
        is AgendaError.DirectoryNotFound ->
            stringResource(R.string.directory_not_found, error.path)
        is AgendaError.NotADirectory ->
            stringResource(R.string.not_a_directory, error.path)
        is AgendaError.NoOrgFilesFound ->
            stringResource(R.string.no_org_files_found, error.path)
        is AgendaError.Generic ->
            error.message
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.directory_issue),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(stringResource(R.string.retry))
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onConfigurePath,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(stringResource(R.string.change_folder))
                }
            }
        }
    }
}

@Composable
private fun EmptyStateView(
    agendaFilesCount: Int,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.no_prioritized_tasks),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        val hint = if (agendaFilesCount > 0) {
            stringResource(R.string.empty_hint_files_found, agendaFilesCount)
        } else {
            stringResource(R.string.empty_hint_no_files)
        }

        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedButton(onClick = onRefresh) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.refresh))
        }
    }
}

@Composable
private fun ActiveClockBanner(
    task: RoamTask,
    onClockOutAndDone: () -> Unit,
    onClockOut: () -> Unit,
    onCancelClock: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clockStartTime = task.clockStartTime
    var elapsedSeconds by remember(clockStartTime) {
        val initial = if (clockStartTime != null) {
            java.time.Duration.between(clockStartTime, java.time.LocalDateTime.now()).seconds.coerceAtLeast(0L)
        } else 0L
        mutableLongStateOf(initial)
    }

    LaunchedEffect(clockStartTime) {
        while (true) {
            if (clockStartTime != null) {
                elapsedSeconds = java.time.Duration.between(clockStartTime, java.time.LocalDateTime.now()).seconds.coerceAtLeast(0L)
            }
            delay(1000L)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = com.roamagenda.data.RoamAgendaParser.formatOrgTitle(task.title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.clocked_banner, formatElapsedSeconds(elapsedSeconds)),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onClockOutAndDone,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.clock_out_and_done),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            FilledTonalButton(
                onClick = onClockOut,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.clock_out_button), style = MaterialTheme.typography.labelMedium)
            }

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(
                onClick = onCancelClock,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel_clock),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

