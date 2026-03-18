package com.example.lyrisync

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.http.GET
import retrofit2.http.Query
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.widget.ToggleButton

data class LrcResponse(
    val id: Int,
    val name: String,
    val artistName: String,
    val syncedLyrics: String?,
    val plainLyrics: String?
)
data class LyricLine(
    val timeMs: Long,
    val text: String
)
interface LrcLibService {
    @GET("api/search")
    suspend fun searchLyrics(
        @Query("track_name") track: String,
        @Query("artist_name") artist: String
    ): List<LrcResponse>
}
interface TranslationService {
    @GET("translate_a/single")
    suspend fun getTranslation(
        @Query("client") client: String = "gtx",
        @Query("sl") sourceLang: String = "ja",
        @Query("tl") targetLang: String = "en",
        @Query("dt") dataType: String = "t",
        @Query("q") q: String
    ): List<Any>
}
// Define the services here
private val lrcService: LrcLibService by lazy {
    retrofit2.Retrofit.Builder()
        .baseUrl("https://lrclib.net/")
        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
        .build()
        .create(LrcLibService::class.java)
}
private val translationService: TranslationService by lazy {
    retrofit2.Retrofit.Builder()
        .baseUrl("https://translate.googleapis.com/")
        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
        .build()
        .create(TranslationService::class.java)
}

class MainActivity : AppCompatActivity() {
    data class JishoResponse(val data: List<JishoData>)
    private var translatedLyrics = listOf<String>()
    data class JishoData(val japanese: List<JishoJapanese>, val senses: List<JishoSense>)
    data class JishoJapanese(val word: String?, val reading: String?)
    data class JishoSense(val english_definitions: List<String>)
    private var lyricAdapter: LyricAdapter? = null
    private var parsedLyrics = listOf<LyricLine>()
    private var syncJob: Job? = null

    // 1. Replace with your Client ID from the Spotify Developer Dashboard
    private val clientId = "06f5df4fd4234a06bbc234600ed42851"
    private val redirectUri = "lyrisync://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Add this to prove the code is reaching this point
        findViewById<TextView>(R.id.songTitleText)?.text = "App Started! Connecting..."
        Log.d("Lyrisync", "onCreate finished")

        val recyclerView = findViewById<RecyclerView>(R.id.lyricRecyclerView)
        val snapHelper = androidx.recyclerview.widget.LinearSnapHelper()
        snapHelper.attachToRecyclerView(recyclerView)
        lyricAdapter = LyricAdapter()
        recyclerView.adapter = lyricAdapter
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        val jishoRv = findViewById<RecyclerView>(R.id.jishoRecyclerView)

        // Initialize the adapter here
        jishoAdapter = JishoHistoryAdapter(jishoHistory)

        jishoRv.adapter = jishoAdapter
        jishoRv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
    }

    override fun onStart() {
        super.onStart()
        Log.d("Lyrisync", "Attempting to connect with Client ID: $clientId")
        // 2. Setup connection parameters
        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true) // Set this to TRUE
            .build()

        // 3. Connect to Spotify
        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                Log.d("Lyrisync", "Connected to Spotify!")
                connected()
            }

            // This is the one giving you the "overrides nothing" error
            override fun onFailure(throwable: Throwable) {
                Log.e("Lyrisync", "Connection failed: ${throwable.message}", throwable)

                // Use runOnUiThread to update the screen since this might happen on a background thread
                runOnUiThread {
                    val titleView = findViewById<TextView>(R.id.songTitleText)
                    titleView.text = "Connection Failed: ${throwable.message}"
                }
            }
        })
    }

    private var currentTrackUri: String? = null

    private fun connected() {
        spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
            val track = playerState.track
            if (track != null) {
                if (track.uri != currentTrackUri) {
                    currentTrackUri = track.uri
                    findViewById<TextView>(R.id.songTitleText).text = track.name
                    findViewById<TextView>(R.id.artistNameText).text = track.artist.name
                    fetchLyrics(track.name, track.artist.name)
                }

                // Start the sync loop if it's not running
                if (syncJob == null || !syncJob!!.isActive) {
                    startSyncLoop()
                }
            }
        }
    }

    private var activeIndex = -1

    private fun startSyncLoop() {
        val recyclerView = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.lyricRecyclerView)

        syncJob = lifecycleScope.launch {
            while (isActive) {
                spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
                    val currentMs = playerState.playbackPosition

                    // Find the index of the line that matches current time
                    // Inside startSyncLoop callback
                    val index = parsedLyrics.indexOfLast { it.timeMs <= currentMs }
                    if (index != -1 && index != activeIndex) {
                        activeIndex = index
                        runOnUiThread {
                            // LYRICS always sync highlight
                            lyricAdapter?.activeIndex = index
                            lyricAdapter?.notifyDataSetChanged()

                            // AUTO-SCROLL only if toggle is on
                            val isSyncOn = findViewById<ToggleButton>(R.id.syncToggleButton).isChecked
                            if (isSyncOn) {
                                findViewById<RecyclerView>(R.id.lyricRecyclerView).smoothScrollToPosition(index)
                                updateJishoDetails(parsedLyrics[index].text)
                            }
                        }
                    }
                }
                delay(500) // The 500ms heartbeat
            }
        }
    }

    private fun fetchTranslation(text: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val retrofit = retrofit2.Retrofit.Builder()
                    .baseUrl("https://translate.googleapis.com/")
                    .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                    .build()

                val service = retrofit.create(TranslationService::class.java)
                // Replace the previous extraction line with this:
                val response = service.getTranslation(q = text)
                val body = response as? List<*>
                val firstPart = body?.get(0) as? List<*>
                val secondPart = firstPart?.get(0) as? List<*>
                val translatedText = secondPart?.get(0)?.toString() ?: "Translation unavailable"
            } catch (e: Exception) {
                Log.e("Lyrisync", "Translation failed: ${e.message}")
            }
        }
    }

    private fun fetchLyrics(title: String, artist: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = lrcService.searchLyrics(title, artist)
                val bestMatch = response.firstOrNull { it.syncedLyrics != null }

                if (bestMatch != null) {
                    parsedLyrics = parseLrc(bestMatch.syncedLyrics!!)
                    val fullJapaneseText = parsedLyrics.joinToString("\n") { it.text }
                    val translationResponse = translationService.getTranslation(q = fullJapaneseText)
                    val bulkResult = extractTextFromGoogle(translationResponse)
                    translatedLyrics = bulkResult.split("\n")

                    withContext(Dispatchers.Main) {
                        // This is the "New Way" to update the UI
                        lyricAdapter?.updateData(parsedLyrics, translatedLyrics)
                    }
                }
            } catch (e: Exception) {
                Log.e("Lyrisync", "Bulk fetch failed: ${e.message}")
            }
        }
    }

    private fun extractTextFromGoogle(response: List<Any>): String {
        return try {
            val body = response as? List<*>
            val segments = body?.get(0) as? List<*>
            val result = StringBuilder()
            segments?.forEach { segment ->
                val parts = segment as? List<*>
                result.append(parts?.get(0)?.toString() ?: "")
            }
            result.toString()
        } catch (e: Exception) {
            "Translation Error"
        }
    }

    private var isSyncEnabled = true
    private val jishoHistory = mutableListOf<CharSequence>()
    private lateinit var jishoAdapter: JishoHistoryAdapter // Standard adapter similar to LyricAdapter

    private fun updateJishoDetails(text: String) {
        val kanjiList = text.filter {
            Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        }.toList().distinct()

        if (kanjiList.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val spannable = android.text.SpannableStringBuilder()
            val gson = com.google.gson.Gson()

            for (kanji in kanjiList) {
                try {
                    val url = java.net.URL("https://jisho.org/api/v1/search/words?keyword=$kanji")
                    val result = gson.fromJson(url.readText(), JishoResponse::class.java)
                    val match = result.data.firstOrNull()

                    if (match != null) {
                        val start = spannable.length
                        spannable.append("【 $kanji 】")

                        // Highlight the Kanji in a different color (e.g., Orange/Yellow)
                        spannable.setSpan(
                            android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#FFB74D")),
                            start + 2, start + 3, // Targets just the character inside brackets
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )

                        val reading = match.japanese.firstOrNull()?.reading ?: ""
                        val def = match.senses.firstOrNull()?.english_definitions?.joinToString(", ") ?: ""
                        spannable.append(" ($reading)\n→ $def\n\n")
                    }
                } catch (e: Exception) { Log.e("Lyrisync", "Jisho Error") }
            }

            // Inside updateJishoDetails, change the Main thread block to:
            withContext(Dispatchers.Main) {
                if (spannable.isNotEmpty()) {
                    jishoHistory.add(0, spannable)

                    // The ?. ensures no crash if the adapter isn't ready yet
                    jishoAdapter?.notifyItemInserted(0)

                    if (isSyncEnabled) {
                        findViewById<RecyclerView>(R.id.jishoRecyclerView).scrollToPosition(0)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 5. Always disconnect to save battery and memory
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
    }
}

// Checks if the line contains any Kanji characters
fun containsKanji(text: String): Boolean {
    return text.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS }
}

// LRC files look like [00:12.34] xxxx. We need to convert 00:12.34 into total milliseconds.
fun parseLrc(lrcContent: String): List<LyricLine> {
    val lyricList = mutableListOf<LyricLine>()
    val lines = lrcContent.split("\n")

    val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2})](.*)")

    for (line in lines) {
        val match = regex.find(line)
        if (match != null) {
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val ms = match.groupValues[3].toLong() * 10 // xx is usually centiseconds
            val text = match.groupValues[4].trim()

            val totalMs = (min * 60 * 1000) + (sec * 1000) + ms
            lyricList.add(LyricLine(totalMs, text))
        }
    }
    return lyricList.sortedBy { it.timeMs }
}

class JishoHistoryAdapter(private val history: List<CharSequence>) :
    RecyclerView.Adapter<JishoHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // We can reuse a simple layout or just a CardView with a TextView
        val content: TextView = view.findViewById(R.id.cardContent)
    }

    override fun onCreateViewHolder(
        p0: ViewGroup,
        p1: Int
    ): ViewHolder {
        val view = LayoutInflater.from(p0.context).inflate(R.layout.item_jisho_card, p0, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.content.text = history[position]
    }

    override fun getItemCount() = history.size
}