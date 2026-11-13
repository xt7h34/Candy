package com.candy

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * A live line to the bot, open while the app is on screen. The bot taps it the moment something new happens in your chats
 * (a message, someone typing), and the screens then ask for the new data at once, instead of waiting for their next round of
 * asking. Nothing private travels on it, only the tap. If it drops, the screens simply go back to asking every few seconds.
 */
object Events {
    /** Goes up with every tap from the bot. Screens that show chats use it as a reason to refresh now. */
    var dmTick by mutableStateOf(0)
        private set

    /** True while the line is open (then screens can ask less often). */
    var connected by mutableStateOf(false)
        private set

    private var job: Job? = null

    @Volatile private var conn: HttpURLConnection? = null
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun start(scope: CoroutineScope, token: String) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var wait = 3_000L
            while (isActive) {
                val reached = listen(token)
                main.post { connected = false }
                if (!isActive) break
                delay(wait)
                wait = if (reached) 3_000L else minOf(wait * 2, 30_000L)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        conn?.disconnect()   // wakes up the blocked read
        main.post { connected = false }
    }

    /** Stays on the line until it drops. Returns true if it got connected at all. */
    private fun listen(token: String): Boolean {
        var reached = false
        val c = URL(CandyApi.urlOf("/candy/events")).openConnection() as HttpURLConnection
        conn = c
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 10_000
            c.readTimeout = 70_000   // the bot sends a sign of life every 20 seconds
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "text/event-stream")
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("ngrok-skip-browser-warning", "1")
            if (c.responseCode != 200) return false
            reached = true
            main.post { connected = true }
            c.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                var event = ""
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim()
                    } else if (line.isEmpty()) {
                        if (event == "dm") main.post { dmTick++ }
                        event = ""
                    }
                }
            }
        } catch (e: Exception) {
            // the line was lost: the loop above opens it again
        } finally {
            c.disconnect()
            if (conn === c) conn = null
        }
        return reached
    }
}
