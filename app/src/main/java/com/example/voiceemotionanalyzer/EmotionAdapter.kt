package com.example.voiceemotionanalyzer

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.voiceemotionanalyzer.databinding.ItemEmotionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EmotionAdapter : ListAdapter<EmotionPoint, EmotionAdapter.EmotionViewHolder>(EmotionDiffCallback()) {

    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmotionViewHolder {
        val binding = ItemEmotionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EmotionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EmotionViewHolder, position: Int) {
        holder.bind(getItem(position), formatter)
    }

    class EmotionViewHolder(private val binding: ItemEmotionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: EmotionPoint, formatter: SimpleDateFormat) {
            val context = binding.root.context
            
            // Time
            binding.timeText.text = formatter.format(Date(item.timeMs))
            
            // Emotion badge
            binding.emotionText.text = item.emotion.replaceFirstChar { it.uppercase() }
            val emotionColor = EmotionPoint.getEmotionColor(item.emotion)
            val emotionLightColor = EmotionPoint.getEmotionLightColor(item.emotion)
            binding.emotionText.setTextColor(emotionColor)
            
            // Update badge background
            val badgeBackground = binding.emotionText.background as? GradientDrawable
            badgeBackground?.setColor(emotionLightColor)
            
            // Emotion indicator bar
            val indicatorDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(emotionColor)
                cornerRadius = 4f
            }
            binding.emotionIndicator.background = indicatorDrawable
            
            // Transcript text
            binding.transcriptText.text = item.text.ifBlank { "—" }
            
            // Confidence score
            val confidencePercent = (item.confidence * 100).toInt()
            binding.scoreText.text = context.getString(
                R.string.confidence_label
            ) + " $confidencePercent%"
        }
    }

    class EmotionDiffCallback : DiffUtil.ItemCallback<EmotionPoint>() {
        override fun areItemsTheSame(oldItem: EmotionPoint, newItem: EmotionPoint): Boolean {
            return oldItem.timeMs == newItem.timeMs
        }

        override fun areContentsTheSame(oldItem: EmotionPoint, newItem: EmotionPoint): Boolean {
            return oldItem == newItem
        }
    }
}
