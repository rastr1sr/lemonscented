package io.github.rastr1sr.radiomonash

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTest {
    @Test
    fun countsStats() {
        val today = LocalDate.of(2026, 9, 28)
        val days = mapOf(
            today.minusDays(1) to 600L,
            today.minusDays(2) to 60L,
            today.minusDays(4) to 30L,
            today.minusDays(9) to 999L,
        )
        val plays = listOf(Play("A", "X"), Play("B", "X"), Play("A", null), Play(null, "Y"))
        val stats = stats(days, plays, today)
        assertEquals(690L, stats.weekSeconds)
        assertEquals(2, stats.streak)
        assertEquals(listOf("A", "B"), stats.topArtists)
        assertEquals(listOf("X", "Y"), stats.topShows)
        assertEquals(3, stats(days + (today to 5L), plays, today).streak)
    }
}
