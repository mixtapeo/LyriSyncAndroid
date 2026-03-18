package com.example.lyrisync

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
    }



    override fun getItemCount() = lyrics.size
}