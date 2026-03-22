package com.example.lyrisync

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val switchAutoScroll = findViewById<SwitchCompat>(R.id.switchAutoScroll)
        val btnClearHistory = findViewById<Button>(R.id.btnClearHistory)
        val spinnerSubtitleMode = findViewById<Spinner>(R.id.spinnerSubtitleMode)
        val sharedPrefs = getSharedPreferences("LyriSyncPrefs", Context.MODE_PRIVATE)

        // --- 1. Setup the Dropdown (Spinner) ---
        val subtitleOptions = arrayOf("None", "Furigana", "Furigana + Translation", "Only Translation")

        // Use a built-in Android layout for the spinner items to keep it simple and dark-mode friendly
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, subtitleOptions)
        spinnerSubtitleMode.adapter = adapter

        // Load saved state (Default to 2: "Furigana + Translation")
        val savedSubtitleMode = sharedPrefs.getInt("SUBTITLE_MODE", 2)
        spinnerSubtitleMode.setSelection(savedSubtitleMode)

        // Save state on change
        spinnerSubtitleMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sharedPrefs.edit().putInt("SUBTITLE_MODE", position).apply()
                // Tell MainActivity it needs to redraw the lyrics when we go back
                sharedPrefs.edit().putBoolean("REFRESH_LYRICS_REQUESTED", true).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // --- 2. Setup the existing Switch and Button ---
        switchAutoScroll.isChecked = sharedPrefs.getBoolean("AUTO_SYNC", true)

        switchAutoScroll.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("AUTO_SYNC", isChecked).apply()
        }

        btnClearHistory.setOnClickListener {
            sharedPrefs.edit().putBoolean("WIPE_REQUESTED", true).apply()
            Toast.makeText(this, "History cleared!", Toast.LENGTH_SHORT).show()
        }
    }
}