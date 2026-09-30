package io.github.rastr1sr.radiomonash

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTest {
    @Test
    fun parsesChatMessages() {
        val text = """
            [{"id":"a","timestampId":"t1","userId":"u","displayName":"Adam","createdAt":10,"type":"message",
            "content":{"text":"hi"},"isStationMessage":true},
            {"id":"b","userId":"v","displayName":"B","createdAt":11,"type":"gif",
            "content":{"media":{"aspectRatio":0.5,"url":"https://media.giphy.com/x.gif"}}},
            {"id":"c","userId":"w","displayName":"C","createdAt":12,"type":"gif","content":{"media":{"url":"http://x"}}},
            {"id":"d","displayName":"broken"}]
        """
        val messages = parseMessages(JSONArray(text))
        assertEquals(listOf("a", "b", "c"), messages.map { it.id })
        assertEquals("hi", messages[0].text)
        assertTrue(messages[0].fromStation)
        assertEquals("https://media.giphy.com/x.gif", messages[1].gif)
        assertEquals(0.5f, messages[1].ratio)
        assertEquals(null, messages[2].gif)
    }
}
