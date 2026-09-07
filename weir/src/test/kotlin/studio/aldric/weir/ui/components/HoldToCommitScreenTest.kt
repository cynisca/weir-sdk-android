package studio.aldric.weir.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class HoldToCommitScreenTest {
    @Test fun blankFallsBackToFirstSuggestion() = assertEquals("First", holdCommitValue("   ", listOf("First", "Second")))
    @Test fun nonBlankTextWins() = assertEquals("Custom", holdCommitValue("Custom", listOf("First")))
    @Test fun blankWithoutSuggestionUsesEmptyString() = assertEquals("", holdCommitValue("", null))
}
