package io.github.rastr1sr.radiomonash

import java.time.format.DateTimeParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HistoryTest {
    @Test
    fun parsesHistory() {
        val text = """
            {"data":[{"playoutStart":"2026-09-28T00:58:22.000Z","title":"Song","artist":"Band",
            "artwork":{"128x128":"https://cdn.example/a.jpg"}},
            {"playoutStart":"2026-09-28T00:55:00.000Z","title":"Other","artist":null,"artwork":{"128x128":"file:///x"}}]}
        """
        val played = parseHistory(text)
        assertEquals(listOf("Song", "Other"), played.map { it.title })
        assertEquals("Band", played[0].artist)
        assertEquals(null, played[1].artist)
        assertEquals(null, played[1].art)
        assertThrows(DateTimeParseException::class.java) {
            parseHistory("""{"data":[{"playoutStart":"soon","title":"x"}]}""")
        }
    }
}
