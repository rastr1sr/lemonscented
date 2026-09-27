package io.github.rastr1sr.radiomonash

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadiocultTest {
    @Test
    fun parsesAndSortsSchedule() {
        val json = JSONObject(
            """
            {"schedules":[
              {"id":"b","title":"Later","start":"2026-09-28T03:00:00.000Z","end":"2026-09-28T04:00:00.000Z","media":{"type":"live"}},
              {"id":"a","title":"Earlier","start":"2026-09-28T01:00:00.000Z","end":"2026-09-28T03:00:00.000Z","media":{"type":"playlist"}}
            ]}
            """,
        )
        val shows = parseSchedule(json)
        assertEquals(listOf("Earlier", "Later"), shows.map { it.title })
        assertEquals(Instant.parse("2026-09-28T01:00:00Z"), shows[0].start)
        assertFalse(shows[0].live)
        assertTrue(shows[1].live)
    }

    @Test
    fun matchesStreamTitle() {
        assertTrue(sameTrack("Stefan West - Hard Times", "hard times"))
        assertFalse(sameTrack("Stefan West - Hard Times", "Soft Times"))
        assertFalse(sameTrack("Stefan West - Hard Times", null))
    }

    @Test
    fun readsAirMode() {
        val show = { status: String, type: String ->
            JSONObject("""{"status":"$status","content":{"media":{"type":"$type"}}}""")
        }
        assertEquals("live", airMode(show("schedule", "live")))
        assertEquals("playlist", airMode(show("schedule", "playlist")))
        assertEquals(
            "playlist",
            airMode(JSONObject("""{"status":"defaultPlaylist","content":{}}""")),
        )
        assertEquals("off", airMode(JSONObject("""{"status":"offAir"}""")))
    }
}
