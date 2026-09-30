package io.github.rastr1sr.radiomonash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadiocultTest {
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
        assertEquals(AirMode.Live, airMode(show("schedule", "live")))
        assertEquals(AirMode.Playlist, airMode(show("schedule", "playlist")))
        assertEquals(
            AirMode.Playlist,
            airMode(JSONObject("""{"status":"defaultPlaylist","content":{}}""")),
        )
        assertEquals(AirMode.Off, airMode(JSONObject("""{"status":"offAir"}""")))
    }

    @Test
    fun flattensTipTap() {
        val doc = JSONObject(
            """
            {"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"An hour of "},{"type":"text","text":"music."}]},
              {"type":"paragraph","content":[{"type":"text","text":"No talking."},{"type":"hardBreak"},{"type":"text","text":"Just songs."}]}
            ]}
            """,
        )
        assertEquals("An hour of music.\n\nNo talking.\nJust songs.", tipTapText(doc))
        assertEquals(null, tipTapText(JSONObject("""{"type":"doc","content":[]}""")))
        assertEquals(null, tipTapText(null))
    }
}
