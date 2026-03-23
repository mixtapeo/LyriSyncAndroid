package com.example.lyrisync

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
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
import androidx.core.view.WindowInsetsCompat
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
    @SqlQuery("SELECT * FROM dictionary WHERE kanji = :query OR reading = :query LIMIT 1")
    fun getDefinition(query: String): JishoEntry?
}
private val preparedLineSets = mutableMapOf<Int, JishoLineSet>()
private val queryCache = mutableMapOf<String, JishoEntry?>()
private val jpCharacterRegex = Regex("[\\u3040-\\u30ff\\u4e00-\\u9faf]")
private val singleKanaRegex = Regex("[\\u3040-\\u30ff]")
@Entity(
    tableName = "dictionary",
    indices = [
        androidx.room.Index(value = ["kanji"], name = "index_kanji"),
        androidx.room.Index(value = ["reading"], name = "index_reading")
    ]
)
data class JishoEntry(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "kanji") val kanji: String?,
    @ColumnInfo(name = "reading") val reading: String?,
    @ColumnInfo(name = "meanings") val definition: String?
)
@Database(entities = [JishoEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun jishoDao(): JishoDao

    companion object {
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "jmdict.db"
                )
                    .createFromAsset("databases/jmdict.db")
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
// 1. The new model to hold raw data for Anki and color syncing
data class JishoWord(
    val phrase: String,
    val reading: String,
    val meaning: String,
    val formattedText: CharSequence,
    val wordIndex: Int // Keeps the color synced with the lyrics!
)

// 2. Update the Big Box model to use our new word model
data class JishoLineSet(
    val lyricText: String,
    val words: List<JishoWord> // Changed from List<CharSequence>
)

class MainActivity : AppCompatActivity() {
    private var translatedLyrics = listOf<String>()
    private var lyricAdapter: LyricAdapter? = null
    private var parsedLyrics = listOf<LyricLine>()
    private var syncJob: Job? = null

    private val clientId = "06f5df4fd4234a06bbc234600ed42851"
    private val redirectUri = "lyrisync://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null
    private val songDictionary = mutableMapOf<String, CharSequence>()
    private var isSyncEnabled = true
    private val jishoHistory = mutableListOf<JishoLineSet>()
    private lateinit var jishoAdapter: JishoHistoryAdapter
    private val preparedLineSets = mutableMapOf<Int, JishoLineSet>()

    private fun extractWordsFromLine(text: String, dao: JishoDao): List<String> {
        val foundWords = mutableListOf<String>()
        var i = 0
        val maxWordLength = 10

        while (i < text.length) {
            var matchFound = false
            val currentMax = minOf(text.length - i, maxWordLength)

            for (len in currentMax downTo 1) {
                val substring = text.substring(i, i + len)

                // FAST FAIL 1: If it's just English or punctuation, skip the DB completely
                if (!substring.contains(jpCharacterRegex)) {
                    continue
                }

                // FAST FAIL 2: Skip single-character Kana particles using the hoisted Regex
                if (len == 1 && substring.matches(singleKanaRegex)) {
                    continue
                }

                // MEMORY CACHE: Don't query SQLite for the same word twice
                val entry = if (queryCache.containsKey(substring)) {
                    queryCache[substring]
                } else {
                    val dbResult = dao.getDefinition(substring)
                    queryCache[substring] = dbResult // Save it for next time!
                    dbResult
                }

                if (entry != null) {
                    foundWords.add(substring)
                    i += len
                    matchFound = true
                    break
                }
            }

            if (!matchFound) {
                i++
            }
        }
        return foundWords
    }

    private fun prefetchSongDictionary(lyrics: List<LyricLine>) {
        val startTime = System.currentTimeMillis()
        Log.d("Lyrisync", "Prefetch started for ${lyrics.size} lines")

        runOnUiThread {
            jishoHistory.clear()
            jishoAdapter.notifyDataSetChanged()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val dbLoadStart = System.currentTimeMillis()
            val db = AppDatabase.getDatabase(this@MainActivity)
            val dao = db.jishoDao()
            Log.d("Lyrisync", "DB/DAO Init took: ${System.currentTimeMillis() - dbLoadStart}ms")

            val furiganaLyrics = mutableListOf<String>()
            val highlightsList = mutableListOf<List<String>>()

            preparedLineSets.clear()
            songDictionary.clear()
            queryCache.clear()

            val loopStart = System.currentTimeMillis()

            for ((index, line) in lyrics.withIndex()) {
                val lineStart = System.currentTimeMillis()
                val lineText = line.text

                if (lineText.isBlank()) {
                    highlightsList.add(emptyList())
                    furiganaLyrics.add("")
                    continue
                }

                // SUSPECT #1: The word extraction logic
                val extractStart = System.currentTimeMillis()
                val lineWords = extractWordsFromLine(lineText, dao)
                highlightsList.add(lineWords)
                val extractDuration = System.currentTimeMillis() - extractStart

                val lineReadings = mutableListOf<String>()
                val lineJishoWords = mutableListOf<JishoWord>() // Replaced lineDefinitions

                // Use forEachIndexed so we can track the exact color index!
                lineWords.forEachIndexed { wordIndex, phrase ->
                    val entry = queryCache[phrase]
                    if (entry != null) {
                        val reading = entry.reading
                        if (!reading.isNullOrBlank()) {
                            lineReadings.add(reading)
                        } else {
                            lineReadings.add(phrase)
                        }

                        val definitionText = entry.definition
                        if (!definitionText.isNullOrBlank()) {
                            if (!songDictionary.containsKey(phrase)) {
                                val spannable = android.text.SpannableStringBuilder()
                                val displayReading = entry.reading ?: phrase
                                spannable.append("【 $phrase 】 ($displayReading)\n→ $definitionText\n\n")
                                songDictionary[phrase] = spannable
                            }
                            // Save the complete object for the UI and Anki
                            songDictionary[phrase]?.let { formatted ->
                                lineJishoWords.add(
                                    JishoWord(phrase, reading ?: phrase, definitionText, formatted, wordIndex)
                                )
                            }
                        }
                    }
                }

                furiganaLyrics.add(lineReadings.joinToString(" • "))

                if (lineJishoWords.isNotEmpty()) {
                    preparedLineSets[index] = JishoLineSet(lineText, lineJishoWords)
                }

                // Log slow lines (anything taking more than 100ms)
                val lineTotal = System.currentTimeMillis() - lineStart
                if (lineTotal > 100) {
                    Log.w("Lyrisync", "Slow line [$index] took ${lineTotal}ms (Extraction: ${extractDuration}ms)")
                }
            }

            val totalProcessingTime = System.currentTimeMillis() - loopStart
            Log.d("Lyrisync", "Total Loop Processing: ${totalProcessingTime}ms")
            Log.d("Lyrisync", "Average per line: ${totalProcessingTime / lyrics.size}ms")

            withContext(Dispatchers.Main) {
                val uiStart = System.currentTimeMillis()
                lyricAdapter?.updateData(lyrics, translatedLyrics, furiganaLyrics, highlightsList)
                Log.d("Lyrisync", "UI Update took: ${System.currentTimeMillis() - uiStart}ms")
                Log.i("Lyrisync", "TOTAL PREFETCH TIME: ${System.currentTimeMillis() - startTime}ms")
            }
        }}

    private fun displayPreparedLine(lineIndex: Int) {
        val preparedBox = preparedLineSets[lineIndex]

        if (preparedBox != null) {
            Log.d("Lyrisync-Debug", "displayPreparedLine: Found box for index $lineIndex")
            jishoHistory.add(0, preparedBox)

            runOnUiThread {
                jishoAdapter.notifyItemInserted(0)
                if (isSyncEnabled) {
                    val jishoRv = findViewById<RecyclerView>(R.id.jishoRecyclerView)
                    jishoRv.scrollToPosition(0)
                }
            }
        } else {
            Log.d("Lyrisync-Debug", "displayPreparedLine: NO DATA for index $lineIndex. (Blank line or still loading)")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        actionBar?.hide()
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.songTitleText)?.text = "App Started! Connecting..."
        Log.d("Lyrisync", "onCreate finished")

        // --- 1. SETUP MAIN CONTENT LISTS ---
        val recyclerView = findViewById<RecyclerView>(R.id.lyricRecyclerView)
        val snapHelper = androidx.recyclerview.widget.LinearSnapHelper()
        snapHelper.attachToRecyclerView(recyclerView)
        lyricAdapter = LyricAdapter()
        recyclerView.adapter = lyricAdapter
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        val jishoRv = findViewById<RecyclerView>(R.id.jishoRecyclerView)
        jishoAdapter = JishoHistoryAdapter(jishoHistory)
        jishoRv.adapter = jishoAdapter
        jishoRv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        // --- 2. SETUP SYNC TOGGLE ---
        val syncBtn = findViewById<ToggleButton>(R.id.syncToggleButton)
        syncBtn.setOnCheckedChangeListener { _, isChecked ->
            isSyncEnabled = isChecked
            if (isChecked) {
                syncBtn.backgroundTintList = android.content.res.ColorStateList.valueOf("#1DB954".toColorInt())
            } else {
                syncBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.GRAY)
            }
        }

        // --- 3. BULLETPROOF BOTTOM NAVIGATION ---
        val bottomNavigationView = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    findViewById<RecyclerView>(R.id.lyricRecyclerView).smoothScrollToPosition(0)
                    true
                }
                R.id.nav_search -> {
                    android.widget.Toast.makeText(this, "Search coming soon!", android.widget.Toast.LENGTH_SHORT).show()
                    false
                }
                R.id.nav_settings -> {
                    val intent = android.content.Intent(this, SettingsActivity::class.java)

                    // Modern AndroidX Animation handling (Works on all Android versions)
                    val options = androidx.core.app.ActivityOptionsCompat.makeCustomAnimation(
                        this,
                        R.anim.slide_in_right,
                        R.anim.slide_out_left
                    )

                    startActivity(intent, options.toBundle())
                    false
                }
                else -> false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                connected()
            }
            override fun onFailure(throwable: Throwable) {
                Log.e("Lyrisync", "Connection failed: ${throwable.message}", throwable)
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
                    activeIndex = -1
                    findViewById<TextView>(R.id.songTitleText).text = track.name
                    findViewById<TextView>(R.id.artistNameText).text = track.artist.name
                    fetchLyrics(track.name, track.artist.name)
                }

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
                val jpRegex = Regex("[\\u3040-\\u30ff\\u4e00-\\u9faf]")

                val bestMatch = response
                    .filter { it.syncedLyrics != null }
                    .firstOrNull { it.syncedLyrics?.contains(jpRegex) == true }
                    ?: response.firstOrNull { it.syncedLyrics != null }

                if (bestMatch != null) {
                    parsedLyrics = parseLrc(bestMatch.syncedLyrics!!)

                    val fullJapaneseText = parsedLyrics.joinToString("\n") { it.text }
                    val translationResponse = translationService.getTranslation(q = fullJapaneseText)
                    val bulkResult = extractTextFromGoogle(translationResponse)
                    translatedLyrics = bulkResult.split("\n")

                    prefetchSongDictionary(parsedLyrics)

                    withContext(Dispatchers.Main) {
                        Log.d("Lyrisync-Debug", "0. Pushing empty lists to UI while DB loads")
                        lyricAdapter?.updateData(parsedLyrics, translatedLyrics, emptyList(), emptyList())
                    }
                } else {
                    parsedLyrics = listOf()
                    withContext(Dispatchers.Main) {
                        lyricAdapter?.updateData(parsedLyrics, listOf(), emptyList(), emptyList())
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
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
    }

    override fun onResume() {
        super.onResume()
        val sharedPrefs = getSharedPreferences("LyriSyncPrefs", Context.MODE_PRIVATE)
        val wipeRequested = sharedPrefs.getBoolean("WIPE_REQUESTED", false)
        if (wipeRequested) {
            jishoHistory.clear()
            jishoAdapter?.notifyDataSetChanged()
            sharedPrefs.edit().putBoolean("WIPE_REQUESTED", false).apply()
        }

        val refreshLyricsRequested = sharedPrefs.getBoolean("REFRESH_LYRICS_REQUESTED", false)
        if (refreshLyricsRequested) {
            findViewById<RecyclerView>(R.id.lyricRecyclerView).adapter?.notifyDataSetChanged()
            sharedPrefs.edit().putBoolean("REFRESH_LYRICS_REQUESTED", false).apply()
        }
    }
}

fun parseLrc(lrcContent: String): List<LyricLine> {
    val lyricList = mutableListOf<LyricLine>()
    val lines = lrcContent.split("\n")
    val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2})](.*)")

    for (line in lines) {
        val match = regex.find(line)
        if (match != null) {
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val ms = match.groupValues[3].toLong() * 10
            val text = match.groupValues[4].trim()

            val totalMs = (min * 60 * 1000) + (sec * 1000) + ms
            lyricList.add(LyricLine(totalMs, text))
        }
    }
    return lyricList.sortedBy { it.timeMs }
}

class JishoHistoryAdapter(private val history: List<JishoLineSet>) :
    RecyclerView.Adapter<JishoHistoryAdapter.ViewHolder>() {

    // Same Color Palette as the Lyrics!
    private val highlightColors = intArrayOf(
        "#FFD54F".toColorInt(), // Yellow
        "#81C784".toColorInt(), // Green
        "#64B5F6".toColorInt(), // Blue
        "#E57373".toColorInt(), // Red
        "#BA68C8".toColorInt()  // Purple
    )
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
        holder.lineHeader.text = item.lyricText
        holder.container.removeAllViews()

        val context = holder.itemView.context

        // Loop through our new JishoWord objects
        for (jishoWord in item.words) {
            val smallBox = TextView(context).apply {
                text = jishoWord.formattedText
                textSize = 15f
                setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
                setPadding(32, 24, 32, 24)

                val marginParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                marginParams.setMargins(0, 0, 0, 16)
                layoutParams = marginParams

                // 1. Color Match: Set the border stroke to match the lyric highlight!
                val assignedColor = highlightColors[jishoWord.wordIndex % highlightColors.size]
                val drawable = android.graphics.drawable.GradientDrawable()
                drawable.setColor("#383838".toColorInt())
                drawable.cornerRadius = 16f
                drawable.setStroke(4, assignedColor) // 4px colored border
                background = drawable

                // 2. Anki Integration: Make the box clickable
                isClickable = true
                isFocusable = true

                // Add a subtle ripple effect when tapped
                val typedValue = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                foreground = androidx.core.content.ContextCompat.getDrawable(context, typedValue.resourceId)

                setOnClickListener {
                    // Format the text for the flashcard
                    val flashcardText = "${jishoWord.phrase} [${jishoWord.reading}]\n\n${jishoWord.meaning}"

                    // Create an Android Share Intent (AnkiDroid will appear in this list!)
                    val sendIntent = android.content.Intent().apply {
                        action = android.content.Intent.ACTION_SEND
                        putExtra(android.content.Intent.EXTRA_TEXT, flashcardText)
                        type = "text/plain"
                    }
                    val shareIntent = android.content.Intent.createChooser(sendIntent, "Send to Anki")
                    context.startActivity(shareIntent)
                }
            }
            holder.container.addView(smallBox)
        }
    }
    override fun getItemCount() = history.size
}