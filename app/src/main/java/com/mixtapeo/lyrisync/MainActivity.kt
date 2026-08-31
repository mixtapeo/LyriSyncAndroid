package com.mixtapeo.lyrisync

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import coil.load
import com.atilika.kuromoji.ipadic.Tokenizer
import com.google.android.material.materialswitch.MaterialSwitch
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.error.RemoteClientException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query
import androidx.room.Query as SqlQuery
import retrofit2.http.Query as ApiQuery
import android.net.Uri
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import android.util.Log
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
private val mainHandler = Handler(Looper.getMainLooper())

data class HighlightSpan(
    val start: Int,
    val end: Int,
    val wordIndex: Int // Keeps the color synced with the JishoBox!
)

data class SpotifyPlaybackResponse(
    val is_playing: Boolean,
    val progress_ms: Long,
    val item: SpotifyTrack?
)

data class SpotifyTrack(
    val uri: String,
    val name: String,
    val artists: List<SpotifyArtist>
)

data class SpotifyArtist(val name: String)

interface SpotifyWebApi {
    @GET("v1/me/player")
    suspend fun getPlaybackState(): retrofit2.Response<SpotifyPlaybackResponse>
}

private val customOkHttpClient: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS) // Time to establish the connection
        .readTimeout(30, TimeUnit.SECONDS)    // Time to wait for the next byte of data
        .writeTimeout(30, TimeUnit.SECONDS)   // Time to wait while sending data
        .build()
}

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
    @Headers("User-Agent: LyriSync/1.0 (anirudhsridhar626@email.com)")
    @GET("api/search")
    suspend fun searchLyrics(
        @Query("track_name") track: String,
        @Query("artist_name") artist: String
    ): List<LrcResponse>

    // for search function
    @Headers("User-Agent: LyriSync/1.0 (anirudhsridhar626@email.com)")
    @GET("api/search")
    suspend fun searchGeneral(
        @Query("q") query: String
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
private val queryCache = mutableMapOf<String, JishoEntry?>()
private val globalAnkiCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
private val jpCharacterRegex = Regex("[\\u3040-\\u30ff\\u4e00-\\u9faf]")

class MainActivity : AppCompatActivity() {
    private val clientId = BuildConfig.SPOTIFY_CLIENT_ID
    private var myAccessToken: String? = BuildConfig.myAccessToken
    private var translatedLyrics = listOf<String>()
    private var lyricAdapter: LyricAdapter? = null
    private var parsedLyrics = listOf<LyricLine>()
    private var syncJob: Job? = null
    private val redirectUri = "lyrisync://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null
    private val songDictionary = mutableMapOf<String, CharSequence>()
    private var isSyncEnabled = true
    private val jishoHistory = mutableListOf<JishoLineSet>()
    private lateinit var jishoAdapter: JishoHistoryAdapter
    private val preparedLineSets = mutableMapOf<Int, JishoLineSet>()
    private val viewModel: LyriSyncViewModel by viewModels()
    private var reconnectTry = 0
    private var connectionMonitorJob: Job? = null
    private var isConnecting = false
    private var currentTrackUri: String? = null
    private var activeIndex = -1
    enum class SyncEngine { SDK, WEB_API }
    private var activeEngine = SyncEngine.SDK
    private var lastSdkPosition = -1L
    private var webApiProgressMs = -1L
    private var isWebPlaying = false
    private var universalSyncJob: Job? = null
    private fun updateAnkiMode(mode: Int) {
        val sharedPrefs = getSharedPreferences("LyriSyncPrefs", MODE_PRIVATE)

        // Check for permissions if we are moving to a non-zero mode
        if (mode != 0) {
            val permission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, permission)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ankiPermissionLauncher.launch(permission)
                return
            }
            // If we have permission and are enabling a mode, fetch the decks
            populateAnkiDecks()
        }

        Log.i("LyriSync", "Anki mode updated to: $mode")
        sharedPrefs.edit {
            putInt("ANKI_MODE", mode)
            putBoolean("REFRESH_LYRICS_REQUESTED", true)
        }

        // Refresh the lyrics display to show/hide words immediately
        globalAnkiCache.clear()
        if (parsedLyrics.isNotEmpty()) {
            prefetchSongDictionary(parsedLyrics)
        } else {
            lyricAdapter?.notifyDataSetChanged()
        }
    }
    fun checkNotificationPermission() {
        val isGranted = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)

        if (!isGranted) {
            // Redirect user to the Notification Access settings screen
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
            // You'll want to show a Toast or Dialog explaining WHY you need this first!
        } else {
            Log.d("LyriSync", "We have permission to read all media players!")
        }
    }
    private val ankiPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.i("LyriSync", "Anki permission granted!")
            Toast.makeText(this, "Anki sync enabled!", Toast.LENGTH_SHORT).show()
            populateAnkiDecks()
        } else {
            Log.w("LyriSync", "Anki permission denied.")
            Toast.makeText(this, "Permission required for Anki sync.", Toast.LENGTH_LONG).show()

            getSharedPreferences("LyriSyncPrefs", MODE_PRIVATE).edit { putInt("ANKI_MODE", 0) }
        }
    }
    @SuppressLint("DirectSystemCurrentTimeMillisUsage")
    private fun isWebTokenValid(): Boolean {
        val prefs = getSharedPreferences("LyriSyncPrefs", MODE_PRIVATE)
        val token = prefs.getString("SPOTIFY_TOKEN", null)
        val expireTime = prefs.getLong("SPOTIFY_TOKEN_EXPIRE_TIME", 0L)

        // Return false if there's no token, OR if the current time is past the expiration
        // (minus a 60-second safety buffer).
        return token != null && System.currentTimeMillis() < (expireTime - 60000)
    }
    private fun checkAndRefreshSpotifyToken() {
        if (isWebTokenValid()) {
            Log.d("LyriSync", "Token is still valid. Skipping auth popup.")
            // If it's valid, make sure the loop is running!
            if (syncJob == null || !syncJob!!.isActive) {
                startHybridSyncLoop()
            }
        } else {
            Log.w("LyriSync", "Token is dead or missing. Requesting new one...")
            requestFreshSpotifyToken()
        }
    }
    private val SPOTIFY_AUTH_REQUEST_CODE = 1337
    private var isRefreshingToken = false
    @SuppressLint("DirectSystemCurrentTimeMillisUsage")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SPOTIFY_AUTH_REQUEST_CODE) {
            isRefreshingToken = false
            val response = com.spotify.sdk.android.auth.AuthorizationClient.getResponse(resultCode, data)

            when (response.type) {
                com.spotify.sdk.android.auth.AuthorizationResponse.Type.TOKEN -> {
                    val freshToken = response.accessToken

                    // --- Calculate exact expiration time in milliseconds ---
                    // expiresIn is usually 3600 seconds. We multiply by 1000 for Ms.
                    val expireTimeMs = System.currentTimeMillis() + (response.expiresIn * 1000)

                    Log.d("LyriSync", "Got fresh token! Expires at timestamp: $expireTimeMs")

                    // Save BOTH the token and the expiration time
                    getSharedPreferences("LyriSyncPrefs", MODE_PRIVATE).edit {
                        putString("SPOTIFY_TOKEN", freshToken)
                        putLong("SPOTIFY_TOKEN_EXPIRE_TIME", expireTimeMs)
                    }

                    myAccessToken = freshToken
                    val banner = findViewById<com.google.android.material.card.MaterialCardView>(R.id.spotifyOfflineBanner)
                    if (banner.visibility == View.VISIBLE) {
                        banner.animate()
                            .alpha(0f)
                            .translationY(50f)
                            .setDuration(300)
                            .withEndAction {
                                banner.visibility = View.GONE
                            }
                            .start()
                    }
                    startHybridSyncLoop()
                    reconnectToSpotify()
                }
                com.spotify.sdk.android.auth.AuthorizationResponse.Type.ERROR -> {
                    Log.e("LyriSync", "Auth error: ${response.error}")
                    // Optional: Show a toast letting them know login failed
                    Toast.makeText(this, "Spotify Login Failed", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    Log.w("LyriSync", "Auth cancelled or unknown response.")
                }
            }
        }
    }
    private fun requestFreshSpotifyToken() {
        val builder = com.spotify.sdk.android.auth.AuthorizationRequest.Builder(
            clientId,
            com.spotify.sdk.android.auth.AuthorizationResponse.Type.TOKEN,
            redirectUri
        )

        builder.setScopes(arrayOf("user-read-playback-state", "user-modify-playback-state"))
        val request = builder.build()

        // This opens the Spotify login screen
        com.spotify.sdk.android.auth.AuthorizationClient.openLoginActivity(this, SPOTIFY_AUTH_REQUEST_CODE, request)
    }
    private val spotifyApiService: SpotifyWebApi by lazy {
        val interceptor = okhttp3.logging.HttpLoggingInterceptor().apply {
            level = okhttp3.logging.HttpLoggingInterceptor.Level.BODY
        }

        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                // NOW we can call getSharedPreferences because we are inside the Activity!
                val prefs = getSharedPreferences("LyriSyncPrefs", MODE_PRIVATE)
                val token = prefs.getString("SPOTIFY_TOKEN", BuildConfig.myAccessToken)

                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                chain.proceed(request)
            }
            .build()

        retrofit2.Retrofit.Builder()
            .baseUrl("https://api.spotify.com/") // Fixed: use the real Spotify URL here
            .client(client)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            .create(SpotifyWebApi::class.java)
    }
    private val lrcService: LrcLibService by lazy {
        retrofit2.Retrofit.Builder()
            .baseUrl("https://lrclib.net/")
            .client(customOkHttpClient)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            .create(LrcLibService::class.java)
    }
    private val translationService: TranslationService by lazy {
        retrofit2.Retrofit.Builder()
            .baseUrl("https://translate.googleapis.com/")
            .client(customOkHttpClient)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            .create(TranslationService::class.java)
    }

    private fun isNetworkConnected(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        // Returns true if connected to Wi-Fi, Cellular, or Ethernet
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    @SuppressLint("SetTextI18n", "NotifyDataSetChanged", "CutPasteId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. DECLARE ONCE AT THE TOP
        val sharedPrefs = getSharedPreferences("LyriSyncPrefs", MODE_PRIVATE)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        myAccessToken = sharedPrefs.getString("SPOTIFY_TOKEN", BuildConfig.myAccessToken)

        // 2. SYSTEM NAVIGATION LOGIC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val systemNavSwitch = findViewById<MaterialSwitch>(R.id.switchSystemNav)

            // Load saved state
            val isNavHidden = sharedPrefs.getBoolean("HIDE_SYSTEM_NAV", true)
            systemNavSwitch.isChecked = isNavHidden

            if (isNavHidden) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            systemNavSwitch.setOnCheckedChangeListener { _, isChecked ->
                sharedPrefs.edit { putBoolean("HIDE_SYSTEM_NAV", isChecked) }
                if (isChecked) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        }

        // 3. SETTINGS & OTHER UI (Remove the 'val' from the sharedPrefs lines below)
        // Just use 'sharedPrefs' directly now.
        val radioGroupSubtitle = findViewById<RadioGroup>(R.id.spinnerSubtitleMode)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.version).text = version
        val btnClearHistory = findViewById<Button>(R.id.wipeHistoryButton)
        findViewById<TextView>(R.id.version).text = version

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
            val intent = Intent(Intent.ACTION_VIEW, "https://github.com/mixtapeo".toUri())
            startActivity(intent)
        }

        // --- Setup Anki Exclude Logic ---
        val ankiSwitch = findViewById<MaterialSwitch>(R.id.ankiEnabledSwitch)
        val ankiRadioGroup = findViewById<RadioGroup>(R.id.ankiExcludeRadioGroup)
        val TargetAnkiDeck = findViewById<TextView>(R.id.TargetAnkiDeck)
        val ankiDeckSpinner = findViewById<android.widget.Spinner>(R.id.ankiDeckSpinner)

        val ankiIdToIndex = mapOf(
            R.id.radioAnkiExists to 1,
            R.id.radioAnkiStudied to 2
        )
        val ankiIndexToId = ankiIdToIndex.entries.associate { it.value to it.key }

        // 1. LOAD SAVED STATE
        val savedAnkiMode = sharedPrefs.getInt("ANKI_MODE", 0)


        // 1. Initial Load: Populate if ANY mode is enabled and we have permission
        if (savedAnkiMode != 0) {
            val permission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, permission)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                populateAnkiDecks()
            }
        }

        // 2. Listen for User Selection
        ankiDeckSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val selectedDeck = parent?.getItemAtPosition(position).toString()
                    sharedPrefs.edit { putString("ANKI_DECK", selectedDeck) }

                    // Optional: Change the text color to white so it looks good on dark theme
                    (view as? TextView)?.setTextColor(Color.WHITE)
                    globalAnkiCache.clear()
                    if (parsedLyrics.isNotEmpty()) prefetchSongDictionary(parsedLyrics)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

        // Initial UI Setup
        if (savedAnkiMode == 0) {
            ankiSwitch.isChecked = false
            ankiRadioGroup.alpha = 0.5f // Visual cue it's disabled
            // Check the first option by default so it's ready for when they turn it on
            ankiRadioGroup.check(R.id.radioAnkiExists)
            TargetAnkiDeck.alpha = 0.5f
            TargetAnkiDeck.isEnabled = false
            ankiDeckSpinner.alpha = 0.5f
            ankiDeckSpinner.isEnabled = false
        } else {
            ankiSwitch.isChecked = true
            ankiRadioGroup.alpha = 1.0f
            ankiDeckSpinner.alpha = 1.0f
            ankiDeckSpinner.isEnabled = true
            TargetAnkiDeck.alpha = 1.0f
            TargetAnkiDeck.isEnabled = true
            ankiIndexToId[savedAnkiMode]?.let { id -> ankiRadioGroup.check(id) }
        }

        // 2. THE SWITCH LISTENER (Master Control)
        ankiSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                ankiRadioGroup.alpha = 1.0f
                // When turned on, find out which radio is currently clicked
                val currentRadioId = ankiRadioGroup.checkedRadioButtonId
                val newMode = ankiIdToIndex[currentRadioId] ?: 1 // Default to "Exclude if in Deck"
                ankiDeckSpinner.alpha = 1.0f
                ankiDeckSpinner.isEnabled = true
                TargetAnkiDeck.alpha = 1.0f
                TargetAnkiDeck.isEnabled = true

                updateAnkiMode(newMode)
            } else {
                ankiRadioGroup.alpha = 0.5f
                TargetAnkiDeck.alpha = 0.5f
                TargetAnkiDeck.isEnabled = false
                ankiDeckSpinner.alpha = 0.5f
                ankiDeckSpinner.isEnabled = false
                updateAnkiMode(0) // Switch off = Mode 0
            }
        }

        // 2. SET THE LISTENER (Your existing logic)
        ankiRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val position = ankiIdToIndex[checkedId] ?: 0
            Log.i("LyriSync", "Anki mode changed: $position")

            if (position != 0) {
                val permission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, permission)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    ankiPermissionLauncher.launch(permission)
                    return@setOnCheckedChangeListener
                }
            }

            sharedPrefs.edit {
                putInt("ANKI_MODE", position)
                putBoolean("REFRESH_LYRICS_REQUESTED", true)
            }

            lyricAdapter?.notifyDataSetChanged()
        }

        val providerSpinner = findViewById<android.widget.Spinner>(R.id.providerSpinner)
        val savedProvider = sharedPrefs.getString("MEDIA_PROVIDER", "SPOTIFY")

        // Set initial selection
        if (savedProvider == "UNIVERSAL") providerSpinner.setSelection(1)
        else providerSpinner.setSelection(0)

        providerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = if (position == 0) "SPOTIFY" else "UNIVERSAL"
                sharedPrefs.edit().putString("MEDIA_PROVIDER", selected).apply()
                findViewById<com.google.android.material.card.MaterialCardView>(R.id.spotifyOfflineBanner).visibility = View.GONE
                // RE-ROUTE THE ENGINES ON THE FLY!
                applyProviderRouting(selected)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
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

        // setup text size buttons
        val textSizeSlider =
            findViewById<com.google.android.material.slider.Slider>(R.id.textSizeSlider)
        val previewLyric = findViewById<TextView>(R.id.previewLyric)
        val previewFurigana = findViewById<TextView>(R.id.previewFurigana)
        val previewTranslation = findViewById<TextView>(R.id.previewTranslation)

        // Load saved text sizes
        val savedTextSize = sharedPrefs.getFloat("TEXT_SIZE", 4f)

        // --- FIX 1: DO THE PROPER MATH ON STARTUP ---
        val initialBaseSize = 18f + savedTextSize
        val initialFuriganaSize = initialBaseSize * 0.5f
        val initialTranslationSize = initialBaseSize * 0.65f

        // Initialize Sliders
        textSizeSlider.value = savedTextSize

        // Initialize Previews
        previewLyric.setTextSize(TypedValue.COMPLEX_UNIT_SP, initialBaseSize)
        previewFurigana.setTextSize(TypedValue.COMPLEX_UNIT_SP, initialFuriganaSize)
        previewTranslation.setTextSize(TypedValue.COMPLEX_UNIT_SP, initialTranslationSize)

        // Initialize Adapter safely
        lyricAdapter?.apply {
            textSize = initialBaseSize
            furiganaSize = initialFuriganaSize
            translationSize = initialTranslationSize
        }

        textSizeSlider.addOnChangeListener { _, value, _ ->
            sharedPrefs.edit { putFloat("TEXT_SIZE", value) }
            // 1. Calculate the base size
            val baseSize = 18f + value // value 0..32 results in 18sp..50sp

            // 2. Define consistent ratios (adjust these to your liking)
            val furiganaSize = baseSize * 0.5f
            val translationSize = baseSize * 0.65f

            // 3. Update the Preview Cards (to show the user what's happening)
            previewLyric.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSize)
            previewFurigana.setTextSize(TypedValue.COMPLEX_UNIT_SP, furiganaSize)
            previewTranslation.setTextSize(TypedValue.COMPLEX_UNIT_SP, translationSize)

            // 4. Update the actual RecyclerView Adapter
            lyricAdapter?.apply {
                this.textSize = baseSize
                this.furiganaSize = furiganaSize
                this.translationSize = translationSize
                notifyDataSetChanged()
            }
        }

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
            sharedPrefs.edit { putInt("DEF_LIMIT", newLimit) }
            jishoAdapter.definitionLimit = newLimit
            jishoAdapter.notifyDataSetChanged()
        }
        // OBSERVE: When the activeIndex changes, update ONLY the affected rows
        lifecycleScope.launch {
            viewModel.activeIndex.collect { newIndex ->
                val oldIndex = lyricAdapter?.activeIndex ?: -1
                lyricAdapter?.activeIndex = newIndex

                if (oldIndex != -1) lyricAdapter?.notifyItemChanged(oldIndex)

                if (newIndex != -1) {
                    lyricAdapter?.notifyItemChanged(newIndex)

                    // --- CUSTOM CENTERING LOGIC ---
                    val recyclerView = findViewById<RecyclerView>(R.id.lyricRecyclerView)
                    val layoutManager =
                        recyclerView.layoutManager as androidx.recyclerview.widget.LinearLayoutManager

                    // Calculate the middle of the RecyclerView
                    val offset =
                        recyclerView.height / 2 - 100 // Subtracting 100px for the approximate height of one lyric row

                    // This force-scrolls the index to the top, then applies the 'offset'
                    // to push it down to the middle.
                    layoutManager.scrollToPositionWithOffset(newIndex, offset)

                    displayPreparedLine(newIndex)
                }
            }
        }

        // play pause button
        val btnPlayPause = findViewById<android.widget.ImageButton>(R.id.playPause)

        btnPlayPause.setOnClickListener {
            spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
                if (playerState.isPaused) {
                    spotifyAppRemote?.playerApi?.resume()
                } else {
                    spotifyAppRemote?.playerApi?.pause()
                }
            }
        }

        // --- 2. SETUP SYNC TOGGLE & ANIMATION ---
        val fabReSync =
            findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabReSync)
        val syncBtn = findViewById<ToggleButton>(R.id.syncToggleButton)
        findViewById<TextView>(R.id.floatingWarningText) // Grab the new text
        syncBtn.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                viewModel.setSource(LyricSource.SPOTIFY)
                fabReSync.visibility = View.GONE // Hide FAB if manually enabled via toggle

                // Force a re-fetch of the ACTUAL Spotify song
                spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
                    val track = playerState.track
                    if (track != null) {
                        // fetchLyrics(track.name, track.artist.name)
                    }// Optional: Force a scroll back to active line
                    val currentActive = viewModel.activeIndex.value
                    if (currentActive != -1) {
                        findViewById<RecyclerView>(R.id.lyricRecyclerView).smoothScrollToPosition(
                            currentActive
                        )
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

        val lyricRecyclerView = findViewById<RecyclerView>(R.id.lyricRecyclerView)

        // 1. Detect Manual Scroll
        lyricRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)

                // SCROLL_STATE_DRAGGING means the user's finger is moving the list
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && viewModel.source.value == LyricSource.SPOTIFY) {
                    // Pause Sync and Show FAB
                    viewModel.setSource(LyricSource.MANUAL)
                    syncBtn.isChecked = false

                    fabReSync.apply {
                        visibility = View.VISIBLE
                        alpha = 0f
                        animate().alpha(1f).setDuration(200).start()
                    }
                }
            }
        })

        // 2. Re-enable Sync on FAB Click
        fabReSync.setOnClickListener {
            viewModel.setSource(LyricSource.SPOTIFY)
            syncBtn.isChecked = true

            // Smoothly hide the FAB
            fabReSync.animate().alpha(0f).setDuration(200).withEndAction {
                fabReSync.visibility = View.GONE
            }.start()

            // Immediately snap back to the current playing line
            val currentActive = viewModel.activeIndex.value
            if (currentActive != -1) {
                lyricRecyclerView.smoothScrollToPosition(currentActive)
            }
        }

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

                    checkOnline()
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

                searchInput.clearFocus() //CLEAR FOCUS of keyboard

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

    fun isSpotifyInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.spotify.music", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun applyProviderRouting(provider: String) {
        if (provider == "UNIVERSAL") {
            Log.i("LyriSync", "Routing to Universal Engine...")
            // 1. Kill the Spotify Loop
            syncAndMonitorJob?.cancel()

            // 2. Disconnect Local Spotify if it's running
            spotifyAppRemote?.let { SpotifyAppRemote.disconnect(it) }

            // 3. Make sure we have permission
            checkNotificationPermission()

            // 4. Start listening to the Universal Bridge
            startUniversalSyncLoop()

        } else {
            Log.i("LyriSync", "Routing to Spotify Engine...")
            // 1. Kill the Universal Loop
            universalSyncJob?.cancel()

            // 2. Restart the Spotify connection sequence
            reconnectToSpotify(forceAuthView = false)
            // (Remember, reconnectToSpotify starts the syncAndMonitorJob on success/fail)
        }
    }

    private fun startUniversalSyncLoop() {
        universalSyncJob?.cancel()
        universalSyncJob = lifecycleScope.launch(Dispatchers.Main) {
            UniversalMediaBridge.mediaState.collect { state ->

                // 1. Did the song change?
                if (state.triggerNewFetch && state.title.isNotBlank()) {
                    currentTrackUri = null // Reset spotify tracking
                    activeIndex = -1
                    findViewById<TextView>(R.id.songTitleText).text = state.title
                    findViewById<TextView>(R.id.artistNameText).text = state.artist

                    fetchLyrics(state.title, state.artist)
                }

                // 2. Sync the lyrics to the time
                if (state.isPlaying && state.positionMs > 0) {
                    syncLyricsToPosition(state.positionMs)
                }
            }
        }
    }

    private fun populateAnkiDecks() {
        Log.i("LyriSync", "Populating Anki Decks...")
        val ankiDeckSpinner = findViewById<android.widget.Spinner>(R.id.ankiDeckSpinner)
        val sharedPrefs = getSharedPreferences("LyriSyncPrefs", Context.MODE_PRIVATE)

        val decks = AnkiHelper.getDeckList(this)
        val adapter =
            android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, decks)
        ankiDeckSpinner.adapter = adapter

        // Restore the user's previously saved deck selection
        val savedDeck = sharedPrefs.getString("ANKI_DECK", "All Decks")
        val position = decks.indexOf(savedDeck)
        if (position >= 0) {
            ankiDeckSpinner.setSelection(position)
        }
    }

    override fun onStart() {
        super.onStart()
        val sharedPrefs = getSharedPreferences("LyriSyncPrefs", MODE_PRIVATE)
        val isFirstRun = sharedPrefs.getBoolean("IS_FIRST_RUN", true)

        if (!isFirstRun) {
            val activeProvider = sharedPrefs.getString("MEDIA_PROVIDER", "SPOTIFY") ?: "SPOTIFY"
            applyProviderRouting(activeProvider)
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
            sharedPrefs.edit { putBoolean("WIPE_REQUESTED", false) }
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
                Log.e("Lyrisync", "Connection failed: ${throwable.message}")
                checkOnline()

                if (syncAndMonitorJob == null || !syncAndMonitorJob!!.isActive) {
                    startHybridSyncLoop()
                }

                val rootCause = throwable.cause
                if (throwable is RemoteClientException || rootCause is RemoteClientException) {
                    // ONLY show the dialog if this was an automatic background attempt (false).
                    // If they already clicked the button (true) and it failed, don't trap them in a loop!
                    if (!forceAuthView) {
                        runOnUiThread { showSpotifyAuthDialog() }
                    } else {
                        // It failed even after they tried to force it. Let the Web API take over.
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Spotify App connection failed. Using Cloud Sync.", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        findViewById<TextView>(R.id.songTitleText)?.text = "Media not playing..."
                        findViewById<TextView>(R.id.artistNameText)?.text = ""
                    }
                }
            }
        })
    }
    private fun checkOnline() {
        if (!isNetworkConnected()) {
            Log.d("Lyrisync", "No internet connection")
            // update UI to show "No Connection" state
            runOnUiThread {
                findViewById<TextView>(R.id.NoLyricsText).text = "No Connection"
                findViewById<TextView>(R.id.NoLyricsText).visibility = View.VISIBLE
                findViewById<TextView>(R.id.artistNameText).text = ""
                findViewById<TextView>(R.id.songTitleText).text = ""
                lyricAdapter?.updateData(emptyList(), emptyList(), emptyList(), emptyList())
            }
        }
    }
    private var syncAndMonitorJob: Job? = null
    private var currentFetchJob: Job? = null // currently active "fetch" job. Explicitly cancel it the moment a new song is detected.
    @SuppressLint("UseKtx")
    private fun startHybridSyncLoop() {
        syncAndMonitorJob?.cancel()
        syncAndMonitorJob = lifecycleScope.launch(Dispatchers.Main) {

            var timeSinceLastWebPoll = 4000
            var timeSinceLastStaleCheck = 0 // <--- NEW: Grace period counter
            val banner = findViewById<com.google.android.material.card.MaterialCardView>(R.id.spotifyOfflineBanner)
            var bannerText = findViewById<TextView>(R.id.offlineBannerText)

            while (isActive) {
                val isSdkConnected = spotifyAppRemote?.isConnected == true
                Log.d("Lyrisync", "SDK Connected: $isSdkConnected")
                if (activeEngine == SyncEngine.SDK) {
                    // ---------------------------------------------------------
                    // ENGINE 1: LOCAL SDK MODE (Fast, 100ms UI updates)
                    // ---------------------------------------------------------
                    if (banner.visibility == View.VISIBLE) {
                        banner.animate()
                            .alpha(0f)
                            .translationY(50f)
                            .setDuration(300)
                            .withEndAction {
                                banner.visibility = View.GONE
                            }
                            .start()
                    }

                    if (!isSdkConnected) {
                        Log.w("Lyrisync", "SDK Disconnected! Switching to Web API Fallback.")
                        activeEngine = SyncEngine.WEB_API
                        timeSinceLastWebPoll = 4000
                    } else {
                        timeSinceLastStaleCheck += 100 // Count up every tick

                        spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
                            val isPlaying = !playerState.isPaused
                            val currentSdkPos = playerState.playbackPosition

                            // ONLY check for staleness every 2 seconds
                            if (timeSinceLastStaleCheck >= 2000) {
                                timeSinceLastStaleCheck = 0 // Reset counter

                                val isStale =
                                    isPlaying && currentSdkPos == lastSdkPosition && currentSdkPos > 0

                                if (isStale) {
                                    Log.w("Lyrisync", "SDK Frozen! Switching to Web API Fallback.")
                                    activeEngine = SyncEngine.WEB_API
                                    timeSinceLastWebPoll = 4000
                                }
                                // Set baseline for the NEXT 2-second check
                                lastSdkPosition = currentSdkPos
                            }
                            Log.d("Lyrisync", "SDK Pos: $currentSdkPos")
                            // Always sync lyrics immediately to keep the UI buttery smooth
                            syncLyricsToPosition(currentSdkPos)
                        }
                    }
                } else {
                    // ---------------------------------------------------------
                    // ENGINE 2: WEB API FALLBACK MODE (Polled, Dead-Reckoned)
                    // ---------------------------------------------------------
//                    if (banner.visibility == View.GONE) {
//                        banner.text = "Cloud Sync Active (Local App Asleep/Missing)"
//                        banner.backgroundTintList = ColorStateList.valueOf("#E65100".toColorInt())
//                        banner.visibility = View.VISIBLE
//                    }

                    timeSinceLastWebPoll += 100

                    // 1. Check the Web API every 4 seconds
                    if (timeSinceLastWebPoll >= 4000) {
                        timeSinceLastWebPoll = 0

                        try {
                            val response = spotifyApiService.getPlaybackState()

                            if (response.isSuccessful && response.body() != null) {
                                if (banner.visibility != View.VISIBLE) {
                                    banner.alpha = 0f
                                    banner.translationY = 50f // Start slightly lower
                                    banner.visibility = View.VISIBLE

                                    banner.animate()
                                        .alpha(1f)
                                        .translationY(0f) // Slide up to its normal position
                                        .setDuration(300)
                                        .start()
                                }
                                val state = response.body()!!
                                isWebPlaying = state.is_playing
                                webApiProgressMs = state.progress_ms

                                state.item?.let { track ->
                                    if (track.uri != currentTrackUri) {
                                        currentTrackUri = track.uri
                                        activeIndex = -1
                                        findViewById<TextView>(R.id.songTitleText).text = track.name
                                        findViewById<TextView>(R.id.artistNameText).text =
                                            track.artists.firstOrNull()?.name ?: ""
                                        fetchLyrics(
                                            track.name,
                                            track.artists.firstOrNull()?.name ?: ""
                                        )
                                    }
                                }
                            } else if (response.code() == 401) {
                                if (!isRefreshingToken) {
                                    // Don't auto-refresh. Tell the user what happened!
                                    runOnUiThread {
                                        // dont show banner if using universal
                                        if (!getSharedPreferences("MEDIA_PROVIDER", MODE_PRIVATE).equals("UNIVERSAL")){
                                            bannerText.text = "Connection lost. Tap here to re-link Spotify."
                                            banner.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E65100")) // Orange warning
                                            banner.visibility = View.VISIBLE

                                            // Wait for the user to explicitly tap the banner
                                            banner.setOnClickListener {
                                                isRefreshingToken = true
                                                bannerText.text = "Connecting..."
                                                banner.setOnClickListener(null) // Prevent double clicks
                                                checkAndRefreshSpotifyToken()
                                            }
                                        }
                                    }
                                }
                                // KILL THE LOOP completely. We will restart it when the user finishes logging in.
                                return@launch
                            } else if (response.code() == 429) {
                                val retryAfterSeconds =
                                    response.headers()["Retry-After"]?.toIntOrNull() ?: 10
                                Log.e("Lyrisync", "429 RATE LIMIT: Wait ${retryAfterSeconds}s.")
                                timeSinceLastWebPoll = -(retryAfterSeconds * 1000) // Apply Penalty

                                bannerText.text = "API Limit Hit. Pausing for ${retryAfterSeconds}s..."
                                banner.backgroundTintList =
                                    ColorStateList.valueOf(Color.parseColor("#D32F2F"))
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // ALWAYS rethrow CancellationException in coroutines, or they won't cancel properly!
                            throw e
                        } catch (e: java.net.SocketException) {
                            // This happens when the coroutine is cancelled mid-flight or network drops.
                            // It is totally normal. Just log it and let the loop try again next time.
                            Log.w(
                                "Lyrisync",
                                "Socket closed. Web API request cancelled or network dropped."
                            )
                        } catch (e: java.net.UnknownHostException){
                            // no internet probably. No way spotify webapi is down.
                            Log.d("Lyrisync", "man come on are you here")
                            checkOnline()
                        }
                        catch (e: java.io.IOException) {
                            // Catches general network timeouts and offline issues
                            Log.w("Lyrisync", "Network error during Web API poll: ${e.message}")
                        } catch (e: Exception) {
                            Log.e("Lyrisync", "Web API Poll Failed: ${e.message}")
                        }
                        // 2. RECOVERY CHECK: Can we switch back to the SDK?
                        if (isSdkConnected) {
                            spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
                                val sdkPos = playerState.playbackPosition
                                val isPlaying = !playerState.isPaused

                                // Does the SDK show active movement?
                                if (isPlaying && sdkPos != lastSdkPosition && sdkPos > 0) {
                                    Log.i(
                                        "Lyrisync",
                                        "SDK woke up and is moving! Switching back to Local Mode."
                                    )
                                    activeEngine = SyncEngine.SDK
                                    timeSinceLastStaleCheck =
                                        0 // Reset the Stale Check so it doesn't instantly fail

                                    if (banner.visibility == View.VISIBLE) banner.visibility =
                                        View.GONE
                                }
                                lastSdkPosition = sdkPos
                            }
                        }
                    }

                    // 3. DEAD RECKONING
                    if (isWebPlaying && activeEngine == SyncEngine.WEB_API) {
                        webApiProgressMs += 100
                        syncLyricsToPosition(webApiProgressMs)
                    }
                }

                delay(100)
            }
        }
    }

    private fun connected() {
        spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
            val track = playerState.track

            // play pause button logic
            val btnPlayPause = findViewById<android.widget.ImageButton>(R.id.playPause)
            if (playerState.isPaused) {
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            }

            if (track != null) {
                if (track.uri != currentTrackUri) {
                    currentTrackUri = track.uri
                    activeIndex = -1
                    findViewById<TextView>(R.id.songTitleText).text = track.name
                    findViewById<TextView>(R.id.artistNameText).text = track.artist.name
                    fetchLyrics(track.name, track.artist.name)
                }

                if (syncJob == null || !syncJob!!.isActive) {
                    startHybridSyncLoop()
                }
            }
        }
    }

    // <------- support funcs ------->
    // Setup Coil ImageLoader with GIF support
    private fun showFirstStartDialog(prefs: android.content.SharedPreferences) {
        val overlay = findViewById<View>(R.id.onboardingOverlay)
        val btnOk = findViewById<Button>(R.id.btnOnboardingOk)
        val btnUniversal = findViewById<Button>(R.id.btnUniversalPlayer) // Grab the new button
        val btnNever = findViewById<Button>(R.id.btnOnboardingNever)
        val gifVideo = findViewById<ImageView>(R.id.gifVideo)
        val gifBroadcastView = findViewById<ImageView>(R.id.gifBroadcast)
        if (!isSpotifyInstalled(this)) {
            findViewById<TextView>(R.id.onboardingText)?.text =
                "Spotify not detected. You can use the 'Universal' mode for other music apps, or search manually. For spotify, follow these steps:"

            btnOk.text = "Continue with Spotify (WebAPI)"
            btnUniversal.visibility = View.VISIBLE // Show the universal button!
            btnNever.visibility = View.GONE        // Hide the "never" button to keep UI clean
        }
        overlay.visibility = View.VISIBLE

        // 1. Create a specialized Loader for animations
        val animationLoader = coil.ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
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
            prefs.edit { putBoolean("IS_FIRST_RUN", false) }
            checkAndRefreshSpotifyToken() // force auth diag to avoid user having to figure out to auth
            reconnectToSpotify()
        }

        btnNever.setOnClickListener {
            prefs.edit { putBoolean("IS_FIRST_RUN", false) }
            overlay.visibility = View.GONE
            reconnectToSpotify()
        }

        btnUniversal.setOnClickListener {
            overlay.visibility = View.GONE

            // Save the preference so the app remembers this choice next time
            prefs.edit {
                putBoolean("IS_FIRST_RUN", false)
                putString("MEDIA_PROVIDER", "UNIVERSAL")
            }

            // Update the Settings Spinner UI so it matches their choice
            findViewById<android.widget.Spinner>(R.id.providerSpinner).setSelection(1)

            // Start the Universal Engine directly instead of Spotify!
            applyProviderRouting("UNIVERSAL")
        }

        btnNever.setOnClickListener {
            prefs.edit { putBoolean("IS_FIRST_RUN", false) }
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
        checkOnline()
        // 1. CANCEL ANY ONGOING FETCH IMMEDIATELY
        currentFetchJob?.cancel()

        runOnUiThread {
            findViewById<ProgressBar>(R.id.loadingCircle).visibility = View.VISIBLE
            // CLEAR EVERYTHING CURRENT
            parsedLyrics = emptyList()
            translatedLyrics = emptyList()
            preparedLineSets.clear()
            songDictionary.clear()

            // Tell the UI to go blank
            lyricAdapter?.updateData(emptyList(), emptyList(), emptyList(), emptyList())

            // Reset the highlighted line
            activeIndex = -1
        }

        // 2. ASSIGN THIS LAUNCH TO YOUR VARIABLE
        currentFetchJob = lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.IO) {
                try {
                    Log.d("Lyrisync", "Fetching lyrics for $title by $artist")
                    val response = lrcService.searchLyrics(title, artist)

                    val bestMatch = response.filter { it.syncedLyrics != null }
                        .firstOrNull { it.syncedLyrics?.contains(jpCharacterRegex) == true }
                        ?: response.firstOrNull { it.syncedLyrics != null }

                    if (bestMatch != null) {
                        parsedLyrics = parseLrc(bestMatch.syncedLyrics!!)
                        Log.d("LyriSync-debug", "Lyrics are: $parsedLyrics")

                        // 2. Filter out the blank gaps and save their original indices
                        val nonBlankIndices = mutableListOf<Int>()
                        val textToTranslate = mutableListOf<String>()

                        parsedLyrics.forEachIndexed { index, line ->
                            if (line.text.isNotBlank()) {
                                nonBlankIndices.add(index)
                                textToTranslate.add(line.text)
                            }
                        }

                        val fullJapaneseText = textToTranslate.joinToString("\n")
                        val hasKanji = fullJapaneseText.contains(jpCharacterRegex)
                        Log.d("LyriSync-debug", "Has kanji: $hasKanji")

                        if (hasKanji && textToTranslate.isNotEmpty()) {
                            // 2. Fetch the translation
                            val translationResponse =
                                translationService.getTranslation(q = fullJapaneseText)
                            Log.d("Lyrisync", "Translation service called + $translationResponse")
                            val bulkResult = extractTextFromGoogle(translationResponse)
                            val tempTranslations = bulkResult.split("\n")

                            // 3. Map the translations back to their exact original positions
                            val alignedTranslations = MutableList(parsedLyrics.size) { "" }
                            val limit = minOf(nonBlankIndices.size, tempTranslations.size)

                            for (i in 0 until limit) {
                                alignedTranslations[nonBlankIndices[i]] = tempTranslations[i].trim()
                            }
                            translatedLyrics = alignedTranslations
                        } else {
                            Log.d("Lyrisync", "No kanji found, skipping translation")
                            translatedLyrics = parsedLyrics.map { "" }
                        }
                        prefetchSongDictionary(parsedLyrics)

                        withContext(Dispatchers.Main) {
                            lyricAdapter?.updateData(
                                parsedLyrics,
                                translatedLyrics,
                                emptyList(),
                                emptyList()
                            )
                            findViewById<TextView>(R.id.NoLyricsText).visibility = View.GONE
                        }
                    } else {
                        // Hide if no lyrics found
                        withContext(Dispatchers.Main) {
                            findViewById<ProgressBar>(R.id.loadingCircle).visibility = View.GONE
                            lyricAdapter?.updateData(
                                listOf(),
                                listOf(),
                                emptyList(),
                                emptyList()
                            )
                            findViewById<TextView>(R.id.NoLyricsText).visibility = View.VISIBLE
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: java.net.SocketException) {
                    Log.w("Lyrisync", "Lyrics fetch cancelled (Socket closed).")
                } catch (e: java.io.IOException) {
                    Log.e("Lyrisync", "Network error fetching lyrics.", e)
                    withContext(Dispatchers.Main) {
                        findViewById<ProgressBar>(R.id.loadingCircle).visibility = View.GONE
                        // Optionally show a "Network Error" Toast here
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

    @SuppressLint("NotifyDataSetChanged", "DirectSystemCurrentTimeMillisUsage")
    /*
    * Uses lexicological analyzer (kuromoji) to segement words -> underlines and coloring, furigana and translation fetch, and issue UI update
    * */
    private fun prefetchSongDictionary(lyrics: List<LyricLine>) {
        val startTime = System.currentTimeMillis()
        Log.d("Lyrisync", "Prefetch started for ${lyrics.size} lines")

        runOnUiThread {
            jishoHistory.clear()
            jishoAdapter.notifyDataSetChanged()
        }

        val ankiMode = getSharedPreferences("LyriSyncPrefs", MODE_PRIVATE).getInt("ANKI_MODE", 0)
        val ankiDeck =
            getSharedPreferences("LyriSyncPrefs", MODE_PRIVATE).getString("ANKI_DECK", "All Decks")
                ?: "All Decks" // <-- Add this!
        lifecycleScope.launch(Dispatchers.IO) {
            val dbLoadStart = System.currentTimeMillis()
            val db = AppDatabase.getDatabase(this@MainActivity)
            val dao = db.jishoDao()

            // 1. Initialize Kuromoji Tokenizer
            // Done on the IO dispatcher because loading the dictionary takes a moment
            val tokenizer = Tokenizer()

            Log.d(
                "Lyrisync",
                "DB/DAO & Tokenizer Init took: ${System.currentTimeMillis() - dbLoadStart}ms"
            )

            val furiganaLyrics = mutableListOf<String>()
            val highlightsList = mutableListOf<List<HighlightSpan>>()

            preparedLineSets.clear()
            songDictionary.clear()
            queryCache.clear()

            val jpCharacterRegex = Regex("[\\u3040-\\u30ff\\u4e00-\\u9faf]")
            // --- 1. THE PARALLEL ANKI BLAST ---
            if (ankiMode != 0) {
                val uniqueWords = mutableSetOf<String>()

                // Scan the song for unique Japanese words
                for (line in lyrics) {
                    if (line.text.isNotBlank() && line.text != "...") {
                        tokenizer.tokenize(line.text).forEach { token ->
                            val baseForm = token.baseForm ?: token.surface
                            if (token.partOfSpeechLevel1 != "記号" && baseForm.contains(
                                    jpCharacterRegex
                                )
                            ) {
                                uniqueWords.add(baseForm)
                            }
                        }
                    }
                }

                // Find words we haven't asked Anki about yet
                val wordsToFetch = uniqueWords.filter { !globalAnkiCache.containsKey(it) }

                if (wordsToFetch.isNotEmpty()) {
                    Log.d(
                        "Lyrisync",
                        "Parallel fetching ${wordsToFetch.size} new words from Anki..."
                    )
                    val ankiFetchStart = System.currentTimeMillis()

                    // Blast Anki with concurrent requests using coroutines!
                    val deferreds = wordsToFetch.map { word ->
                        async(Dispatchers.IO) { // <-- Removed prefix
                            val excluded = AnkiHelper.shouldExclude(
                                this@MainActivity,
                                word,
                                ankiMode,
                                ankiDeck
                            )
                            globalAnkiCache[word] = excluded
                        }
                    }
                    deferreds.awaitAll()

                    Log.d(
                        "Lyrisync",
                        "Parallel Anki blast finished in ${System.currentTimeMillis() - ankiFetchStart}ms!"
                    )
                }
            }
            val loopStart = System.currentTimeMillis()

            for ((index, line) in lyrics.withIndex()) {
                val lineStart = System.currentTimeMillis()
                val lineText = line.text

                // exceptions to skip processing
                if (lineText == "..." || lineText.isBlank()) {
                    highlightsList.add(emptyList())
                    furiganaLyrics.add("")
                    continue
                }

                val lineHighlights = mutableListOf<HighlightSpan>()
                val lineReadings = mutableListOf<String>()
                val lineJishoWords = mutableListOf<JishoWord>()
                var wordIndex = 0

                // 2. Tokenize the entire line at once
                val tokens = tokenizer.tokenize(lineText)

                tokens.forEach { token ->
                    val surface =
                        token.surface // The exact word as it appears in the song (e.g., "走っ")
                    val baseForm = token.baseForm ?: surface // The dictionary form (e.g., "走る")
                    val pos1 = token.partOfSpeechLevel1 // e.g., Noun (名詞), Particle (助詞), etc.

                    if (surface.isBlank()) {
                        return@forEach
                    }
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
                        // 1. Check Anki Status First
                        // --- 2. INSTANT CACHE LOOKUP ---
                        val isExcludedByAnki = if (ankiMode != 0) {
                            globalAnkiCache[baseForm] ?: false
                        } else {
                            false
                        }

                        // 2. ALWAYS apply the highlight coordinates
                        val startPos = token.position
                        val endPos = startPos + surface.length
                        lineHighlights.add(HighlightSpan(startPos, endPos, wordIndex))

                        Log.d(
                            "Lyrisync-Color",
                            "Generated span for [${surface}]: Start $startPos, End $endPos"
                        )

                        // 3. ALWAYS grab the proper Furigana reading from the database
                        val reading = entry.reading ?: token.reading ?: surface
                        lineReadings.add(reading)

                        // 4. ONLY build the definition box if Anki didn't exclude it
                        if (!isExcludedByAnki) {
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
                            }
                        }

                        // 5. ALWAYS increment the color index so the next word gets a new color
                        wordIndex++

                    } else {
                        // Word is valid but not in your DB, just append its reading for Furigana
                        lineReadings.add(token.reading ?: surface)
                    }
                }

                highlightsList.add(lineHighlights)
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
                Log.i(
                    "Lyrisync",
                    "TOTAL PREFETCH TIME: ${System.currentTimeMillis() - startTime}ms"
                )

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
                val nonBlankIndices = mutableListOf<Int>()
                val textToTranslate = mutableListOf<String>()

                parsedLyrics.forEachIndexed { index, line ->
                    if (line.text.isNotBlank()) {
                        nonBlankIndices.add(index)
                        textToTranslate.add(line.text)
                    }
                }

                val fullJapaneseText = textToTranslate.joinToString("\n")

                if (fullJapaneseText.isNotBlank()) {
                    val translationResponse =
                        translationService.getTranslation(q = fullJapaneseText)
                    val bulkResult = extractTextFromGoogle(translationResponse)
                    val tempTranslations = bulkResult.split("\n")

                    // Map translations to skip the dummy gaps
                    val alignedTranslations = MutableList(parsedLyrics.size) { "" }
                    val limit = minOf(nonBlankIndices.size, tempTranslations.size)

                    for (i in 0 until limit) {
                        alignedTranslations[nonBlankIndices[i]] = tempTranslations[i].trim()
                    }
                    translatedLyrics = alignedTranslations
                } else {
                    translatedLyrics = parsedLyrics.map { "" }
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
        val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})](.*)")

        for (line in lines) {
            val match = regex.find(line)
            if (match != null) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()

                val msString = match.groupValues[3]
                var ms = msString.toLong()
                if (msString.length == 2) ms *= 10

                val text = match.groupValues[4].trim()
                val totalMs = (min * 60 * 1000) + (sec * 1000) + ms

                if (text.isNotEmpty()) {
                    lyricList.add(LyricLine(totalMs, text))
                }
            }
        }

        // Sort first so we can calculate gaps reliably
        val sortedLyrics = lyricList.sortedBy { it.timeMs }.toMutableList()
        val finalLyrics = mutableListOf<LyricLine>()

        // 1. Check for an intro gap BEFORE the loop
        if (sortedLyrics.isNotEmpty()) {
            val firstLyricTime = sortedLyrics[0].timeMs
            if (firstLyricTime > 2500) { // If intro is longer than 2.5s
                finalLyrics.add(LyricLine(1, " "))
            }
        }

        // 2. Then run your loop as usual
        for (i in 0 until sortedLyrics.size) {
            val currentLyric = sortedLyrics[i]
            finalLyrics.add(currentLyric) // 1. Add the actual lyric

            if (i < sortedLyrics.size - 1) {
                val nextStart = sortedLyrics[i + 1].timeMs
                val currentStart = currentLyric.timeMs
                val gap = nextStart - currentStart

                // If gap is > 5 seconds
                if (gap > 10000) {
                    finalLyrics.add(LyricLine(nextStart, " "))
                }
                Log.d("Lyrisync", "Gap between lines: $gap at $currentLyric")
            }
        }
        Log.d("Lyrisync", "Parsed Lyrics: $finalLyrics")
        return finalLyrics
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
                setTextColor("#E0E0E0".toColorInt())
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

                val typedValue = TypedValue()
                context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, typedValue, true
                )
                foreground =
                    androidx.core.content.ContextCompat.getDrawable(context, typedValue.resourceId)

                setOnClickListener {
                    // Send the newly limited text to Anki so you don't get bloated flashcards!
                    val flashcardText =
                        "${jishoWord.phrase} [${jishoWord.reading}]\n\n$limitedDefinitions"

                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, flashcardText)
                        type = "text/plain"
                    }
                    val shareIntent =
                        Intent.createChooser(sendIntent, "Send to Anki")
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

    @SuppressLint("NotifyDataSetChanged")
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
        holder.artist.setTextColor("#A0A0A0".toColorInt())

        // 2. Trigger the listener when the user taps this row
        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount() = results.size
}

object AnkiHelper {
    private val NOTES_URI = Uri.parse("content://com.ichi2.anki.flashcards/notes")
    private val DECKS_URI = Uri.parse("content://com.ichi2.anki.flashcards/decks")

    fun getDeckList(context: Context): List<String> {
        val decks = mutableListOf("All Decks")

        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                "com.ichi2.anki.permission.READ_WRITE_DATABASE"
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return decks
        }

        try {
            // --- FIX 1: Change "name" to "deck_name" in the query array and sort order ---
            val cursor = context.contentResolver.query(
                DECKS_URI,
                arrayOf("deck_name"),
                null,
                null,
                "deck_name COLLATE NOCASE ASC"
            )

            cursor?.use {
                val nameIndex = it.getColumnIndex("deck_name")

                if (nameIndex != -1) {
                    while (it.moveToNext()) {
                        val deckName = it.getString(nameIndex)
                        Log.d("Lyrisync", "Deck: $deckName")
                        if (!deckName.isNullOrBlank()) {
                            decks.add(deckName)
                        }
                    }
                } else {
                    Log.e("Lyrisync", "Column 'deck_name' missing! Columns available: ${it.columnNames.joinToString()}")
                }
            }
        } catch (e: Exception) {
            Log.e("Lyrisync", "Failed to fetch Anki decks", e)
        }
        return decks
    }

    @SuppressLint("Range")
    fun shouldExclude(context: Context, word: String, mode: Int, deckName: String): Boolean {
        // Mode 0: Show all words (don't exclude anything)
        if (mode == 0) return false

        // Safety check for permissions
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                "com.ichi2.anki.permission.READ_WRITE_DATABASE"
            )
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("Lyrisync", "Anki permission missing. Cannot filter words.")
            return false
        }

        try {
            val cleanWord = word.trim()

            // --- THE MAGIC: Anki Native Search Syntax ---
            // Mode 1: "\"word\"" -> Excludes if it exists at all.
            // Mode 2: "\"word\" -is:new" -> Excludes ONLY if it exists AND is not a brand new card.
            val ankiSearchQuery = buildString {
                append("\"$cleanWord\"") // 1. Always look for the exact word
                if (mode == 2) append(" -is:new") // 2. Add 'studied' filter if Mode 2
                if (deckName != "All Decks") append(" deck:\"$deckName\"") // 3. Add deck filter if selected
            }

            // We only make ONE query to the notes table
            val cursor = context.contentResolver.query(
                NOTES_URI,
                null,
                ankiSearchQuery,
                null,
                null
            )
            Log.d("Lyrisync", "Query: $ankiSearchQuery")
            if (cursor != null) {
                cursor.use {
                    if (it.moveToFirst()) {
                        Log.d("Lyrisync", "Excluding [$cleanWord]! Match found for query: $ankiSearchQuery")
                        return true
                    } else {
                        Log.d("Lyrisync", "Keeping [$cleanWord]. No match found for query: $ankiSearchQuery")
                        return false
                    }
                }
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            Log.w("Lyrisync", "Word [$word] matched too many cards in Anki. Skipping.", e)
        } catch (e: Exception) {
            Log.e("Lyrisync", "General Anki query error.", e)
        }

        return false
    }
}