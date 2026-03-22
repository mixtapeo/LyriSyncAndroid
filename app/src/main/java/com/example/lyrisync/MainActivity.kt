package com.example.lyrisync

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ToggleButton
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.core.graphics.toColorInt
import androidx.room.Query as SqlQuery
import retrofit2.http.Query as ApiQuery
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
        @ApiQuery("track_name") track: String,
        @ApiQuery("artist_name") artist: String
    ): List<LrcResponse>
}
interface TranslationService {
    @GET("translate_a/single")
    suspend fun getTranslation(
        @ApiQuery("client") client: String = "gtx",
        @ApiQuery("sl") sourceLang: String = "ja",
        @ApiQuery("tl") targetLang: String = "en",
        @ApiQuery("dt") dataType: String = "t",
        @ApiQuery("q") q: String
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
@Dao
interface JishoDao {
    @SqlQuery("SELECT * FROM dictionary WHERE kanji LIKE '%' || :query || '%'  OR reading LIKE '%' || :query || '%'  LIMIT 1")
    fun getDefinition(query: String): JishoEntry?
}
@Entity(tableName = "dictionary")
data class JishoEntry(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String, // Room requires this to be non-nullable in code
    @ColumnInfo(name = "kanji") val kanji: String?,
    @ColumnInfo(name = "reading") val reading: String?,
    @ColumnInfo(name = "meanings") val definition: String? // Matches 'notNull=false' in your log
)
@Database(entities = [JishoEntry::class], version = 1, exportSchema = false) // Add exportSchema = false
abstract class AppDatabase : RoomDatabase() {
    abstract fun jishoDao(): JishoDao

    companion object {
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "jmdict.db" // This is the internal name
                )
                    .createFromAsset("databases/jmdict.db") // This is the path in your assets folder
                    .fallbackToDestructiveMigration() // If you change the version, it wipes and re-copies from assets
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
// The new "Big Box" data model
data class JishoLineSet(
    val lyricText: String,
    val definitions: List<CharSequence>
)

class MainActivity : AppCompatActivity() {
    private var translatedLyrics = listOf<String>()
    private var lyricAdapter: LyricAdapter? = null
    private var parsedLyrics = listOf<LyricLine>()
    private var syncJob: Job? = null

    // 1. Replace with your Client ID from the Spotify Developer Dashboard
    private val clientId = "06f5df4fd4234a06bbc234600ed42851"
    private val redirectUri = "lyrisync://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null
    private val songDictionary = mutableMapOf<String, CharSequence>()
    private var isSyncEnabled = true
    private val jishoHistory = mutableListOf<JishoLineSet>()
    private lateinit var jishoAdapter: JishoHistoryAdapter
    private val preparedLineSets = mutableMapOf<Int, JishoLineSet>()

    private fun prepareAllLyricLines() {
        preparedLineSets.clear() // Clear old song's data
        val kanjiRegex = Regex("[\\u4e00-\\u9faf]+")

        for ((index, line) in parsedLyrics.withIndex()) {
            val matches = kanjiRegex.findAll(line.text)
            val lineDefinitions = mutableListOf<CharSequence>()

            for (match in matches) {
                val phrase = match.value
                val cachedDef = songDictionary[phrase]

                if (cachedDef != null) {
                    lineDefinitions.add(cachedDef)
                }
            }

            // If this line has Kanji, package it into a Big Box and save it to the Map
            if (lineDefinitions.isNotEmpty()) {
                preparedLineSets[index] = JishoLineSet(line.text, lineDefinitions)
            }
        }
        Log.d("Lyrisync", "AOT Processing Complete: ${preparedLineSets.size} lines prepared.")
    }

    private fun prefetchSongDictionary(lyrics: List<LyricLine>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@MainActivity)
            val dao = db.jishoDao()

            val kanjiRegex = Regex("[\\u4e00-\\u9faf]+")
            val allPhrases = lyrics.flatMap { line ->
                kanjiRegex.findAll(line.text).map { it.value }.toList()
            }.distinct()

            for (phrase in allPhrases) {
                val entry = dao.getDefinition(phrase)
                if (entry != null && !entry.definition.isNullOrEmpty()) {
                    val spannable = android.text.SpannableStringBuilder()
                    val reading = entry.reading ?: ""
                    spannable.append("【 $phrase 】 ($reading)\n→ ${entry.definition}\n\n")
                    songDictionary[phrase] = spannable
                }
            }
            prepareAllLyricLines()
        }
    }

    private fun displayPreparedLine(lineIndex: Int) {
        // O(1) Instant Lookup
        val preparedBox = preparedLineSets[lineIndex]

        if (preparedBox != null) {
            jishoHistory.add(0, preparedBox)

            runOnUiThread {
                jishoAdapter.notifyItemInserted(0)
                if (isSyncEnabled) {
                    val jishoRv = findViewById<RecyclerView>(R.id.jishoRecyclerView)
                    jishoRv.scrollToPosition(0)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Add this to prove the code is reaching this point
        findViewById<TextView>(R.id.songTitleText)?.text = "App Started! Connecting..."
        Log.d("Lyrisync", "onCreate finished")

        val settingsBtn = findViewById<android.view.View>(R.id.settingsButton)
        settingsBtn.setOnClickListener {
            // An Intent is a formal request to the OS to launch a new component
            val intent = android.content.Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
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

        val syncBtn = findViewById<ToggleButton>(R.id.syncToggleButton)
        syncBtn.setOnCheckedChangeListener { _, isChecked ->
            isSyncEnabled = isChecked

            // Visual feedback: Change color when in Manual mode
            if (isChecked) {
                syncBtn.backgroundTintList = android.content.res.ColorStateList.valueOf("#1DB954".toColorInt())
            } else {
                syncBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.GRAY)
            }
        }
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
                    activeIndex = -1 // Reset this so the first line of the new song triggers
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
        syncJob = lifecycleScope.launch {
            while (isActive) {
                spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
                    runOnUiThread {
                        syncLyricsToPosition(playerState.playbackPosition)
                    }
                }
                delay(100)
            }
        }
    }

    private fun fetchLyrics(title: String, artist: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = lrcService.searchLyrics(title, artist)

                // Log all found IDs and snippet for debugging
                response.forEach {
                    Log.d("Lyrisync", "Found LRC ID: ${it.id} | HasSynced: ${it.syncedLyrics != null}")
                }

                // Priority Logic:
                // 1. Find entries with Synced Lyrics
                // 2. Of those, find one containing Japanese characters (Kanji/Hiragana/Katakana)
                // 3. Fallback to the first synced entry if no Japanese-specific entry is found
                val jpRegex = Regex("[\\u3040-\\u30ff\\u4e00-\\u9faf]")

                val bestMatch = response
                    .filter { it.syncedLyrics != null }
                    .firstOrNull { it.syncedLyrics?.contains(jpRegex) == true }
                    ?: response.firstOrNull { it.syncedLyrics != null }

                if (bestMatch != null) {
                    Log.d("Lyrisync", "Selected Lyrics ID: ${bestMatch.id}")
                    parsedLyrics = parseLrc(bestMatch.syncedLyrics!!)

                    val fullJapaneseText = parsedLyrics.joinToString("\n") { it.text }
                    val translationResponse = translationService.getTranslation(q = fullJapaneseText)
                    val bulkResult = extractTextFromGoogle(translationResponse)
                    translatedLyrics = bulkResult.split("\n")

                    prefetchSongDictionary(parsedLyrics)

                    withContext(Dispatchers.Main) {
                        lyricAdapter?.updateData(parsedLyrics, translatedLyrics)

                        // Trigger an immediate sync so we don't wait for the loop delay
                        spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
                            syncLyricsToPosition(playerState.playbackPosition)
                        }
                    }
                } else {
                    Log.e("Lyrisync", "No synced lyrics found for $title. Clearing lyrics")
                    parsedLyrics = listOf()
                    withContext(Dispatchers.Main) {
                        lyricAdapter?.updateData(parsedLyrics, listOf())
                    }
                }
            } catch (e: Exception) {
                Log.e("Lyrisync", "Fetch failed: ${e.message}", e)
            }
        }
    }

    private fun syncLyricsToPosition(currentMs: Long) {
        val index = parsedLyrics.indexOfLast { it.timeMs <= currentMs }
        if (index != -1 && index != activeIndex) {
            activeIndex = index
            lyricAdapter?.activeIndex = index
            lyricAdapter?.notifyDataSetChanged()
            // Replace your "if (isSyncEnabled)" logic with this:
            val sharedPrefs = getSharedPreferences("LyriSyncPrefs", Context.MODE_PRIVATE)
            val isSyncEnabled = sharedPrefs.getBoolean("AUTO_SYNC", true)
            if (isSyncEnabled) {
                displayPreparedLine(index)
                findViewById<RecyclerView>(R.id.lyricRecyclerView).smoothScrollToPosition(index)
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
            "Translation Error" + e.message
        }
    }


    override fun onStop() {
        super.onStop()
        // 5. Always disconnect to save battery and memory
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
    }

    override fun onResume() {
        super.onResume()

        val sharedPrefs = getSharedPreferences("LyriSyncPrefs", Context.MODE_PRIVATE)

        // Check for History Wipe
        val wipeRequested = sharedPrefs.getBoolean("WIPE_REQUESTED", false)
        if (wipeRequested) {
            jishoHistory.clear()
            jishoAdapter?.notifyDataSetChanged()
            sharedPrefs.edit().putBoolean("WIPE_REQUESTED", false).apply()
        }

        // Check for Lyric Subtitle Change
        val refreshLyricsRequested = sharedPrefs.getBoolean("REFRESH_LYRICS_REQUESTED", false)
        if (refreshLyricsRequested) {
            // Redraw the entire lyric list so the new visibility settings apply
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.lyricRecyclerView).adapter?.notifyDataSetChanged()
            sharedPrefs.edit().putBoolean("REFRESH_LYRICS_REQUESTED", false).apply()
        }
    }
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

class JishoHistoryAdapter(private val history: List<JishoLineSet>) :
    RecyclerView.Adapter<JishoHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val lineHeader: TextView = view.findViewById(R.id.lineHeader)
        val container: android.widget.LinearLayout = view.findViewById(R.id.definitionsContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_jisho_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = history[position]

        // 1. Set the Lyric Line Text
        holder.lineHeader.text = item.lyricText

        // 2. Clear out old boxes
        holder.container.removeAllViews()

        // 3. Create a "Small Box" for every Kanji definition in this line
        val context = holder.itemView.context
        for (definition in item.definitions) {
            val smallBox = TextView(context).apply {
                text = definition
                textSize = 15f
                setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
                setPadding(32, 24, 32, 24)

                val marginParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                marginParams.setMargins(0, 0, 0, 16)
                layoutParams = marginParams

                val drawable = android.graphics.drawable.GradientDrawable()
                drawable.setColor(android.graphics.Color.parseColor("#383838"))
                drawable.cornerRadius = 16f
                background = drawable
            }
            holder.container.addView(smallBox)
        }
    }

    override fun getItemCount() = history.size
}