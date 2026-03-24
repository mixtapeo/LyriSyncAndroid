package com.mixtapeo.lyrisync

import android.content.Context
import android.os.Bundle
import android.util.Log // Added Log import
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

class SettingsActivity : AppCompatActivity() {

    // Define a Tag for filtering in Logcat
    private val TAG = "LyriSync_Settings"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate: SettingsActivity started")

        val radioGroupSubtitle = findViewById<RadioGroup>(R.id.spinnerSubtitleMode)
        val btnClearHistory = findViewById<Button>(R.id.wipeHistoryButton)
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottomNavigation)

        val sharedPrefs = getSharedPreferences("LyriSyncPrefs", Context.MODE_PRIVATE)

        // --- 1. Setup RadioGroup (Subtitle Mode) ---
        val idToIndex = mapOf(
            R.id.radioNone to 0,
            R.id.radioFurigana to 1,
            R.id.radioBoth to 2,
            R.id.radioEnglish to 3
        )

        val indexToId = idToIndex.entries.associate { it.value to it.key }

        val savedSubtitleMode = sharedPrefs.getInt("SUBTITLE_MODE", 2)
        Log.d(TAG, "Loading saved Subtitle Mode index: $savedSubtitleMode")
        indexToId[savedSubtitleMode]?.let { radioGroupSubtitle.check(it) }

        radioGroupSubtitle.setOnCheckedChangeListener { _, checkedId ->
            val position = idToIndex[checkedId] ?: 2
            Log.i(TAG, "Subtitle mode changed. New Index: $position (ID: $checkedId)")

            sharedPrefs.edit()
                .putInt("SUBTITLE_MODE", position)
                .putBoolean("REFRESH_LYRICS_REQUESTED", true)
                .apply()
        }

        // --- 2. Setup History Button ---
        btnClearHistory.setOnClickListener {
            Log.w(TAG, "Wipe History requested by user.")
            sharedPrefs.edit().putBoolean("WIPE_REQUESTED", true).apply()
            Toast.makeText(this, "History cleared!", Toast.LENGTH_SHORT).show()
        }

        // --- 3. Bottom Navigation ---
        bottomNavigationView.selectedItemId = R.id.nav_settings

        bottomNavigationView.setOnItemSelectedListener { item ->
            Log.d(TAG, "Navigation item clicked: ${item.title}")
            when (item.itemId) {
                R.id.nav_home -> {
                    Log.d(TAG, "Navigating back to Home (finishing activity)")
                    finish()
                    true
                }
                R.id.nav_search -> {
                    Toast.makeText(this, "Search coming soon!", Toast.LENGTH_SHORT).show()
                    false
                }
                R.id.nav_settings -> true
                else -> false
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        Log.d(TAG, "finish() called - applying exit transitions")
        super.finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: SettingsActivity destroyed")
        super.onDestroy()
    }
}