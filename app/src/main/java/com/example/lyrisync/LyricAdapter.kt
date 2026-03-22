package com.example.lyrisync

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LyricAdapter(
    private var lyrics: List<LyricLine> = emptyList(),
    private var translations: List<String> = emptyList()
) : RecyclerView.Adapter<LyricAdapter.LyricViewHolder>() {

    var activeIndex: Int = -1

    fun updateData(newLyrics: List<LyricLine>, newTranslations: List<String>) {
        this.lyrics = newLyrics
        this.translations = newTranslations
        notifyDataSetChanged()
    }

    class LyricViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val jp: TextView = view.findViewById(R.id.itemJp)
        val en: TextView = view.findViewById(R.id.itemEn)

        // 2. Safely look for a Furigana view.
        // If you don't have this in your XML yet, it just returns null instead of crashing!
        val furigana: TextView? = view.findViewById(R.id.itemFurigana)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric, parent, false)
        return LyricViewHolder(view)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        holder.jp.text = lyrics[position].text
        holder.en.text = translations.getOrNull(position) ?: ""

        if (position == activeIndex) {
            holder.itemView.alpha = 1.0f // Fully visible
            holder.jp.setTextColor(Color.parseColor("#1DB954")) // Spotify Green
        } else {
            holder.itemView.alpha = 0.3f // Faded out
            holder.jp.setTextColor(Color.WHITE)
        }
        val spannable = android.text.SpannableString(lyrics[position].text)

        // Find all Kanji in this specific line
        val text = lyrics[position].text
        for (i in text.indices) {
            if (Character.UnicodeBlock.of(text[i]) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                val kanjiRegex = Regex("[\\u4e00-\\u9faf]+")
                val matches = kanjiRegex.findAll(text)

                for (match in matches) {
                    spannable.setSpan(
                        android.text.style.UnderlineSpan(),
                        match.range.first, match.range.last + 1,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#FFD54F")),
                        match.range.first, match.range.last + 1,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }

        // --- SUBTITLE TOGGLE LOGIC ---
        // 1. Get the current setting from SharedPreferences
        val sharedPrefs = holder.itemView.context.getSharedPreferences("LyriSyncPrefs", Context.MODE_PRIVATE)
        val subtitleMode = sharedPrefs.getInt("SUBTITLE_MODE", 2) // Default to 2

        // 2. Toggle visibility based on the Mode
        // Notice we are using your 'en' variable and the safe 'furigana?' variable
        when (subtitleMode) {
            0 -> { // None
                holder.furigana?.visibility = View.GONE
                holder.en.visibility = View.GONE
            }
            1 -> { // Furigana Only
                holder.furigana?.visibility = View.VISIBLE
                holder.en.visibility = View.GONE
            }
            2 -> { // Furigana + Translation
                holder.furigana?.visibility = View.VISIBLE
                holder.en.visibility = View.VISIBLE
            }
            3 -> { // Only Translation
                holder.furigana?.visibility = View.GONE
                holder.en.visibility = View.VISIBLE
            }
        }

        holder.jp.text = spannable
    }

    override fun getItemCount() = lyrics.size
}