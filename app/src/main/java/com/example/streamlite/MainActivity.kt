package com.example.streamlite

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.URL
import kotlin.concurrent.thread

/** TV-first prototype: load an authorized M3U URL and select a channel. */
class MainActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var video: PlayerView
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(0xff101827.toInt())
        }
        val title = TextView(this).apply {
            text = "StreamLite TV · Live"
            textSize = 26f
            setTextColor(-1)
        }
        val url = EditText(this).apply {
            hint = "Paste your authorized M3U playlist URL"
            setSingleLine(true)
            setTextColor(-1)
            setHintTextColor(0xffaab4c2.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val load = Button(this).apply { text = "Load playlist" }
        status = TextView(this).apply {
            text = "Ready. Add a playlist to begin."
            setTextColor(-1)
        }
        video = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = true
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(title)
        root.addView(url)
        root.addView(load)
        root.addView(status)
        root.addView(video, LinearLayout.LayoutParams(-1, 0, 2f))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        load.setOnClickListener {
            val address = url.text.toString().trim()
            if (!address.startsWith("https://") && !address.startsWith("http://")) {
                status.text = "Enter a valid HTTP(S) playlist URL"
                return@setOnClickListener
            }
            status.text = "Loading…"
            thread {
                try {
                    val connection = URL(address).openConnection().apply {
                        connectTimeout = 10000
                        readTimeout = 15000
                    }
                    val body = connection.getInputStream().bufferedReader().use { it.readText() }
                    val channels = parseM3u(body)
                    runOnUiThread {
                        list.removeAllViews()
                        status.text = "${channels.size} channels found"
                        channels.take(2000).forEach { (name, stream) ->
                            list.addView(Button(this).apply {
                                text = name
                                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                                isFocusable = true
                                setOnClickListener {
                                    player.setMediaItem(MediaItem.fromUri(stream))
                                    player.prepare()
                                    player.play()
                                }
                            })
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Playlist failed: ${e.message}" }
                }
            }
        }
    }

    private fun parseM3u(text: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var pending: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                pending = line.substringAfterLast(',').ifBlank { "Unnamed channel" }
            } else if (!line.startsWith("#") &&
                (line.startsWith("http://") || line.startsWith("https://"))) {
                result += (pending ?: "Channel ${result.size + 1}") to line
                pending = null
            }
        }
        return result
    }

    override fun onStop() { super.onStop(); player.pause() }
    override fun onDestroy() { player.release(); super.onDestroy() }
}
