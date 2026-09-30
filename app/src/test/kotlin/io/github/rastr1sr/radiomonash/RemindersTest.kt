package io.github.rastr1sr.radiomonash

import org.junit.Assert.assertEquals
import org.junit.Test

class RemindersTest {
    @Test
    fun decodesReminder() {
        assertEquals(1790000000000L to "Oak | Ash", decodeReminder("1790000000000|Oak | Ash"))
        assertEquals(null, decodeReminder("garbage"))
    }
}
