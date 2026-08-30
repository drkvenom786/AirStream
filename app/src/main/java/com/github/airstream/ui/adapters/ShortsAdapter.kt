package com.github.airstream.ui.adapters

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.graphics.Color
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.airstream.R
import com.github.airstream.api.obj.StreamItem
import com.github.airstream.constants.PreferenceKeys
import com.github.airstream.databinding.ItemShortBinding
import com.github.airstream.extensions.formatShort
import com.github.airstream.extensions.toID
import com.github.airstream.helpers.ImageHelper
import com.github.airstream.helpers.PreferenceHelper
import com.github.airstream.ui.adapters.callbacks.DiffUtilItemCallback
import com.github.airstream.ui.extensions.setupSubscriptionButton
import com.github.airstream.util.TextUtils

class ShortsAdapter(
    private val onChannelClick: (String?) -> Unit,
    private val onCommentsClick: (String, String?) -> Unit,
    private val onShareClick: (StreamItem) -> Unit,
    private val onMoreOptionsClick: (StreamItem) -> Unit,
    private val onLikeClick: (StreamItem, ImageView, TextView) -> Unit,
    private val onDislikeClick: (StreamItem, ImageView) -> Unit,
    private val onSingleTap: (Int, ShortsViewHolder) -> Unit,
    private val onDoubleTap: (Int, ShortsViewHolder) -> Unit,
    private val onFastForwardStart: (ShortsViewHolder) -> Unit,
    private val onFastForwardEnd: (ShortsViewHolder) -> Unit,
) : ListAdapter<StreamItem, ShortsAdapter.ShortsViewHolder>(DiffUtilItemCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortsViewHolder {
        val binding = ItemShortBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ShortsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ShortsViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class ShortsViewHolder(val binding: ItemShortBinding) : RecyclerView.ViewHolder(binding.root) {
        var currentStreamItem: StreamItem? = null

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: StreamItem) {
            currentStreamItem = item
            val videoId = item.url?.toID().orEmpty()
            val context = binding.root.context

            // Dynamic margin adjustment for pill-shaped navigation bar
            val isPill = PreferenceHelper.getBoolean(PreferenceKeys.PILL_SHAPED_NAV_BAR, false)
            val extraBottom = if (isPill) (80 * context.resources.displayMetrics.density).toInt() else 0
            val metaBaseMargin = (24 * context.resources.displayMetrics.density).toInt()
            val railBaseMargin = (32 * context.resources.displayMetrics.density).toInt()

            binding.metaContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = metaBaseMargin + extraBottom
            }
            binding.actionRail.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = railBaseMargin + extraBottom
            }
            binding.progressBarBottom.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = extraBottom
            }

            // Thumbnail
            ImageHelper.loadImage(item.thumbnail, binding.thumbnailImage)
            binding.thumbnailImage.isVisible = true
            binding.bufferingProgress.isVisible = false
            binding.playPauseIndicator.alpha = 0f
            binding.heartAnimationView.alpha = 0f
            binding.fastForwardIndicator.isVisible = false

            // Title & Channel info
            binding.shortTitle.text = item.title ?: ""
            binding.channelName.text = item.uploaderName ?: context.getString(R.string.unknown)

            ImageHelper.loadImage(item.uploaderAvatar, binding.channelAvatar, true)

            // Channel click
            binding.channelRow.setOnClickListener {
                onChannelClick(item.uploaderUrl)
            }

            // Subscribe button
            val channelId = item.uploaderUrl?.toID()
            if (!channelId.isNullOrBlank()) {
                binding.subscribeButton.isVisible = true
                binding.subscribeButton.setupSubscriptionButton(
                    channelId = channelId,
                    channelName = item.uploaderName.orEmpty(),
                    channelAvatar = item.uploaderAvatar,
                    channelVerified = item.uploaderVerified ?: false
                )
            } else {
                binding.subscribeButton.isVisible = false
            }

            // Audio track info
            val audioTitle = if (!item.uploaderName.isNullOrBlank()) {
                "${item.uploaderName} • Original Sound"
            } else {
                "Original Sound"
            }
            binding.audioTrackTitle.text = audioTitle
            binding.audioTrackTitle.isSelected = true

            // Like, Comments count format
            val likesFormatted = if (item.views != null && item.views > 0) {
                (item.views / 8).coerceAtLeast(1).formatShort()
            } else {
                "Like"
            }
            binding.likeCountText.text = likesFormatted

            val commentsFormatted = if (item.views != null && item.views > 0) {
                (item.views / 50).coerceAtLeast(1).formatShort()
            } else {
                "0"
            }
            binding.commentsCountText.text = commentsFormatted

            // Button actions
            binding.likeContainer.setOnClickListener {
                onLikeClick(item, binding.likeIcon, binding.likeCountText)
            }

            binding.dislikeContainer.setOnClickListener {
                onDislikeClick(item, binding.dislikeIcon)
            }

            binding.commentsContainer.setOnClickListener {
                onCommentsClick(videoId, item.uploaderAvatar)
            }

            binding.shareContainer.setOnClickListener {
                onShareClick(item)
            }

            binding.moreOptionsContainer.setOnClickListener {
                onMoreOptionsClick(item)
            }

            // Touch gesture detector for tap, double-tap, and long press
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onSingleTap(pos, this@ShortsViewHolder)
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onDoubleTap(pos, this@ShortsViewHolder)
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    onFastForwardStart(this@ShortsViewHolder)
                }
            })

            binding.touchArea.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    onFastForwardEnd(this@ShortsViewHolder)
                }
                true
            }
        }

        fun showPlayPauseIndicator(isPlaying: Boolean) {
            val indicator = binding.playPauseIndicator
            indicator.setImageResource(if (isPlaying) R.drawable.ic_play else R.drawable.ic_pause)
            indicator.alpha = 0.9f
            indicator.scaleX = 1.3f
            indicator.scaleY = 1.3f
            indicator.animate()
                .alpha(0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(600)
                .start()
        }

        fun showHeartAnimation() {
            val heart = binding.heartAnimationView
            heart.alpha = 1f
            heart.scaleX = 0.5f
            heart.scaleY = 0.5f
            heart.animate()
                .scaleX(1.4f)
                .scaleY(1.4f)
                .alpha(0f)
                .setDuration(700)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        heart.alpha = 0f
                    }
                })
                .start()
        }

        fun setLikeActive(isActive: Boolean) {
            val color = if (isActive) Color.parseColor("#FF2D55") else Color.WHITE
            binding.likeIcon.setColorFilter(color)
        }

        fun setDislikeActive(isActive: Boolean) {
            val color = if (isActive) Color.parseColor("#3B82F6") else Color.WHITE
            binding.dislikeIcon.setColorFilter(color)
        }

        fun setBuffering(isBuffering: Boolean) {
            binding.bufferingProgress.isVisible = isBuffering
        }

        fun hideThumbnail() {
            binding.thumbnailImage.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { binding.thumbnailImage.isVisible = false; binding.thumbnailImage.alpha = 1f }
                .start()
        }

        fun updateProgress(currentMs: Long, durationMs: Long) {
            if (durationMs <= 0) {
                binding.progressBarBottom.progress = 0
                return
            }
            val progress = ((currentMs.toDouble() / durationMs.toDouble()) * 1000).toInt()
            binding.progressBarBottom.progress = progress.coerceIn(0, 1000)
        }
    }
}

