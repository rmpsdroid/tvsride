package dev.tvsopen.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTextFormatterTest {
    @Test
    fun includesSenderBeforeMessageBody() {
        assertEquals("FCHG: Testttt", MessageTextFormatter.formatForCluster("FCHG", "Testttt"))
    }

    @Test
    fun truncatesBodyToTheSeventeenCharacterClusterLimit() {
        assertEquals("Alice: Hello ther", MessageTextFormatter.formatForCluster("Alice", "Hello there, how are you?"))
    }

    @Test
    fun omitsGenericAppNameWhenSenderIsNotAvailable() {
        assertEquals("Hello there", MessageTextFormatter.formatForCluster("Messages", "Hello there"))
    }

    @Test
    fun showsSenderWhenBodyIsEmpty() {
        assertEquals("Alice", MessageTextFormatter.formatForCluster("Alice", ""))
    }
}
