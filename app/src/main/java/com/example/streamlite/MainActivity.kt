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
import java.net.URLEncoder
import org.json.JSONArray
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
            text = "Ismail IPTV · Live"
            textSize = 26f
            setTextColor(-1)
        }
        val url = EditText(this).apply {
            hint = "Server URL (Xtream) or M3U playlist URL"
            setSingleLine(true)
            setTextColor(-1)
            setHintTextColor(0xffaab4c2.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val username = EditText(this).apply {
            hint = "Xtream username"
            setSingleLine(true)
            setTextColor(-1)
            setHintTextColor(0xffaab4c2.toInt())
        }
        val password = EditText(this).apply {
            hint = "Xtream password"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(-1)
            setHintTextColor(0xffaab4c2.toInt())
        }
        val login = Button(this).apply { text = "Login with Xtream Codes" }
        val load = Button(this).apply { text = "Load M3U playlist" }
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
        root.addView(username)
        root.addView(password)
        root.addView(login)
        root.addView(load)
        root.addView(status)
        root.addView(video, LinearLayout.LayoutParams(-1, 0, 2f))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        login.setOnClickListener {
            val server = url.text.toString().trim().trimEnd('/')
            val user = username.text.toString().trim()
            val pass = password.text.toString()
            if (!(server.startsWith("https://") || server.startsWith("http://")) || user.isBlank() || pass.isBlank()) {
                status.text = "Enter server URL, Xtream username and password"
                return@setOnClickListener
            }
            status.text = "Connecting to Xtream…"
            thread {
                try {
                    val encodedUser = URLEncoder.encode(user, "UTF-8")
                    val encodedPass = URLEncoder.encode(pass, "UTF-8")
                    val api = "$"+"server/player_api.php?username=$"+"encodedUser&password=$"+"encodedPass"
                    val info = URL(api).openConnection().apply {
                        connectTimeout = 10000
                        readTimeout = 15000
                    }.getInputStream().bufferedReader().use { it.readText() }
                    val auth = org.json.JSONObject(info).optJSONObject("user_info")
                    if (auth?.optInt("auth", 0) != 1) throw IllegalArgumentException("Invalid login or unsupported Xtream server")
                    val response = URL("$"+"api&action=get_live_streams").openConnection().apply {
                        connectTimeout = 10000
                        readTimeout = 20000
                    }.getInputStream().bufferedReader().use { it.readText() }
                    val streams = JSONArray(response)
                    val channels = (0 until streams.length()).mapNotNull { index ->
                        val item = streams.optJSONObject(index) ?: return@mapNotNull null
                        val id = item.optLong("stream_id", -1)
                        if (id < 0) return@mapNotNull null
                        val name = item.optString("name", "Channel")
                        name to "$"+"server/live/$"+"encodedUser/$"+"encodedPass/$"+"id.ts"
                    }
                    runOnUiThread { showChannels(channels) }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Xtream login failed: $"+"{e.message}" }
                }
            }
        }
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
                    runOnUiThread { showChannels(channels) }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Playlist failed: ${e.message}" }
                }
            }
        }
    }

    private fun showChannels(channels: List<Pair<String, String>>) {
        list.removeAllViews()
        status.text = "$"+"{channels.size} channels found"
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
