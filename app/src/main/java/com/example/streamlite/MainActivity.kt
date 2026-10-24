package com.example.streamlite

import android.app.Activity
import android.app.DownloadManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.json.JSONArray
import org.json.JSONObject
import java.net.*
import java.io.*
import java.security.SecureRandom
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val bg = Color.rgb(12, 17, 29)
    private val panel = Color.rgb(27, 36, 53)
    private val accent = Color.rgb(39, 189, 229)
    private val prefs by lazy { getSharedPreferences("iptv", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var player: ExoPlayer
    private lateinit var video: PlayerView
    private lateinit var items: LinearLayout
    private var server = ""
    private var username = ""
    private var password = ""
    private var section = "live"
    private var filter = "all"
    private var categoryId = ""
    private var categoryOpen = false
    private val categoryNames = mutableMapOf<String, Map<String, String>>()
    private var channels = mutableListOf<Entry>()
    private var movies = mutableListOf<Entry>()
    private var series = mutableListOf<Entry>()
    private val favorites = mutableSetOf<String>()
    private val recent = mutableListOf<String>()
    private var pairServer: ServerSocket? = null
    private var pairToken = ""
    private var pairing = false
    private var updateDownloadId = -1L
    private var updateReceiver: BroadcastReceiver? = null
    private val releaseApi = "https://api.github.com/repos/ismaltundag/StreamLite-TV/releases/tags/tv-latest"

    data class Entry(val id: String, val name: String, val url: String, val kind: String, val container: String = "mp4", val categoryId: String = "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        favorites.addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        recent.addAll(prefs.getString("recent", "")!!.split("|").filter { it.isNotBlank() })
        server = prefs.getString("server", "") ?: ""
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""
        if (server.isNotBlank() && username.isNotBlank() && password.isNotBlank()) loadAccount() else showLogin()
        checkForUpdates(false)
    }

    private fun label(value: String, size: Float = 18f): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(12, 10, 12, 10)
    }

    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value
        isFocusable = true
        setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(panel))
        setOnClickListener { action() }
    }

    private fun base(): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 18, 28, 18)
            setBackgroundColor(bg)
        }
        setContentView(root)
        return root
    }

    private fun field(hintText: String, value: String = "", secret: Boolean = false): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        setSingleLine(true)
        textSize = 18f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.LTGRAY)
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun showLogin(message: String = "Sign in once. Your account stays saved on this TV.") {
        base()
        val outer = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.VERTICAL }
        root.addView(outer, LinearLayout.LayoutParams(-1, -1))
        outer.addView(label("ISMAIL IPTV", 32f))
        outer.addView(label("XTREAM CODES LOGIN", 21f))
        val serverField = field("Server URL including port", server)
        val userField = field("Username", username)
        val passField = field("Password", password, true)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 20, 28, 20); setBackgroundColor(panel) }
        outer.addView(form, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * .68).toInt(), -2))
        form.addView(serverField)
        form.addView(userField)
        form.addView(passField)
        status = label(message, 15f)
        form.addView(status)
        form.addView(button("SIGN IN") {
            val s = serverField.text.toString().trim().trimEnd('/')
            val u = userField.text.toString().trim()
            val p = passField.text.toString()
            if (!(s.startsWith("http://") || s.startsWith("https://")) || u.isEmpty() || p.isEmpty()) {
                status.text = "Enter a complete server URL, username and password"
            } else {
                server = s; username = u; password = p
                stopPairing()
                loadAccount()
            }
        })
        form.addView(button("ENTER LOGIN ON PHONE  ·  QR CODE") { startPairing(form) })
    }

    private fun loadAccount() {
        base()
        status = label("Connecting to Xtream…", 20f)
        root.addView(status)
        thread {
            try {
                val auth = JSONObject(fetch(api(""))).optJSONObject("user_info")
                if (auth?.optInt("auth", 0) != 1) throw IllegalArgumentException("Server rejected login. Check credentials and URL.")
                for ((kind, action) in listOf("live" to "get_live_categories", "movie" to "get_vod_categories", "series" to "get_series_categories")) {
                    try {
                        val categories = JSONArray(fetch(api(action)))
                        val names = mutableMapOf<String, String>()
                        for (i in 0 until categories.length()) {
                            val item = categories.optJSONObject(i) ?: continue
                            names[item.optString("category_id")] = item.optString("category_name", "Other")
                        }
                        categoryNames[kind] = names
                    } catch (_: Exception) { categoryNames[kind] = emptyMap() }
                }
                val live = parseEntries(fetch(api("get_live_streams")), "live")
                val vod = parseEntries(fetch(api("get_vod_streams")), "movie")
                val shows = parseEntries(fetch(api("get_series")), "series")
                channels = live.toMutableList()
                movies = vod.toMutableList()
                series = shows.toMutableList()
                prefs.edit().putString("server", server).putString("username", username).putString("password", password).apply()
                runOnUiThread { showHome() }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Unable to load: " + (e.message ?: e.javaClass.simpleName)
                    root.addView(button("RETRY") { loadAccount() })
                    root.addView(button("EDIT LOGIN") { showLogin() })
                }
            }
        }
    }

    private fun api(action: String): String {
        val user = URLEncoder.encode(username, "UTF-8")
        val pass = URLEncoder.encode(password, "UTF-8")
        return server + "/player_api.php?username=" + user + "&password=" + pass +
            (if (action.isBlank()) "" else "&action=" + action)
    }

    private fun fetch(address: String): String {
        val connection = URL(address).openConnection().apply { connectTimeout = 10000; readTimeout = 20000 }
        return connection.getInputStream().bufferedReader().use { it.readText() }
    }

    private fun parseEntries(json: String, kind: String): List<Entry> {
        val data = JSONArray(json)
        val u = URLEncoder.encode(username, "UTF-8")
        val p = URLEncoder.encode(password, "UTF-8")
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString(if (kind == "series") "series_id" else "stream_id")
            if (id.isBlank()) return@mapNotNull null
            val name = item.optString("name", "Untitled")
            val extension = item.optString("container_extension", "mp4").ifBlank { "mp4" }
            val address = when (kind) {
                "live" -> server + "/live/" + u + "/" + p + "/" + id + ".ts"
                "movie" -> server + "/movie/" + u + "/" + p + "/" + id + "." + extension
                else -> ""
            }
            Entry(kind + ":" + id, name, address, kind, extension, item.optString("category_id"))
        }
    }

    private fun showHome() {
        base()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(header)
        header.addView(label("ISMAIL IPTV", 24f))
        for (tab in listOf("live", "movie", "series")) {
            header.addView(button(when (tab) { "live" -> "LIVE TV"; "movie" -> "MOVIES"; else -> "SERIES" } +
                if (section == tab) "  ●" else "") { section = tab; filter = "all"; categoryId = ""; categoryOpen = false; showHome() })
        }
        header.addView(button("ACCOUNT") { showAccount() })
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(left, LinearLayout.LayoutParams(0, -1, 1f))
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        left.addView(filters)
        if (section == "live") {
            for ((key, title) in listOf("all" to "ALL", "favorites" to "★ FAVORITES", "recent" to "◷ RECENT")) {
                filters.addView(button(title + if (filter == key) " ●" else "") { filter = key; categoryId = ""; categoryOpen = false; showHome() })
            }
        }
        status = label("", 15f)
        left.addView(status)
        val scroll = ScrollView(this)
        items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(items)
        left.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        video = PlayerView(this).apply { player = this@MainActivity.player; useController = true }
        right.addView(video, LinearLayout.LayoutParams(-1, 0, 1f))
        right.addView(label("Select a channel or movie to start watching.", 16f))
        populate()
    }

    private fun populate() {
        items.removeAllViews()
        val source = when (section) { "live" -> channels; "movie" -> movies; else -> series }
        if (filter == "all" && !categoryOpen) {
            val grouped = source.groupBy { it.categoryId }
            val names = categoryNames[section] ?: emptyMap()
            val sorted = grouped.keys.sortedWith(compareBy({ names[it]?.lowercase() ?: "zzzz" }, { it }))
            status.text = "${source.size} items · ${sorted.size} categories"
            items.addView(button("ALL ${source.size} ITEMS") {
                categoryId = "*"; categoryOpen = true; populate()
            })
            sorted.forEach { id ->
                val name = names[id] ?: if (id.isBlank()) "Uncategorized" else "Category " + id
                items.addView(button("▸ " + name + "   (" + (grouped[id]?.size ?: 0) + ")") {
                    categoryId = id; categoryOpen = true; populate()
                })
            }
            return
        }
        val selected = when (filter) {
            "favorites" -> source.filter { favorites.contains(it.id) }
            "recent" -> recent.mapNotNull { id -> source.find { it.id == id } }
            else -> if (categoryId == "*") source else source.filter { it.categoryId == categoryId }
        }
        if (filter == "all") {
            items.addView(button("← BACK TO CATEGORIES") {
                categoryOpen = false; categoryId = ""; populate()
            })
        }
        status.text = selected.size.toString() + " " + when (section) { "live" -> "channels"; "movie" -> "movies"; else -> "series" }
        selected.take(1500).forEach { entry ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val play = button(entry.name) {
                if (entry.kind == "series") showEpisodes(entry) else play(entry)
            }
            row.addView(play, LinearLayout.LayoutParams(0, -2, 1f))
            if (section == "live") row.addView(button(if (favorites.contains(entry.id)) "★" else "☆") {
                if (!favorites.add(entry.id)) favorites.remove(entry.id)
                prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
                populate()
            })
            items.addView(row)
        }
    }

    private fun play(entry: Entry) {
        if (entry.url.isBlank()) return
        player.setMediaItem(MediaItem.fromUri(entry.url))
        player.prepare()
        player.play()
        recent.remove(entry.id)
        recent.add(0, entry.id)
        while (recent.size > 40) recent.removeAt(recent.lastIndex)
        prefs.edit().putString("recent", recent.joinToString("|")).apply()
        status.text = "Now playing: " + entry.name
    }

    private fun showEpisodes(show: Entry) {
        status.text = "Loading episodes for " + show.name + "…"
        thread {
            try {
                val id = show.id.substringAfter(':')
                val json = JSONObject(fetch(api("get_series_info") + "&series_id=" + URLEncoder.encode(id, "UTF-8")))
                val episodes = json.optJSONObject("episodes") ?: JSONObject()
                val entries = mutableListOf<Entry>()
                val seasons = episodes.keys()
                while (seasons.hasNext()) {
                    val season = seasons.next()
                    val arr = episodes.optJSONArray(season) ?: continue
                    for (i in 0 until arr.length()) {
                        val ep = arr.optJSONObject(i) ?: continue
                        val epId = ep.optString("id")
                        if (epId.isBlank()) continue
                        val ext = ep.optString("container_extension", "mp4").ifBlank { "mp4" }
                        val u = URLEncoder.encode(username, "UTF-8")
                        val p = URLEncoder.encode(password, "UTF-8")
                        entries.add(Entry("episode:" + epId, "S" + season + " · " + ep.optString("title", "Episode " + (i + 1)),
                            server + "/series/" + u + "/" + p + "/" + epId + "." + ext, "episode"))
                    }
                }
                runOnUiThread {
                    items.removeAllViews()
                    items.addView(button("← BACK TO SERIES") { showHome() })
                    status.text = show.name + " · " + entries.size + " episodes"
                    entries.forEach { ep -> items.addView(button(ep.name) { play(ep) }) }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Episodes failed: " + (e.message ?: "Unknown error") }
            }
        }
    }

    private fun showAccount() {
        base()
        root.addView(label("ACCOUNT", 28f))
        root.addView(label("Signed in as " + username + " · " + server))
        root.addView(button("BACK TO TV") { showHome() })
        root.addView(button("CHECK FOR UPDATES") { checkForUpdates(true) })
        root.addView(button("CHANGE LOGIN") {
            prefs.edit().remove("password").apply()
            password = ""
            showLogin()
        })
    }

    private fun stopPairing() {
        pairing = false
        try { pairServer?.close() } catch (_: Exception) {}
        pairServer = null
    }

    private fun startPairing(form: LinearLayout) {
        stopPairing()
        try {
            val socket = ServerSocket(0)
            pairServer = socket
            pairing = true
            val tokenBytes = ByteArray(16)
            SecureRandom().nextBytes(tokenBytes)
            pairToken = tokenBytes.joinToString("") { "%02x".format(it) }
            val address = findLocalAddress() ?: throw IllegalStateException("Connect TV to Wi-Fi first")
            val link = "http://" + address + ":" + socket.localPort + "/" + pairToken
            form.addView(label("Scan with your phone on the same Wi-Fi. This pairing page is temporary.", 15f))
            val qr = MultiFormatWriter().encode(link, BarcodeFormat.QR_CODE, 280, 280)
            val bitmap = Bitmap.createBitmap(280, 280, Bitmap.Config.ARGB_8888)
            for (y in 0 until 280) for (x in 0 until 280) bitmap.setPixel(x, y, if (qr[x, y]) Color.BLACK else Color.WHITE)
            form.addView(ImageView(this).apply { setImageBitmap(bitmap) },
                LinearLayout.LayoutParams(300, 300).apply { gravity = Gravity.CENTER_HORIZONTAL })
            form.addView(label(link, 13f))
            thread {
                while (pairing) {
                    try {
                        val client = socket.accept()
                        thread { handlePairClient(client) }
                    } catch (_: Exception) { break }
                }
            }
        } catch (e: Exception) {
            status.text = "Phone pairing unavailable: " + (e.message ?: "Unknown error")
            stopPairing()
        }
    }

    private fun findLocalAddress(): String? {
        val networks = NetworkInterface.getNetworkInterfaces()
        while (networks.hasMoreElements()) {
            val network = networks.nextElement()
            if (!network.isUp || network.isLoopback) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val ip = addresses.nextElement()
                if (ip is Inet4Address && ip.isSiteLocalAddress) return ip.hostAddress
            }
        }
        return null
    }

    private fun handlePairClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val request = reader.readLine() ?: return
            val parts = request.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]
            var length = 0
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val validPath = path.substringBefore('?') == "/" + pairToken
            if (!validPath || !pairing) { sendPage(sock, 404, "Not found"); return }
            if (method == "GET") {
                sendPage(sock, 200, """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Ismail IPTV Pairing</title><style>body{font:18px sans-serif;background:#0c111d;color:white;max-width:480px;margin:40px auto;padding:20px}input,button{box-sizing:border-box;width:100%;padding:15px;margin:9px 0;font-size:18px;border-radius:9px}button{background:#27bde5;border:0}</style><h2>Ismail IPTV</h2><p>Enter your Xtream login to send it to your TV over local Wi-Fi.</p><form method="POST"><input name="server" placeholder="Server URL including port" required><input name="username" placeholder="Username" required><input name="password" type="password" placeholder="Password" required><button>Connect TV</button></form>""")
            } else if (method == "POST" && length in 1..8192) {
                val chars = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = reader.read(chars, read, length - read)
                    if (n < 0) return
                    read += n
                }
                val values = String(chars).split('&').mapNotNull {
                    val pair = it.split('=', limit = 2)
                    if (pair.size == 2) URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") else null
                }.toMap()
                val s = (values["server"] ?: "").trim().trimEnd('/')
                val u = (values["username"] ?: "").trim()
                val p = values["password"] ?: ""
                if (!(s.startsWith("https://") || s.startsWith("http://")) || u.isBlank() || p.isBlank()) {
                    sendPage(sock, 400, "Invalid server URL or missing credentials")
                } else {
                    sendPage(sock, 200, "<h2>Sent to TV. You can close this page.</h2>")
                    runOnUiThread { server = s; username = u; password = p; stopPairing(); loadAccount() }
                }
            } else sendPage(sock, 405, "Method not allowed")
        }
    }

    private fun sendPage(sock: Socket, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 " + code + " OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: " + bytes.size + "\r\n\r\n"
        sock.getOutputStream().write(header.toByteArray(Charsets.UTF_8))
        sock.getOutputStream().write(bytes)
        sock.getOutputStream().flush()
    }

    private fun checkForUpdates(manual: Boolean) {
        thread {
            try {
                val connection = URL(releaseApi).openConnection().apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Ismail-IPTV-Android-TV")
                }
                val release = JSONObject(connection.getInputStream().bufferedReader().use { it.readText() })
                val remoteVersion = release.optString("tag_name")
                val assets = release.optJSONArray("assets") ?: JSONArray()
                var apkUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name") == "StreamLite-TV.apk") {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                val latestCode = release.optString("body").lineSequence()
                    .firstOrNull { it.startsWith("Version code:") }
                    ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
                val currentCode = packageManager.getPackageInfo(packageName, 0).let {
                    if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
                }
                runOnUiThread {
                    if (apkUrl.isNotBlank() && latestCode > currentCode) {
                        AlertDialog.Builder(this)
                            .setTitle("Ismail IPTV update available")
                            .setMessage("Version " + latestCode + " is ready. Download and install it now?")
                            .setPositiveButton("Install Update") { _, _ -> downloadUpdate(apkUrl) }
                            .setNegativeButton("Later", null)
                            .show()
                    } else if (manual) {
                        AlertDialog.Builder(this).setMessage("You have the latest available version.").setPositiveButton("OK", null).show()
                    }
                }
            } catch (e: Exception) {
                if (manual) runOnUiThread {
                    AlertDialog.Builder(this).setMessage("Unable to check updates: " + (e.message ?: "Network error"))
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun downloadUpdate(url: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Ismail IPTV update")
                .setDescription("Downloading the latest version")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "ismail-update.apk")
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            updateDownloadId = manager.enqueue(request)
            updateReceiver?.let { unregisterReceiver(it) }
            updateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != updateDownloadId) return
                    val cursor = manager.query(DownloadManager.Query().setFilterById(updateDownloadId))
                    val ok = cursor.use { it.moveToFirst() && it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL }
                    if (ok) installUpdate() else Toast.makeText(this@MainActivity, "Update download failed", Toast.LENGTH_LONG).show()
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(updateReceiver, filter)
            Toast.makeText(this, "Downloading update…", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Update failed: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun installUpdate() {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setMessage("Allow Ismail IPTV to install updates, then return and tap Check for Updates again.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
                }.setNegativeButton("Cancel", null).show()
            return
        }
        val file = java.io.File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ismail-update.apk")
        val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onStop() { super.onStop(); player.pause(); stopPairing() }
    override fun onDestroy() { stopPairing(); updateReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }; player.release(); super.onDestroy() }
}
