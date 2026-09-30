package io.github.rastr1sr.radiomonash

import java.time.Instant
import java.time.format.DateTimeParseException
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {
    @Test
    fun parsesAndSortsSchedule() {
        val json = """
            {"schedules":[
              {"id":"b","title":"Later","start":"2026-09-28T03:00:00.000Z","end":"2026-09-28T04:00:00.000Z","media":{"type":"live"}},
              {"id":"a","title":"Earlier","start":"2026-09-28T01:00:00.000Z","end":"2026-09-28T03:00:00.000Z","media":{"type":"playlist"}}
            ]}
        """
        val shows = parseSchedule(json)
        assertEquals(listOf("Earlier", "Later"), shows.map { it.title })
        assertEquals(Instant.parse("2026-09-28T01:00:00Z"), shows[0].start)
        assertFalse(shows[0].live)
        assertTrue(shows[1].live)
    }

    @Test
    fun rejectsBadSchedule() {
        val badDate = """{"schedules":[{"id":"a","title":"x","start":"soon","end":"later"}]}"""
        assertThrows(DateTimeParseException::class.java) { parseSchedule(badDate) }
        assertThrows(JSONException::class.java) { parseSchedule("not json") }
    }

    @Test
    fun previewsFirstLine() {
        assertEquals("Two hours of music…", preview("Two hours of music.\n\nNo talking."))
        assertEquals("One line only.", preview("One line only."))
    }
}
