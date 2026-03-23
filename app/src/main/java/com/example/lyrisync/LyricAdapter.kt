package com.example.lyrisync

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.graphics.toColorInt

class LyricAdapter(
    private var lyrics: List<LyricLine> = emptyList(),
    private var translations: List<String> = emptyList(),
    private var furiganaList: List<String> = emptyList(),
    private var highlightedWords: List<List<String>> = emptyList()
) : RecyclerView.Adapter<LyricAdapter.LyricViewHolder>() {

    var activeIndex: Int = -1

    fun updateData(
        newLyrics: List<LyricLine>,
        newTranslations: List<String>,
        newFurigana: List<String>,
        newHighlights: List<List<String>>
    ) {
        Log.d("Lyrisync-Debug", "LyricAdapter updateData: Lyrics(${newLyrics.size}), Furigana(${newFurigana.size}), Highlights(${newHighlights.size})")
        this.lyrics = newLyrics
        this.translations = newTranslations
        this.furiganaList = newFurigana
        this.highlightedWords = newHighlights
        notifyDataSetChanged()
    }

    class LyricViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val jp: TextView = view.findViewById(R.id.itemJp)
        val en: TextView = view.findViewById(R.id.itemEn)
        val furigana: TextView? = view.findViewById(R.id.itemFurigana)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric, parent, false)
        return LyricViewHolder(view)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        holder.jp.text = lyrics[position].text
        holder.en.text = translations.getOrNull(position) ?: ""

        val furiganaText = furiganaList.getOrNull(position) ?: ""
        holder.furigana?.text = furiganaText

        if (position == activeIndex) {
            holder.itemView.alpha = 1.0f
            holder.jp.setTextColor(android.graphics.Color.parseColor("#1DB954"))
        } else {
            holder.itemView.alpha = 0.3f
            holder.jp.setTextColor(android.graphics.Color.WHITE)
        }
        val spannable = android.text.SpannableString(lyrics[position].text)
        val text = lyrics[position].text

        val wordsToHighlight = highlightedWords.getOrNull(position) ?: emptyList()
        var searchStartIndex = 0

        // 1. Define your Color Palette
        val highlightColors = intArrayOf(
            "#FFD54F".toColorInt(), // Yellow
            "#81C784".toColorInt(), // Green
            "#64B5F6".toColorInt(), // Blue
            "#E57373".toColorInt(), // Red
            "#BA68C8".toColorInt()  // Purple
        )

        // 2. Use the index to pick the color
        wordsToHighlight.forEachIndexed { wordIndex, word ->
            val startIndex = text.indexOf(word, searchStartIndex)

            if (startIndex != -1) {
                val endIndex = startIndex + word.length

                // Assign the color from the palette
                val assignedColor = highlightColors[wordIndex % highlightColors.size]

                spannable.setSpan(
                    android.text.style.UnderlineSpan(),
                    startIndex, endIndex,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(assignedColor),
                    startIndex, endIndex,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                searchStartIndex = endIndex
            }
        }

        val sharedPrefs = holder.itemView.context.getSharedPreferences("LyriSyncPrefs", Context.MODE_PRIVATE)
        val subtitleMode = sharedPrefs.getInt("SUBTITLE_MODE", 2)

        when (subtitleMode) {
            0 -> {
                holder.furigana?.visibility = View.GONE
                holder.en.visibility = View.GONE
            }
            1 -> {
                holder.furigana?.visibility = View.VISIBLE
                holder.en.visibility = View.GONE
            }
            2 -> {
                holder.furigana?.visibility = View.VISIBLE
                holder.en.visibility = View.VISIBLE
            }
            3 -> {
                holder.furigana?.visibility = View.GONE
                holder.en.visibility = View.VISIBLE
            }
        }

        holder.jp.text = spannable
    }

    override fun getItemCount() = lyrics.size
}