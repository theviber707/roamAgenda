/*
 * SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.roamagenda.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roamagenda.data.RoamTask
import com.roamagenda.ui.theme.OnPriorityBlueContainerDark
import com.roamagenda.ui.theme.OnPriorityBlueContainerLight
import com.roamagenda.ui.theme.OnPriorityGreenContainerDark
import com.roamagenda.ui.theme.OnPriorityGreenContainerLight
import com.roamagenda.ui.theme.OnPriorityRedContainerDark
import com.roamagenda.ui.theme.OnPriorityRedContainerLight
import com.roamagenda.ui.theme.PriorityBlueContainerDark
import com.roamagenda.ui.theme.PriorityBlueContainerLight
import com.roamagenda.ui.theme.PriorityGreenContainerDark
import com.roamagenda.ui.theme.PriorityGreenContainerLight
import com.roamagenda.ui.theme.PriorityRedContainerDark
import com.roamagenda.ui.theme.PriorityRedContainerLight

data class PriorityColorScheme(
    val containerColor: Color,
    val contentColor: Color,
    val borderColor: Color,
    val badgeContainer: Color,
    val badgeContent: Color
)

@Composable
fun getPriorityColorScheme(priority: Char): PriorityColorScheme {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    return when (priority.uppercaseChar()) {
        'A' -> {
            val container = if (isDark) PriorityRedContainerDark.copy(alpha = 0.35f) else PriorityRedContainerLight.copy(alpha = 0.45f)
            val content = if (isDark) OnPriorityRedContainerDark else OnPriorityRedContainerLight
            val badgeContainer = if (isDark) PriorityRedContainerDark else PriorityRedContainerLight
            PriorityColorScheme(
                containerColor = container,
                contentColor = MaterialTheme.colorScheme.onSurface,
                borderColor = content.copy(alpha = 0.3f),
                badgeContainer = badgeContainer,
                badgeContent = content
            )
        }
        'B' -> {
            val container = if (isDark) PriorityBlueContainerDark.copy(alpha = 0.35f) else PriorityBlueContainerLight.copy(alpha = 0.45f)
            val content = if (isDark) OnPriorityBlueContainerDark else OnPriorityBlueContainerLight
            val badgeContainer = if (isDark) PriorityBlueContainerDark else PriorityBlueContainerLight
            PriorityColorScheme(
                containerColor = container,
                contentColor = MaterialTheme.colorScheme.onSurface,
                borderColor = content.copy(alpha = 0.3f),
                badgeContainer = badgeContainer,
                badgeContent = content
            )
        }
        'C' -> {
            val container = if (isDark) PriorityGreenContainerDark.copy(alpha = 0.35f) else PriorityGreenContainerLight.copy(alpha = 0.45f)
            val content = if (isDark) OnPriorityGreenContainerDark else OnPriorityGreenContainerLight
            val badgeContainer = if (isDark) PriorityGreenContainerDark else PriorityGreenContainerLight
            PriorityColorScheme(
                containerColor = container,
                contentColor = MaterialTheme.colorScheme.onSurface,
                borderColor = content.copy(alpha = 0.3f),
                badgeContainer = badgeContainer,
                badgeContent = content
            )
        }
        else -> {
            PriorityColorScheme(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                badgeContainer = MaterialTheme.colorScheme.surfaceVariant,
                badgeContent = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskCard(
    task: RoamTask,
    onMarkDone: (RoamTask) -> Unit,
    modifier: Modifier = Modifier
) {
    var isChecked by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(true) }
    val colors = getPriorityColorScheme(task.priority)

    AnimatedVisibility(
        visible = isVisible,
        exit = fadeOut() + shrinkVertically()
    ) {
        OutlinedCard(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = colors.containerColor
            ),
            border = BorderStroke(1.dp, colors.borderColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { checked ->
                        if (checked && !isChecked) {
                            isChecked = true
                            // Trigger callback after visual response
                            onMarkDone(task)
                        }
                    },
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Headline Title
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None
                        ),
                        color = colors.contentColor
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Keyword Badge (TODO / NEXT)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (task.todoKeyword == "NEXT") {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = task.todoKeyword,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = if (task.todoKeyword == "NEXT") {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // Priority Badge [#A]
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = colors.badgeContainer
                        ) {
                            Text(
                                text = "[#${task.priority}]",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = colors.badgeContent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // Origin File / Note Title
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = task.fileTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Repeating Badge (e.g. Repeat ++1w)
                        if (task.repeater != null) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Repeat,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = task.repeater.raw,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }

                        // Scheduled Badge
                        if (task.scheduledDate != null) {
                            val today = java.time.LocalDate.now()
                            val isOverdue = task.scheduledDate.isBefore(today)
                            val isToday = task.scheduledDate.isEqual(today)

                            val badgeBg = when {
                                isOverdue -> MaterialTheme.colorScheme.errorContainer
                                isToday -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                            val badgeContent = when {
                                isOverdue -> MaterialTheme.colorScheme.onErrorContainer
                                isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val dateLabel = when {
                                isOverdue -> "Overdue: ${task.scheduledDate}"
                                isToday -> "Today"
                                else -> "Due: ${task.scheduledDate}"
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = badgeBg
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Event,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = badgeContent
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = dateLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = badgeContent
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
