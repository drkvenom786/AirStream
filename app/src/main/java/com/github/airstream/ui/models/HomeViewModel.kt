package com.github.airstream.ui.models

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.airstream.api.MediaServiceRepository
import com.github.airstream.api.PlaylistsHelper
import com.github.airstream.api.SubscriptionHelper
import com.github.airstream.api.TrendingCategory
import com.github.airstream.api.obj.Playlists
import com.github.airstream.api.obj.StreamItem
import com.github.airstream.constants.PreferenceKeys
import com.github.airstream.db.DatabaseHelper
import com.github.airstream.db.DatabaseHolder
import com.github.airstream.db.obj.PlaylistBookmark
import com.github.airstream.extensions.runSafely
import com.github.airstream.extensions.updateIfChanged
import com.github.airstream.helpers.PlayerHelper
import com.github.airstream.helpers.PreferenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel : ViewModel() {
    private val hideWatched
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.HIDE_WATCHED_FROM_FEED,
            false
        )
    private val showUpcoming
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.SHOW_UPCOMING_IN_FEED,
            true
        )

    val trending: MutableLiveData<Pair<TrendingCategory, TrendsViewModel.TrendingStreams>> =
        MutableLiveData(null)
    val feed: MutableLiveData<List<StreamItem>> = MutableLiveData(null)
    val bookmarks: MutableLiveData<List<PlaylistBookmark>> = MutableLiveData(null)
    val playlists: MutableLiveData<List<Playlists>> = MutableLiveData(null)
    val continueWatching: MutableLiveData<List<StreamItem>> = MutableLiveData(null)
    val isLoading: MutableLiveData<Boolean> = MutableLiveData(true)
    val loadedSuccessfully: MutableLiveData<Boolean> = MutableLiveData(false)

    private val sections get() = listOf(trending, feed, bookmarks, playlists, continueWatching)

    private var loadHomeJob: Job? = null

    fun loadHomeFeed(
        context: Context,
        subscriptionsViewModel: SubscriptionsViewModel,
        visibleItems: Set<String>,
        onUnusualLoadTime: () -> Unit
    ) {
        isLoading.value = true

        loadHomeJob?.cancel()
        loadHomeJob = viewModelScope.launch {
            val result = async {
                awaitAll(
                    async { if (visibleItems.contains(TRENDING)) loadTrending(context) },
                    async { if (visibleItems.contains(FEATURED)) loadFeed(subscriptionsViewModel) },
                    async { if (visibleItems.contains(BOOKMARKS)) loadBookmarks() },
                    async { if (visibleItems.contains(PLAYLISTS)) loadPlaylists() },
                    async { if (visibleItems.contains(WATCHING)) loadVideosToContinueWatching() }
                )
                loadedSuccessfully.value = sections.any { it.value != null }
                isLoading.value = false
            }

            withContext(Dispatchers.IO) {
                delay(UNUSUAL_LOAD_TIME_MS)
                if (result.isActive) {
                    onUnusualLoadTime.invoke()
                }
            }
        }
    }

    private var allRecommendations = mutableListOf<StreamItem>()
    private var isLoadingMoreRecommendations = false
    val loadingMore = MutableLiveData<Boolean>(false)

    fun loadMoreRecommendations(context: Context) {
        if (isLoadingMoreRecommendations) return
        isLoadingMoreRecommendations = true
        loadingMore.value = true
        viewModelScope.launch {
            try {
                val region = PreferenceHelper.getTrendingRegion(context)
                val category = PreferenceHelper.getString(
                    PreferenceKeys.TRENDING_CATEGORY,
                    TrendingCategory.LIVE.name
                ).let { TrendingCategory.valueOf(it) }

                val excludeUrls = allRecommendations.mapNotNull { it.url }.toSet()
                val moreVideos = fetchRecommendations(context, 10, excludeUrls)
                allRecommendations.addAll(moreVideos)
                trending.postValue(Pair(category, TrendsViewModel.TrendingStreams(region, allRecommendations.toList())))
            } finally {
                isLoadingMoreRecommendations = false
                loadingMore.postValue(false)
            }
        }
    }

    private suspend fun fetchRecommendations(context: Context, amount: Int, excludeUrls: Set<String>): List<StreamItem> {
        return withContext(Dispatchers.IO) {
            val history = DatabaseHolder.Database.watchHistoryDao().getAll()
            val newRecommendations = mutableListOf<StreamItem>()
            
            if (history.isNotEmpty()) {
                val shuffledHistory = history.shuffled()
                for (video in shuffledHistory) {
                    try {
                        val streams = MediaServiceRepository.instance.getStreams(video.videoId)
                        val related = streams.relatedStreams.filter { !it.isLive && it.url !in excludeUrls && it.url !in newRecommendations.map { r -> r.url } }
                        newRecommendations.addAll(related.shuffled())
                        if (newRecommendations.size >= amount) {
                            return@withContext newRecommendations.take(amount)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    if (newRecommendations.size >= amount) break
                }
            }
            
            // If we still need more, or history is empty, fallback to a random search
            if (newRecommendations.size < amount) {
                val defaultQueries = listOf("popular music", "popular podcast", "tech news", "lofi hip hop", "funny videos", "documentary", "cooking recipes", "coding")
                val query = defaultQueries.random()
                try {
                    val searchResult = MediaServiceRepository.instance.getSearchResults(query, "all")
                    val searchVideos = searchResult.items.filterIsInstance<StreamItem>().filter { 
                        !it.isLive && it.url !in excludeUrls && it.url !in newRecommendations.map { r -> r.url }
                    }
                    newRecommendations.addAll(searchVideos.shuffled())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            return@withContext newRecommendations.take(amount)
        }
    }

    private suspend fun loadTrending(context: Context) {
        val region = PreferenceHelper.getTrendingRegion(context)
        val category = PreferenceHelper.getString(
            PreferenceKeys.TRENDING_CATEGORY,
            TrendingCategory.LIVE.name
        ).let { TrendingCategory.valueOf(it) }

        allRecommendations.clear()
        
        val excludeUrls = emptySet<String>()
        val videos = fetchRecommendations(context, 10, excludeUrls)
        
        if (videos.isNotEmpty()) {
            allRecommendations.addAll(videos)
        }
        
        trending.postValue(
            Pair(
                category,
                TrendsViewModel.TrendingStreams(region, allRecommendations.toList())
            )
        )
    }

    private suspend fun loadFeed(subscriptionsViewModel: SubscriptionsViewModel) {
        runSafely(
            onSuccess = { videos -> feed.updateIfChanged(videos) },
            ioBlock = { tryLoadFeed(subscriptionsViewModel) }
        )
    }

    private suspend fun loadBookmarks() {
        runSafely(
            onSuccess = { newBookmarks -> bookmarks.updateIfChanged(newBookmarks) },
            ioBlock = { DatabaseHolder.Database.playlistBookmarkDao().getAll() }
        )
    }

    private suspend fun loadPlaylists() {
        runSafely(
            onSuccess = { newPlaylists -> playlists.updateIfChanged(newPlaylists) },
            ioBlock = { PlaylistsHelper.getPlaylists() }
        )
    }

    private suspend fun loadVideosToContinueWatching() {
        if (!PlayerHelper.watchHistoryEnabled) return
        runSafely(
            onSuccess = { videos -> continueWatching.updateIfChanged(videos) },
            ioBlock = ::loadWatchingFromDB
        )
    }

    private suspend fun loadWatchingFromDB(): List<StreamItem> {
        val videos = DatabaseHelper.getWatchHistoryPage(1, 20)
            .filter { !it.isShort && (it.duration == null || it.duration > 90) }

        return DatabaseHelper
            .filterUnwatched(videos.map { it.toStreamItem() })
    }

    private suspend fun tryLoadFeed(subscriptionsViewModel: SubscriptionsViewModel): List<StreamItem> {
        // use cached feed if available, otherwise load feed from API/database
        val feed = subscriptionsViewModel.videoFeed.value ?: run {
            SubscriptionHelper.getFeed(forceRefresh = false).also {
                subscriptionsViewModel.videoFeed.postValue(it)
            }
        }

        return DatabaseHelper.filterByStreamTypeAndWatchPosition(feed, hideWatched, showUpcoming)
    }

    companion object {
        private const val UNUSUAL_LOAD_TIME_MS = 10000L
        private const val FEATURED = "featured"
        private const val WATCHING = "watching"
        private const val TRENDING = "trending"
        private const val BOOKMARKS = "bookmarks"
        private const val PLAYLISTS = "playlists"
    }
}
