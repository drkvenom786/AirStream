package com.github.airstream.ui.fragments

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.airstream.R
import com.github.airstream.api.obj.StreamItem
import com.github.airstream.api.obj.Streams
import com.github.airstream.constants.IntentData
import com.github.airstream.databinding.FragmentShortsBinding
import com.github.airstream.db.DatabaseHelper
import com.github.airstream.enums.ShareObjectType
import com.github.airstream.extensions.toID
import com.github.airstream.helpers.DownloadHelper
import com.github.airstream.helpers.NavigationHelper
import com.github.airstream.helpers.PlayerHelper
import com.github.airstream.helpers.ProxyHelper
import com.github.airstream.obj.ShareData
import com.github.airstream.player.SabrMediaSource
import com.github.airstream.player.manifest.SabrManifest
import com.github.airstream.ui.activities.AbstractPlayerHostActivity
import com.github.airstream.ui.activities.MainActivity
import com.github.airstream.ui.adapters.ShortsAdapter
import com.github.airstream.ui.dialogs.AddToPlaylistDialog
import com.github.airstream.ui.dialogs.ShareDialog
import com.github.airstream.ui.models.CommonPlayerViewModel
import com.github.airstream.ui.models.ShortsViewModel
import com.github.airstream.ui.sheets.CommentsSheet
import com.github.airstream.util.DeArrowUtil
import com.github.airstream.util.YoutubeHlsPlaylistParser
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ShortsFragment : Fragment(R.layout.fragment_shorts) {

    private var _binding: FragmentShortsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ShortsViewModel by viewModels()
    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()

    private var exoPlayer: ExoPlayer? = null
    private var shortsAdapter: ShortsAdapter? = null
    private var currentPlayingPosition = -1
    private var currentStreamsJob: Job? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                if (player.isPlaying && currentPlayingPosition >= 0) {
                    val holder = getCurrentViewHolder()
                    holder?.updateProgress(player.currentPosition, player.duration)
                }
            }
            progressHandler.postDelayed(this, 200)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentShortsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        initPlayer()
        initViewPager()
        observeViewModel()

        val initialVideoId = arguments?.getString("videoId")
        viewModel.loadInitialShorts(requireContext(), initialVideoId)

        binding.retryButton.setOnClickListener {
            viewModel.loadInitialShorts(requireContext(), initialVideoId)
        }

        // Minimize / pause global player when entering shorts
        pauseMainVideoPlayer()
    }

    private fun pauseMainVideoPlayer() {
        (activity as? AbstractPlayerHostActivity)?.let { host ->
            host.runOnPlayerFragment {
                if (isAdded) {
                    // minimize player container
                    host.minimizePlayerContainerLayout()
                }
                false
            }
        }
    }

    private fun initPlayer() {
        if (exoPlayer != null) return

        // Ultra-low latency LoadControl for instant shorts playback
        val shortsLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1500,  // minBufferMs (1.5s)
                20000, // maxBufferMs (20s)
                250,   // bufferForPlaybackMs (instant start on 0.25s)
                500    // bufferForPlaybackAfterRebufferMs (0.5s rebuffer)
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        exoPlayer = ExoPlayer.Builder(requireContext())
            .setLoadControl(shortsLoadControl)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val holder = getCurrentViewHolder()
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                holder?.setBuffering(true)
                            }
                            Player.STATE_READY -> {
                                holder?.setBuffering(false)
                                holder?.hideThumbnail()
                            }
                            Player.STATE_ENDED -> {
                                holder?.setBuffering(false)
                            }
                            else -> {
                                holder?.setBuffering(false)
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        val holder = getCurrentViewHolder()
                        if (isPlaying) {
                            holder?.setBuffering(false)
                            holder?.hideThumbnail()
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        super.onPlayerError(error)
                        val pos = currentPlayingPosition
                        val totalCount = shortsAdapter?.itemCount ?: 0
                        if (pos + 1 < totalCount && isAdded) {
                            binding.shortsViewPager.setCurrentItem(pos + 1, true)
                        }
                    }
                })
            }
    }

    private fun initViewPager() {
        shortsAdapter = ShortsAdapter(
            onChannelClick = { uploaderUrl ->
                NavigationHelper.navigateChannel(requireContext(), uploaderUrl)
            },
            onCommentsClick = { videoId, channelAvatar ->
                CommentsSheet()
                    .apply {
                        arguments = bundleOf(
                            IntentData.videoId to videoId,
                            IntentData.channelAvatar to channelAvatar
                        )
                    }
                    .show(childFragmentManager, CommentsSheet::class.java.name)
            },
            onShareClick = { streamItem ->
                val videoId = streamItem.url.orEmpty().toID()
                val bundle = bundleOf(
                    IntentData.id to videoId,
                    IntentData.shareObjectType to ShareObjectType.VIDEO,
                    IntentData.shareData to ShareData(
                        currentVideo = streamItem.title,
                        currentPosition = (exoPlayer?.currentPosition ?: 0L) / 1000
                    )
                )
                val newShareDialog = ShareDialog()
                newShareDialog.arguments = bundle
                newShareDialog.show(childFragmentManager, ShareDialog::class.java.name)
            },
            onMoreOptionsClick = { streamItem ->
                showMoreOptionsDialog(streamItem)
            },
            onLikeClick = { streamItem, likeIcon, likeCountText ->
                val videoId = streamItem.url.orEmpty().toID()
                val isLiked = viewModel.toggleLike(videoId)
                getCurrentViewHolder()?.setLikeActive(isLiked)
            },
            onDislikeClick = { streamItem, dislikeIcon ->
                val videoId = streamItem.url.orEmpty().toID()
                val isDisliked = viewModel.toggleDislike(videoId)
                getCurrentViewHolder()?.setDislikeActive(isDisliked)
            },
            onSingleTap = { position, holder ->
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.pause()
                        holder.showPlayPauseIndicator(false)
                    } else {
                        player.play()
                        holder.showPlayPauseIndicator(true)
                    }
                }
            },
            onDoubleTap = { position, holder ->
                val item = shortsAdapter?.currentList?.getOrNull(position) ?: return@ShortsAdapter
                val videoId = item.url.orEmpty().toID()
                viewModel.likedShorts[videoId] = true
                viewModel.dislikedShorts[videoId] = false
                holder.setLikeActive(true)
                holder.setDislikeActive(false)
                holder.showHeartAnimation()
            },
            onFastForwardStart = { holder ->
                exoPlayer?.playbackParameters = PlaybackParameters(2.0f)
                holder.binding.fastForwardIndicator.isVisible = true
            },
            onFastForwardEnd = { holder ->
                exoPlayer?.playbackParameters = PlaybackParameters(1.0f)
                holder.binding.fastForwardIndicator.isVisible = false
            }
        )

        binding.shortsViewPager.apply {
            adapter = shortsAdapter
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = 1

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    if (position != currentPlayingPosition) {
                        playShortAtPosition(position)
                    }

                    // Pagination check: load more when near end
                    val totalCount = shortsAdapter?.itemCount ?: 0
                    if (position >= totalCount - 3 && totalCount > 0) {
                        viewModel.loadMoreShorts(requireContext())
                    }
                }
            })
        }
    }

    private fun observeViewModel() {
        viewModel.shortsList.observe(viewLifecycleOwner) { shorts ->
            shortsAdapter?.submitList(shorts) {
                if (currentPlayingPosition == -1 && shorts.isNotEmpty()) {
                    playShortAtPosition(0)
                }
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.isVisible = isLoading
            if (isLoading) {
                binding.errorLayout.isGone = true
            }
        }

        viewModel.isError.observe(viewLifecycleOwner) { isError ->
            binding.errorLayout.isVisible = isError
        }
    }

    private fun playShortAtPosition(position: Int) {
        currentPlayingPosition = position
        val shorts = shortsAdapter?.currentList ?: return
        if (position !in shorts.indices) return

        val item = shorts[position]
        val videoId = item.url.orEmpty().toID()
        if (videoId.isBlank()) return

        // Detach player from any previous view
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()

        val holder = getCurrentViewHolder()
        holder?.binding?.playerView?.player = exoPlayer
        holder?.setBuffering(true)
        holder?.setLikeActive(viewModel.likedShorts[videoId] == true)
        holder?.setDislikeActive(viewModel.dislikedShorts[videoId] == true)

        // Save to watch history
        if (PlayerHelper.watchHistoryEnabled) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val historyItem = item.toWatchHistoryItem(videoId)
                    DatabaseHelper.addToWatchHistory(historyItem)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        currentStreamsJob?.cancel()
        currentStreamsJob = lifecycleScope.launch {
            val streams = viewModel.getStreamInfo(videoId)
            if (streams != null && currentPlayingPosition == position && isAdded) {
                applyMediaSource(videoId, streams)
                exoPlayer?.prepare()
                exoPlayer?.play()
            } else if (streams == null && currentPlayingPosition == position && isAdded) {
                // If stream is unavailable, automatically advance to next short
                val totalCount = shortsAdapter?.itemCount ?: 0
                if (position + 1 < totalCount) {
                    binding.shortsViewPager.setCurrentItem(position + 1, true)
                } else {
                    viewModel.loadMoreShorts(requireContext())
                }
            }
        }

        // Pre-fetch next short info
        if (position + 1 < shorts.size) {
            val nextVideoId = shorts[position + 1].url.orEmpty().toID()
            lifecycleScope.launch(Dispatchers.IO) {
                viewModel.getStreamInfo(nextVideoId)
            }
        }
    }

    private fun applyMediaSource(videoId: String, streams: Streams) {
        val player = exoPlayer ?: return
        val context = requireContext()
        val dataSourceFactory = DefaultDataSource.Factory(context)

        // Find best video stream (720p, 1080p, 480p)
        val videoStream = streams.videoStreams.firstOrNull {
            !it.url.isNullOrBlank() && it.url?.startsWith("sabr://") != true &&
                    (it.quality == "720p" || it.quality == "1080p" || it.quality == "480p" || it.format == "MPEG_4")
        } ?: streams.videoStreams.firstOrNull { !it.url.isNullOrBlank() && it.url?.startsWith("sabr://") != true }

        // Find best audio stream (M4A or Opus)
        val audioStream = streams.audioStreams.firstOrNull {
            !it.url.isNullOrBlank() && it.url?.startsWith("sabr://") != true
        }

        when {
            // 1. Merged Progressive Video + Audio for instant start with full sound
            videoStream != null && audioStream != null && !videoStream.url.isNullOrBlank() && !audioStream.url.isNullOrBlank() -> {
                val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(videoStream.url!!.toUri()))
                val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(audioStream.url!!.toUri()))
                val mergedSource = MergingMediaSource(videoSource, audioSource)
                player.setMediaSource(mergedSource)
            }
            // 2. SABR adaptive streaming (includes both video & audio)
            !streams.isLive && streams.serverAbrStreamingUrl != null && streams.videoPlaybackUstreamerConfig != null -> {
                val sabrMediaSourceFactory = SabrMediaSource.Factory(
                    SabrManifest(videoId, streams)
                )
                val mediaItem = MediaItem.Builder()
                    .setUri(streams.serverAbrStreamingUrl.toUri())
                    .setMimeType("application/vnd.yt-ump")
                    .build()
                val mediaSource = sabrMediaSourceFactory.createMediaSource(mediaItem)
                player.setMediaSource(mediaSource)
            }
            // 3. DASH source (includes video + audio AdaptationSets)
            streams.videoStreams.any { it.url?.startsWith("sabr://") != true } -> {
                val dashUri = if (streams.isLive && streams.dash != null) {
                    ProxyHelper.rewriteUrlUsingProxyPreference(streams.dash).toUri()
                } else {
                    PlayerHelper.createDashSource(
                        streams.copy(videoStreams = streams.videoStreams.filter { it.url?.startsWith("sabr://") != true }),
                        context
                    )
                }
                val mediaItem = MediaItem.Builder()
                    .setUri(dashUri)
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build()
                player.setMediaItem(mediaItem)
            }
            // 4. HLS
            streams.hls != null -> {
                val hlsMediaSourceFactory = HlsMediaSource.Factory(dataSourceFactory)
                    .setPlaylistParserFactory(YoutubeHlsPlaylistParser.Factory())
                val mediaItem = MediaItem.Builder()
                    .setUri(ProxyHelper.rewriteUrlUsingProxyPreference(streams.hls).toUri())
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                val mediaSource = hlsMediaSourceFactory.createMediaSource(mediaItem)
                player.setMediaSource(mediaSource)
            }
            // 5. Fallback progressive stream
            videoStream != null && !videoStream.url.isNullOrBlank() -> {
                val mediaItem = MediaItem.fromUri(videoStream.url!!.toUri())
                player.setMediaItem(mediaItem)
            }
            else -> Unit
        }
    }

    private fun getCurrentViewHolder(): ShortsAdapter.ShortsViewHolder? {
        val recyclerView = binding.shortsViewPager.getChildAt(0) as? RecyclerView ?: return null
        return recyclerView.findViewHolderForAdapterPosition(currentPlayingPosition) as? ShortsAdapter.ShortsViewHolder
    }

    private fun showMoreOptionsDialog(streamItem: StreamItem) {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_player_more, null)
        dialog.setContentView(view)

        view.findViewById<TextView>(R.id.btnDownload)?.setOnClickListener {
            dialog.dismiss()
            val videoId = streamItem.url.orEmpty().toID()
            DownloadHelper.startDownloadDialog(requireContext(), childFragmentManager, videoId)
        }

        view.findViewById<TextView>(R.id.btnSave)?.setOnClickListener {
            dialog.dismiss()
            AddToPlaylistDialog().apply {
                arguments = bundleOf(IntentData.videoInfo to streamItem)
            }.show(childFragmentManager, AddToPlaylistDialog::class.java.name)
        }

        view.findViewById<TextView>(R.id.btnAudio)?.setOnClickListener {
            dialog.dismiss()
            NavigationHelper.navigateVideo(
                requireContext(),
                com.github.airstream.parcelable.PlayerData(videoId = streamItem.url.orEmpty().toID()),
                audioOnlyPlayerRequested = true
            )
        }

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        pauseMainVideoPlayer()
        progressHandler.post(progressUpdateRunnable)
        if (currentPlayingPosition >= 0) {
            exoPlayer?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        progressHandler.removeCallbacks(progressUpdateRunnable)
        exoPlayer?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        progressHandler.removeCallbacks(progressUpdateRunnable)
        currentStreamsJob?.cancel()
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        _binding = null
    }
}