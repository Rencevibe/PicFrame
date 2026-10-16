package io.github.rencevibe.picframe.data

import io.github.rencevibe.picframe.domain.TodayItem
import io.github.rencevibe.picframe.domain.parseTodayItems
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HTTP GET for the weather service (`WeatherService.fetch`): body text, or an exception. */
fun httpGet(url: String, timeoutS: Int): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = timeoutS * 1000
        conn.readTimeout = timeoutS * 1000
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } finally {
        conn.disconnect()
    }
}

/** todo.toml from the picked folder (D3). Missing file or folder → []. */
class TodoSource(private val tree: () -> DocumentTree?, private val log: (String) -> Unit = {}) {
    fun load(): List<TodayItem> {
        val text = try {
            val t = tree() ?: return emptyList()
            val entry = t.child(NAME) ?: return emptyList()
            t.open(entry.id)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            log("today: $NAME unreadable ($e)")
            null
        }
        return parseTodayItems(text, NAME, log)
    }

    companion object {
        const val NAME = "todo.toml"
    }
}
