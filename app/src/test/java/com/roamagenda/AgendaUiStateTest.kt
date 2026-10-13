/*
 * SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.roamagenda

import com.roamagenda.ui.AgendaUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaUiStateTest {
    @Test
    fun testCollapsedPrioritiesDefaultsAndToggling() {
        val state = AgendaUiState()
        assertTrue(state.collapsedPriorities.isEmpty())
        assertFalse('A' in state.collapsedPriorities)

        val collapsedA = state.copy(collapsedPriorities = state.collapsedPriorities + 'A')
        assertTrue('A' in collapsedA.collapsedPriorities)
        assertFalse('B' in collapsedA.collapsedPriorities)

        val collapsedBoth = collapsedA.copy(collapsedPriorities = collapsedA.collapsedPriorities + 'B')
        assertEquals(setOf('A', 'B'), collapsedBoth.collapsedPriorities)

        val uncollapsedA = collapsedBoth.copy(collapsedPriorities = collapsedBoth.collapsedPriorities - 'A')
        assertEquals(setOf('B'), uncollapsedA.collapsedPriorities)
    }
}
