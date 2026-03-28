package com.mixtapeo.lyrisync

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ToggleButton
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.net.Uri
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Toast
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
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.ProgressBar
import com.spotify.android.appremote.api.error.SpotifyConnectionTerminatedException
import coil.load
import com.spotify.protocol.client.error.RemoteClientException
import com.atilika.kuromoji.ipadic.Tokenizer
private val mainHandler = Handler(Looper.getMainLooper())

data class LrcResponse(
    val id: Int,
    val name: String,
    val artistName: String,
    val syncedLyrics: String?,
    val plainLyrics: String?
)

data class LyricLine(
    val timeMs: Long, val text: String
)

interface LrcLibService {
    @GET("api/search")
    suspend fun searchLyrics(
        @ApiQuery("track_name") track: String, @ApiQuery("artist_name") artist: String
    ): List<LrcResponse>

    // for search function
    @GET("api/search")
    suspend fun searchGeneral(
        @ApiQuery("q") query: String
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

@Dao
interface JishoDao {
    @SqlQuery("SELECT * FROM dictionary WHERE kanji = :query OR reading = :query LIMIT 1")
    fun getDefinition(query: String): JishoEntry?
}

@Entity(
    tableName = "dictionary",
    indices = [androidx.room.Index(value = ["kanji"], name = "index_kanji"), androidx.room.Index(
        value = ["reading"], name = "index_reading"
    )]
)
data class JishoEntry(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "kanji") val kanji: String?,
    @ColumnInfo(name = "reading") val reading: String?,
    @ColumnInfo(name = "meanings") val definition: String?
)

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
    val lyricText: String, val words: List<JishoWord>
)

@Database(entities = [JishoEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun jishoDao(): JishoDao

    companion object {
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "jmdict.db"
                ).createFromAsset("databases/jmdict.db").fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

private val lrcService: LrcLibService by lazy {
    retrofit2.Retrofit.Builder().baseUrl("https://lrclib.net/")
        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create()).build()
        .create(LrcLibService::class.java)
}
private val translationService: TranslationService by lazy {
    retrofit2.Retrofit.Builder().baseUrl("https://translate.googleapis.com/")
        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create()).build()
        .create(TranslationService::class.java)
}
private val queryCache = mutableMapOf<String, JishoEntry?>()
private val jpCharacterRegex = Regex("[\\u3040-\\u30ff\\u4e00-\\u9faf]")
private val singleKanaRegex = Regex("[\\u3040-\\u30ff]")

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
    private val viewModel: LyriSyncViewModel by viewModels()
    private var reconnectTry = 0
    private val maxRetries = 3
    private var connectionMonitorJob: Job? = null
    private var isConnecting = false
    private var currentTrackUri: String? = null
    private var activeIndex = -1

    @SuppressLint("SetTextI18n", "NotifyDataSetChanged")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // remove status and navbar of android (fullscreen app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Make app draw edge-to-edge
            // WindowCompat.setDecorFitsSystemWindows(window, false)

            // Get controller
            val controller = WindowCompat.getInsetsController(window, window.decorView)

            // Optional: allow swipe to temporarily show bars
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            // Hide status + navigation bars (fullscreen)
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN
        }

        findViewById<TextView>(R.id.songTitleText)?.text = "Waiting for Spotify..."
        Log.d("Lyrisync", "onCreate finished")

        // setup settings views
        val radioGroupSubtitle = findViewById<RadioGroup>(R.id.spinnerSubtitleMode)
        val btnClearHistory = findViewById<Button>(R.id.wipeHistoryButton)
        val sharedPrefs = getSharedPreferences("LyriSyncPrefs", MODE_PRIVATE)

        // --- Setup Subtitle Radio Logic ---
        val idToIndex = mapOf(
            R.id.radioNone to 0,
            R.id.radioFurigana to 1,
            R.id.radioBoth to 2,
            R.id.radioEnglish to 3
        )
        val indexToId = idToIndex.entries.associate { it.value to it.key }

        // Load saved state
        val savedSubtitleMode = sharedPrefs.getInt("SUBTITLE_MODE", 2)
        indexToId[savedSubtitleMode]?.let { radioGroupSubtitle.check(it) }

        radioGroupSubtitle.setOnCheckedChangeListener { _, checkedId ->
            val position = idToIndex[checkedId] ?: 2
            Log.i("LyriSync", "Subtitle mode changed: $position")

            sharedPrefs.edit {
                putInt("SUBTITLE_MODE", position)
                    .putBoolean("REFRESH_LYRICS_REQUESTED", true)
            }

            // Trigger an immediate refresh of the list if lyrics are already loaded
            lyricAdapter?.notifyDataSetChanged()
        }

        // --- Setup Wipe History Logic ---
        btnClearHistory.setOnClickListener {
            Log.w("LyriSync", "Wipe History requested.")
            jishoHistory.clear()
            jishoAdapter.notifyDataSetChanged()
            Toast.makeText(this, "History cleared!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnGithub).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mixtapeo"))
            startActivity(intent)
        }

        // --- 1. SETUP MAIN CONTENT LISTS ---
        val recyclerView = findViewById<RecyclerView>(R.id.lyricRecyclerView)
        val snapHelper = androidx.recyclerview.widget.LinearSnapHelper()
        snapHelper.attachToRecyclerView(recyclerView)
        lyricAdapter = LyricAdapter { clickedIndex ->
            focusLine(clickedIndex)
        }
        recyclerView.adapter = lyricAdapter
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        val jishoRv = findViewById<RecyclerView>(R.id.jishoRecyclerView)
        jishoAdapter = JishoHistoryAdapter(jishoHistory)
        jishoRv.adapter = jishoAdapter
        jishoRv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        // --- SETUP DEFINITION LIMIT SLIDER ---
        val defSlider = findViewById<com.google.android.material.slider.Slider>(R.id.slider)

        // Load the saved state (Default to 3 definitions if they haven't touched it)
        val savedLimit = sharedPrefs.getInt("DEF_LIMIT", 3)
        defSlider.value = savedLimit.toFloat()

        // Pass initial value to the adapter
        jishoAdapter.definitionLimit = savedLimit

        // Listen for drags
        defSlider.addOnChangeListener { _, value, fromUser ->
            val newLimit = value.toInt()

            // 1. TRIGGER VIBRATION
            // We only vibrate if the change came from the user's finger
            if (fromUser) {
                Log.d("Lyrisync", "Slider changed to $newLimit")
                defSlider.performHapticFeedback(
                    android.view.HapticFeedbackConstants.CLOCK_TICK,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING // Optional: ensures it vibrates even if touch feedback is off
                )
            }

            // 2. EXISTING LOGIC
            sharedPrefs.edit().putInt("DEF_LIMIT", newLimit).apply()
            jishoAdapter.definitionLimit = newLimit
            jishoAdapter.notifyDataSetChanged()
        }
        // OBSERVE: When the activeIndex changes, update ONLY the affected rows
        lifecycleScope.launch {
            viewModel.activeIndex.collect { newIndex ->
                val oldIndex = lyricAdapter?.activeIndex ?: -1

                // Update the adapter's internal state
                lyricAdapter?.activeIndex = newIndex

                // OPTIMIZATION: Only refresh the two lines that changed!
                if (oldIndex != -1) lyricAdapter?.notifyItemChanged(oldIndex)
                if (newIndex != -1) {
                    lyricAdapter?.notifyItemChanged(newIndex)

                    // Auto-scroll and Dict Box logic
                    findViewById<RecyclerView>(R.id.lyricRecyclerView).smoothScrollToPosition(
                        newIndex
                    )
                    displayPreparedLine(newIndex)
                }
            }
        }

        // --- 2. SETUP SYNC TOGGLE & ANIMATION ---
        val syncBtn = findViewById<ToggleButton>(R.id.syncToggleButton)
        findViewById<TextView>(R.id.floatingWarningText) // Grab the new text
        syncBtn.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                viewModel.setSource(LyricSource.SPOTIFY)

                // Force a re-fetch of the ACTUAL Spotify song
                spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
                    val track = playerState.track
                    if (track != null) {
                        // fetchLyrics(track.name, track.artist.name)
                    }
                }
            } else {
                viewModel.setSource(LyricSource.MANUAL)
            }
        }
        // Set the initial default tooltip
        androidx.appcompat.widget.TooltipCompat.setTooltipText(
            syncBtn, "Click to pause LyriSync from scrolling on touch"
        )

        // --- 3. BOTTOM NAVIGATION ---
        // The .post block waits for the UI to measure itself before doing math
        val homeScreen = findViewById<View>(R.id.homeScreen)
        val settingsScreen = findViewById<View>(R.id.settingsScreen)
        val searchScreen = findViewById<View>(R.id.searchScreen)
        val bottomNavigationView =
            findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)

        // Setup Search UI
        val searchInput = findViewById<android.widget.EditText>(R.id.searchInput)
        val searchRv =
            findViewById<RecyclerView>(R.id.searchResultsRecyclerView)
        val searchAdapter = SearchAdapter { selectedResult ->
            loadManualSearchResult(selectedResult)
        }
        searchRv.adapter = searchAdapter
        searchRv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        // Listen for the "Enter/Search" key on the keyboard
        searchInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val query = searchInput.text.toString()
                if (query.isNotBlank()) {
                    // Hide the keyboard
                    val imm =
                        getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)

                    // Fire the API Call
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val results = lrcService.searchGeneral(query)
                            withContext(Dispatchers.Main) {
                                searchAdapter.updateData(results)
                            }
                        } catch (e: Exception) {
                            Log.e("Lyrisync", "Search failed", e)
                        }
                    }
                }
                true
            } else {
                false
            }
        }

        var lastSelectedTab = R.id.nav_home // Default to home
        homeScreen.post {
            val trueWidth = homeScreen.width.toFloat()
            val trueHeight = homeScreen.height.toFloat() // We need Height for Y-Axis sliding!
            var isSearchOpen = false

            // Initial State setup
            if (bottomNavigationView.selectedItemId == R.id.nav_settings) {
                homeScreen.translationX = -trueWidth
                settingsScreen.translationX = 0f
                searchScreen.translationY = trueHeight
            } else {
                homeScreen.translationX = 0f
                settingsScreen.translationX = trueWidth
                searchScreen.translationY = trueHeight // Hide Search below the screen
            }

            bottomNavigationView.setOnItemSelectedListener { item ->
                val trueWidth = homeScreen.width.toFloat()
                val trueHeight = homeScreen.height.toFloat()

                when (item.itemId) {
                    R.id.nav_home -> {
                        if (isSearchOpen) {
                            searchScreen.animate().translationY(trueHeight).setDuration(300).start()
                            isSearchOpen = false
                        }
                        lastSelectedTab = R.id.nav_home // REMEMBER HOME
                        homeScreen.animate().translationX(0f).setDuration(300).start()
                        settingsScreen.animate().translationX(trueWidth).setDuration(300).start()
                        true
                    }

                    R.id.nav_settings -> {
                        if (isSearchOpen) {
                            searchScreen.animate().translationY(trueHeight).setDuration(300).start()
                            isSearchOpen = false
                        }
                        lastSelectedTab = R.id.nav_settings // REMEMBER SETTINGS
                        homeScreen.animate().translationX(-trueWidth).setDuration(300).start()
                        settingsScreen.animate().translationX(0f).setDuration(300).start()
                        true
                    }

                    R.id.nav_search -> {
                        if (isSearchOpen) {
                            // CLOSE: Slide down
                            searchScreen.animate().translationY(trueHeight).setDuration(300).start()
                            isSearchOpen = false

                            // RETURN TO PREVIOUS TAB
                            bottomNavigationView.post {
                                bottomNavigationView.selectedItemId = lastSelectedTab
                            }
                            false // Don't highlight search anymore
                        } else {
                            // OPEN: Slide up
                            searchScreen.visibility = View.VISIBLE
                            searchScreen.animate().translationY(0f).setDuration(300).start()
                            isSearchOpen = true
                            true // Highlight search while it's open
                        }
                    }

                    else -> false
                }
            }
        }
        // --- BACK BUTTON INTERCEPTOR ---
        onBackPressedDispatcher.addCallback(
            this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // If we are NOT on the home tab, slide back to home
                    if (bottomNavigationView.selectedItemId != R.id.nav_home) {
                        bottomNavigationView.selectedItemId =
                            R.id.nav_home // This automatically triggers the slide animation!
                    } else {
                        // If we ARE on home, let the app close normally
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            })

        // first run setup
        val isFirstRun = sharedPrefs.getBoolean("IS_FIRST_RUN", true)
        if (isFirstRun) {
            showFirstStartDialog(sharedPrefs)
        }
    }

    override fun onStart() {
        super.onStart()
        val sharedPrefs = getSharedPreferences("LyriSyncPrefs", Context.MODE_PRIVATE)
        val isFirstRun = sharedPrefs.getBoolean("IS_FIRST_RUN", true)

        if (!isFirstRun) {
            // Try to connect silently first. Do NOT force the auth view yet.
            reconnectToSpotify(forceAuthView = false)
        }
    }

    override fun onStop() {
        super.onStop()
        connectionMonitorJob?.cancel() // Stop checking when app is hidden
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
    }

    override fun onResume() {
        super.onResume()
        val sharedPrefs = getSharedPreferences("LyriSyncPrefs", MODE_PRIVATE)
        val wipeRequested = sharedPrefs.getBoolean("WIPE_REQUESTED", false)
        if (wipeRequested) {
            jishoHistory.clear()
            jishoAdapter?.notifyDataSetChanged()
            sharedPrefs.edit().putBoolean("WIPE_REQUESTED", false).apply()
        }

        val refreshLyricsRequested = sharedPrefs.getBoolean("REFRESH_LYRICS_REQUESTED", false)
        if (refreshLyricsRequested) {
            findViewById<RecyclerView>(R.id.lyricRecyclerView).adapter?.notifyDataSetChanged()
            sharedPrefs.edit { putBoolean("REFRESH_LYRICS_REQUESTED", false) }
        }
    }

    // <------- core funcs ------->

    private fun reconnectToSpotify(forceAuthView: Boolean = false) {
        if (isConnecting) return
        isConnecting = true

        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(forceAuthView) // <--- Now it's dynamic!
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                isConnecting = false
                reconnectTry = 0
                spotifyAppRemote = appRemote
                connected()
            }

            override fun onFailure(throwable: Throwable) {
                isConnecting = false

                val rootCause = throwable.cause
                if (throwable is RemoteClientException || rootCause is RemoteClientException) {
                    // Silent auth failed. Ask the user to click the button to authorize.
                    runOnUiThread { showSpotifyAuthDialog() }
                } else if (throwable is SpotifyConnectionTerminatedException && reconnectTry < maxRetries) {
                    reconnectTry++
                    mainHandler.postDelayed({ reconnectToSpotify(forceAuthView) }, 1000)
                } else {
                    Log.e("Lyrisync", "Connection failed: ${throwable.message}")
                    runOnUiThread {
                        findViewById<TextView>(R.id.songTitleText)?.text =
                            "Connection Failed: ${throwable.message}"
                    }
                }
            }
        })
    }

    private fun startSyncLoop() {
        syncJob = lifecycleScope.launch {
            while (isActive) {
                spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
                    runOnUiThread {
                        syncLyricsToPosition(playerState.playbackPosition)
                    }
                }
                delay(32)
            }
        }
    }

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

    // <------- support funcs ------->
    // Setup Coil ImageLoader with GIF support
    private fun showFirstStartDialog(prefs: android.content.SharedPreferences) {
        val overlay = findViewById<View>(R.id.onboardingOverlay)
        val gifVideo = findViewById<ImageView>(R.id.gifVideo)
        val gifBroadcastView = findViewById<ImageView>(R.id.gifBroadcast)
        val btnOk = findViewById<Button>(R.id.btnOnboardingOk)
        val btnNever = findViewById<Button>(R.id.btnOnboardingNever)

        overlay.visibility = View.VISIBLE

        // 1. Create a specialized Loader for animations
        val animationLoader = coil.ImageLoader.Builder(this)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    add(coil.decode.GifDecoder.Factory())
                }
            }
            .build()

        // 2. Load using that specific loader
        gifVideo.load(R.raw.gif2, animationLoader)
        gifBroadcastView.load(R.raw.gif1, animationLoader)

        btnOk.setOnClickListener {
            overlay.visibility = View.GONE
            reconnectToSpotify()
        }

        btnNever.setOnClickListener {
            prefs.edit().putBoolean("IS_FIRST_RUN", false).apply()
            overlay.visibility = View.GONE
            reconnectToSpotify()
        }
    }

    private fun showSpotifyAuthDialog() {
        val overlay = findViewById<View>(R.id.SpotifyAuthRequest)
        val videoAuth = findViewById<ImageView>(R.id.videoAuth)
        val btnAuthOk = findViewById<Button>(R.id.btnAuthOk)
        val btnAuthNever = findViewById<Button>(R.id.btnAuthNever)

        overlay.visibility = View.VISIBLE

        val animationLoader = coil.ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory())
                else add(coil.decode.GifDecoder.Factory())
            }.build()

        videoAuth.load(R.raw.gif1, animationLoader)

        btnAuthOk.setOnClickListener {
            overlay.visibility = View.GONE


            val spotifyPackage = "com.spotify.music"
            val launchIntent = packageManager.getLaunchIntentForPackage(spotifyPackage)

            if (launchIntent != null) {
                // Bring Spotify to the front so the OS doesn't block it
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(launchIntent)

                // Wait 2 seconds for Spotify to open, then connect WITH the Auth View forced ON
                mainHandler.postDelayed({
                    reconnectToSpotify(forceAuthView = true)
                }, 2000)

            } else {
                Toast.makeText(this, "Spotify not installed.", Toast.LENGTH_LONG).show()
            }
        }

        btnAuthNever.setOnClickListener {
            overlay.visibility = View.GONE
        }
    }

    private fun fetchLyrics(title: String, artist: String) {
        runOnUiThread {
            findViewById<ProgressBar>(R.id.loadingCircle).visibility = View.VISIBLE
            // 2. CLEAR EVERYTHING CURRENT
            parsedLyrics = emptyList()
            translatedLyrics = emptyList()
            preparedLineSets.clear()
            songDictionary.clear()

            // 3. Tell the UI to go blank
            lyricAdapter?.updateData(emptyList(), emptyList(), emptyList(), emptyList())

            // 4. Reset the highlighted line
            activeIndex = -1
        }

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.IO) {
                try {
                    val response = lrcService.searchLyrics(title, artist)
                    val jpRegex = Regex("[\\u3040-\\u30ff\\u4e00-\\u9faf]")

                    val bestMatch = response.filter { it.syncedLyrics != null }
                        .firstOrNull { it.syncedLyrics?.contains(jpRegex) == true }
                        ?: response.firstOrNull { it.syncedLyrics != null }

                    if (bestMatch != null) {
                        parsedLyrics = parseLrc(bestMatch.syncedLyrics!!)
                        Log.d("LyriSync-debug", "Lyrics are: $parsedLyrics")
                        val fullJapaneseText = parsedLyrics.joinToString("\n") { it.text }
                        val translationResponse =
                            translationService.getTranslation(q = fullJapaneseText)
                        val bulkResult = extractTextFromGoogle(translationResponse)
                        translatedLyrics = bulkResult.split("\n")

                        prefetchSongDictionary(parsedLyrics)

                        withContext(Dispatchers.Main) {
                            Log.d("Lyrisync-Debug", "0. Pushing empty lists to UI while DB loads")
                            lyricAdapter?.updateData(
                                parsedLyrics, translatedLyrics, emptyList(), emptyList()
                            )
                            // no lyrics text
                            findViewById<TextView>(R.id.NoLyricsText).visibility = View.GONE
                        }
                    } else {
                        // Hide if no lyrics found
                        withContext(Dispatchers.Main) {
                            findViewById<ProgressBar>(R.id.loadingCircle).visibility = View.GONE
                            lyricAdapter?.updateData(listOf(), listOf(), emptyList(), emptyList())
                            findViewById<TextView>(R.id.NoLyricsText).visibility = View.VISIBLE
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Lyrisync", "Fetch failed: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        findViewById<ProgressBar>(R.id.loadingCircle).visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun syncLyricsToPosition(currentMs: Long) {
        // Only sync if Spotify is the current source
        if (viewModel.source.value != LyricSource.SPOTIFY) return

        val index = parsedLyrics.indexOfLast { it.timeMs <= currentMs }

        if (index != -1 && index != activeIndex) {
            activeIndex = index // Keep local sync for safety
            viewModel.updateActiveIndex(index) // This triggers the observer in onCreate
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

    @SuppressLint("NotifyDataSetChanged")
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

            // 1. Initialize Kuromoji Tokenizer
            // Done on the IO dispatcher because loading the dictionary takes a moment
            val tokenizer = Tokenizer()

            Log.d("Lyrisync", "DB/DAO & Tokenizer Init took: ${System.currentTimeMillis() - dbLoadStart}ms")

            val furiganaLyrics = mutableListOf<String>()
            val highlightsList = mutableListOf<List<String>>()

            preparedLineSets.clear()
            songDictionary.clear()
            queryCache.clear()

            val jpCharacterRegex = Regex("[\\u3040-\\u30ff\\u4e00-\\u9faf]")
            val loopStart = System.currentTimeMillis()

            for ((index, line) in lyrics.withIndex()) {
                val lineStart = System.currentTimeMillis()
                val lineText = line.text

                if (lineText.isBlank()) {
                    highlightsList.add(emptyList())
                    furiganaLyrics.add("")
                    continue
                }

                val lineWords = mutableListOf<String>()
                val lineReadings = mutableListOf<String>()
                val lineJishoWords = mutableListOf<JishoWord>()
                var wordIndex = 0

                // 2. Tokenize the entire line at once
                val tokens = tokenizer.tokenize(lineText)

                tokens.forEach { token ->
                    val surface = token.surface // The exact word as it appears in the song (e.g., "走っ")
                    val baseForm = token.baseForm ?: surface // The dictionary form (e.g., "走る")
                    val pos1 = token.partOfSpeechLevel1 // e.g., Noun (名詞), Particle (助詞), etc.

                    // FAST FAIL 1: Skip punctuation (記号) or purely non-Japanese segments
                    if (pos1 == "記号" || !surface.contains(jpCharacterRegex)) {
                        lineReadings.add(surface)
                        return@forEach // acts like 'continue' in a standard loop
                    }

                    // FAST FAIL 2: Safely skip particles (助詞) and auxiliary verbs (助動詞)
                    if (pos1 == "助詞" || pos1 == "助動詞") {
                        lineReadings.add(surface)
                        return@forEach
                    }

                    // MEMORY CACHE: Query your DB using the BASE FORM, not the conjugated surface form!
                    val entry = if (queryCache.containsKey(baseForm)) {
                        queryCache[baseForm]
                    } else {
                        val dbResult = dao.getDefinition(baseForm)
                        queryCache[baseForm] = dbResult
                        dbResult
                    }

                    if (entry != null) {
                        // Highlight the word exactly as it appears in the lyric text
                        lineWords.add(surface)

                        // Prefer DB reading for proper Hiragana format, fallback to Kuromoji reading (Katakana), then surface
                        val reading = entry.reading ?: token.reading ?: surface
                        lineReadings.add(reading)

                        val definitionText = entry.definition
                        if (!definitionText.isNullOrBlank()) {
                            if (!songDictionary.containsKey(baseForm)) {
                                val spannable = android.text.SpannableStringBuilder()
                                val displayReading = entry.reading ?: baseForm
                                spannable.append("【 $baseForm 】 ($displayReading)\n→ $definitionText\n\n")
                                songDictionary[baseForm] = spannable
                            }

                            songDictionary[baseForm]?.let { formatted ->
                                lineJishoWords.add(
                                    JishoWord(
                                        baseForm,
                                        reading,
                                        definitionText,
                                        formatted,
                                        wordIndex
                                    )
                                )
                            }
                            wordIndex++
                        }
                    } else {
                        // Word is valid but not in your DB, just append its reading for Furigana
                        lineReadings.add(token.reading ?: surface)
                    }
                }

                highlightsList.add(lineWords)
                furiganaLyrics.add(lineReadings.joinToString(" • "))

                if (lineJishoWords.isNotEmpty()) {
                    preparedLineSets[index] = JishoLineSet(lineText, lineJishoWords)
                }

                val lineTotal = System.currentTimeMillis() - lineStart
                if (lineTotal > 100) {
                    Log.w("Lyrisync", "Slow line [$index] took ${lineTotal}ms")
                }
            }

            val totalProcessingTime = System.currentTimeMillis() - loopStart
            Log.d("Lyrisync", "Total Loop Processing: ${totalProcessingTime}ms")

            withContext(Dispatchers.Main) {
                val uiStart = System.currentTimeMillis()
                lyricAdapter?.updateData(lyrics, translatedLyrics, furiganaLyrics, highlightsList)

                // HIDE THE SPINNER HERE
                findViewById<ProgressBar>(R.id.loadingCircle).visibility = View.GONE

                Log.d("Lyrisync", "UI Update took: ${System.currentTimeMillis() - uiStart}ms")
                Log.i("Lyrisync", "TOTAL PREFETCH TIME: ${System.currentTimeMillis() - startTime}ms")
            }
        }
    }

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
            Log.d(
                "Lyrisync-Debug",
                "displayPreparedLine: NO DATA for index $lineIndex. (Blank line or still loading)"
            )
        }
    }

    private fun loadManualSearchResult(selectedMatch: LrcResponse) {
        findViewById<ProgressBar>(R.id.loadingCircle).visibility = View.VISIBLE
        viewModel.setSource(LyricSource.MANUAL) // Switch to Manual
        // 1. Close Search Drawer and go back to Home Screen
        val bottomNavigationView =
            findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)
        bottomNavigationView.selectedItemId = R.id.nav_home

        // 2. Force Sync Toggle to OFF (Manual Mode)
        val syncBtn = findViewById<ToggleButton>(R.id.syncToggleButton)
        // UI Updates
        syncBtn.isChecked = false
        syncBtn.backgroundTintList = ColorStateList.valueOf(Color.GRAY)

        // 3. Update the Top Bar Metadata
        runOnUiThread {
            findViewById<TextView>(R.id.songTitleText).text = selectedMatch.name
            findViewById<TextView>(R.id.artistNameText).text = selectedMatch.artistName
            findViewById<TextView>(R.id.NoLyricsText).visibility = View.GONE
        }

        // 4. Process the Lyrics just like the normal flow!
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Handle both synced LRC and plain text lyrics
                val rawLyrics = selectedMatch.syncedLyrics ?: selectedMatch.plainLyrics ?: ""
                parsedLyrics = if (selectedMatch.syncedLyrics != null) {
                    parseLrc(rawLyrics)
                } else {
                    // If it's plain text, generate dummy timestamps so the UI still displays them
                    rawLyrics.split("\n").mapIndexed { index, text ->
                        LyricLine(index.toLong(), text.trim())
                    }.filter { it.text.isNotBlank() }
                }

                // Call Google Translate
                val fullJapaneseText = parsedLyrics.joinToString("\n") { it.text }
                if (fullJapaneseText.isNotBlank()) {
                    val translationResponse =
                        translationService.getTranslation(q = fullJapaneseText)
                    val bulkResult = extractTextFromGoogle(translationResponse)
                    translatedLyrics = bulkResult.split("\n")
                } else {
                    translatedLyrics = emptyList()
                }

                // Send to Database for Furigana and Underlines
                prefetchSongDictionary(parsedLyrics)

                withContext(Dispatchers.Main) {
                    // Update UI while we wait for the database
                    lyricAdapter?.updateData(
                        parsedLyrics, translatedLyrics, emptyList(), emptyList()
                    )
                    activeIndex = -1 // Reset the active highlight
                }
            } catch (e: Exception) {
                Log.e("Lyrisync", "Manual fetch failed: ${e.message}", e)
            }
        }
    }

    private fun focusLine(index: Int) {
        val currentSource = viewModel.source.value

        if (currentSource == LyricSource.SPOTIFY) {
            // OPTIONAL: Just scroll to it, but DON'T seek Spotify
            // or simply return and do nothing to prevent accidental jumps
            return
        }

        // MANUAL MODE LOGIC
        activeIndex = index
        viewModel.updateActiveIndex(index)

        // We explicitly DO NOT call spotifyAppRemote?.playerApi?.seekTo(targetMs) here
        // because we are in Manual mode.
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
}


class JishoHistoryAdapter(private val history: List<JishoLineSet>) :
    RecyclerView.Adapter<JishoHistoryAdapter.ViewHolder>() {
    var definitionLimit = 3

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
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.item_jisho_card, parent, false)
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

                // --- 1. THE CHOPPING LOGIC ---
                val limitedDefinitions = jishoWord.meaning.split(" / ")
                    .take(definitionLimit) // Use the live slider limit!
                    .joinToString(" / ")

                // --- 2. DYNAMIC REBUILD ---
                val spannable = android.text.SpannableStringBuilder()
                val displayReading = jishoWord.reading.ifBlank { jishoWord.phrase }
                spannable.append("【 ${jishoWord.phrase} 】 ($displayReading)\n→ $limitedDefinitions\n\n")

                text = spannable
                textSize = 15f
                setTextColor(Color.parseColor("#E0E0E0"))
                setPadding(32, 24, 32, 24)

                val marginParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                marginParams.setMargins(0, 0, 0, 16)
                layoutParams = marginParams

                // 3. Color Match
                val assignedColor = highlightColors[jishoWord.wordIndex % highlightColors.size]
                val drawable = android.graphics.drawable.GradientDrawable()
                drawable.setColor("#383838".toColorInt())
                drawable.cornerRadius = 16f
                drawable.setStroke(4, assignedColor)
                background = drawable

                // 4. Anki Integration
                isClickable = true
                isFocusable = true

                val typedValue = android.util.TypedValue()
                context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, typedValue, true
                )
                foreground =
                    androidx.core.content.ContextCompat.getDrawable(context, typedValue.resourceId)

                setOnClickListener {
                    // Send the newly limited text to Anki so you don't get bloated flashcards!
                    val flashcardText =
                        "${jishoWord.phrase} [${jishoWord.reading}]\n\n$limitedDefinitions"

                    val sendIntent = android.content.Intent().apply {
                        action = android.content.Intent.ACTION_SEND
                        putExtra(android.content.Intent.EXTRA_TEXT, flashcardText)
                        type = "text/plain"
                    }
                    val shareIntent =
                        android.content.Intent.createChooser(sendIntent, "Send to Anki")
                    context.startActivity(shareIntent)
                }
            }
            holder.container.addView(smallBox)
        }
    }

    override fun getItemCount() = history.size
}

class SearchAdapter(
    private var results: List<LrcResponse> = emptyList(),
    private val onItemClick: (LrcResponse) -> Unit // 1. Added a click listener function
) : RecyclerView.Adapter<SearchAdapter.ViewHolder>() {

    fun updateData(newResults: List<LrcResponse>) {
        this.results = newResults
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(android.R.id.text1)
        val artist: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = results[position]
        holder.title.text = item.name
        holder.title.setTextColor(Color.WHITE)
        holder.title.textSize = 16f

        holder.artist.text = "${item.artistName} • Has Synced Lyrics: ${item.syncedLyrics != null}"
        holder.artist.setTextColor(Color.parseColor("#A0A0A0"))

        // 2. Trigger the listener when the user taps this row
        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount() = results.size
}