package io.github.rastr1sr.radiomonash

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SongLinkTest {
    @Test
    fun picksAppleTrack() {
        val json = """
            {"results":[
              {"trackId":1,"trackName":"Freedom","artistName":"Kid Mac"},
              {"trackId":2,"trackName":"Freedom!","artistName":"SAND"},
              {"trackId":3,"trackName":"The Boys Light Up (Remastered)","artistName":"Australian Crawl"},
              {"trackId":4,"trackName":"PIECES","artistName":"All Regards & Drastic Park"}
            ]}
        """
        assertEquals(2L, appleTrack(json, "Freedom!", "SAND"))
        assertEquals(3L, appleTrack(json, "The Boys Light Up - Remastered 2013", "Australian"))
        assertEquals(4L, appleTrack(json, "PIECES", "All Regards"))
        assertEquals(null, appleTrack(json, "Pineapple Crush", "Guard"))
    }

    @Test
    fun readsSongLinkPage() {
        val html = """
            <html><script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"pageData":{"sections":[
              {"sectionId":"section|auto|albumArt"},
              {"sectionId":"section|auto|links|listen","links":[
                {"displayName":"Deezer","platform":"deezer","url":"https://www.deezer.com/track/1"},
                {"displayName":"Spotify","platform":"spotify"},
                {"displayName":"Pandora","platform":"pandora","url":"https://www.pandora.com/TR:1"}
              ]}
            ]}}}}
            </script></html>
        """
        val exact = listenLinks(html)
        assertEquals(setOf("deezer", "pandora"), exact.keys)
        val found = mergePlatforms("q", exact, complete = true, spotifyApp = true)
        assertEquals(listOf("Deezer"), found.map { it.name })
        assertTrue(found.single().exact)
        assertThrows(JSONException::class.java) { listenLinks("<html>changed</html>") }
    }

    @Test
    fun searchesWhenNothingMatched() {
        val apple = mapOf("appleMusic" to "https://music.apple.com/au/song/1")
        val names = { spotify: Boolean ->
            mergePlatforms("a b", apple, complete = false, spotify).map { it.name }
        }
        assertEquals("Spotify", names(true).first())
        assertFalse("Spotify" in names(false))
        assertFalse("Amazon Music" in names(false))
        val searches = mergePlatforms("a b", apple, complete = false, spotifyApp = false)
        assertTrue(searches.first { it.name == "Apple Music" }.exact)
        val deezer = searches.first { it.name == "Deezer" }
        assertEquals("https://www.deezer.com/search/a%20b", deezer.url)
    }
}
