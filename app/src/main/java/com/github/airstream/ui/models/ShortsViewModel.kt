package com.github.airstream.ui.models

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.airstream.api.MediaServiceRepository
import com.github.airstream.api.SubscriptionHelper
import com.github.airstream.api.obj.StreamItem
import com.github.airstream.api.obj.Streams
import com.github.airstream.db.DatabaseHelper
import com.github.airstream.db.DatabaseHolder
import com.github.airstream.extensions.toID
import com.github.airstream.helpers.PreferenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class ShortsViewModel : ViewModel() {

    private val _shortsList = MutableLiveData<List<StreamItem>>(emptyList())
    val shortsList: LiveData<List<StreamItem>> = _shortsList

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _isLoadingMore = MutableLiveData<Boolean>(false)
    val isLoadingMore: LiveData<Boolean> = _isLoadingMore

    private val _isError = MutableLiveData<Boolean>(false)
    val isError: LiveData<Boolean> = _isError

    private val seenVideoIds = mutableSetOf<String>()
    private val allShorts = mutableListOf<StreamItem>()
    private val streamsCache = ConcurrentHashMap<String, Streams>()

    // Local likes and dislikes state tracking
    val likedShorts = mutableMapOf<String, Boolean>()
    val dislikedShorts = mutableMapOf<String, Boolean>()

    // Recommendation queries based on user taste
    private val personalizedQueries = mutableListOf<String>()
    private var currentQueryIndex = 0
    private var nextPageToken: String? = null
    private var isFetching = false

    private val defaultCategories = listOf(
        "viral shorts",
        "tech shorts",
        "gaming shorts",
        "funny shorts",
        "science shorts",
        "music shorts",
        "animation shorts",
        "movie shorts",
        "coding shorts",
        "satisfying shorts"
    )

    fun loadInitialShorts(context: Context, initialVideoId: String? = null) {
        if (allShorts.isNotEmpty() && initialVideoId == null) {
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _isError.value = false
            seenVideoIds.clear()
            allShorts.clear()

            try {
                // Build personalized taste queries from user history & subscriptions
                buildPersonalizedQueries(context)

                // If initial video is provided, fetch its stream info and verify it's a short
                if (!initialVideoId.isNullOrBlank()) {
                    val initialId = initialVideoId.toID()
                    seenVideoIds.add(initialId)
                    try {
                        val streamInfo = withContext(Dispatchers.IO) {
                            MediaServiceRepository.instance.getStreams(initialId)
                        }
                        if (hasPlayableStream(streamInfo) && (streamInfo.duration <= 90 || streamInfo.isShort)) {
                            streamsCache[initialId] = streamInfo
                            val item = streamInfo.toStreamItem(initialId)
                            allShorts.add(item)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // Fetch initial batch of personalized shorts with verified playable stream URLs
                val fetchedShorts = withContext(Dispatchers.IO) {
                    fetchInitialPersonalizedBatch(context)
                }

                allShorts.addAll(fetchedShorts)
                _shortsList.value = allShorts.toList()
                _isError.value = allShorts.isEmpty()
            } catch (e: Exception) {
                e.printStackTrace()
                _isError.value = allShorts.isEmpty()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun buildPersonalizedQueries(context: Context) {
        personalizedQueries.clear()

        // 1. Taste from Subscriptions
        try {
            val subs = withContext(Dispatchers.IO) {
                SubscriptionHelper.getSubscriptions()
            }
            if (subs.isNotEmpty()) {
                val topSubs = subs.shuffled().take(6)
                topSubs.forEach { sub ->
                    if (!sub.name.isNullOrBlank()) {
                        personalizedQueries.add("${sub.name} shorts")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Taste from Watch History (Top creators and topics)
        try {
            val recentHistory = withContext(Dispatchers.IO) {
                DatabaseHelper.getWatchHistoryPage(1, 15)
            }
            if (recentHistory.isNotEmpty()) {
                val creators = recentHistory.mapNotNull { it.uploader }.distinct().shuffled().take(5)
                creators.forEach { creator ->
                    if (creator.isNotBlank()) {
                        personalizedQueries.add("$creator shorts")
                    }
                }

                // Extract keywords from titles
                val keywords = recentHistory.mapNotNull { it.title }
                    .flatMap { it.split(" ", "-", "|", "_") }
                    .map { it.lowercase().trim() }
                    .filter { it.length in 4..15 && it !in commonStopWords }
                    .groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }
                    .take(4)
                    .map { it.key }

                keywords.forEach { kw ->
                    personalizedQueries.add("$kw shorts")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Taste from Search History
        try {
            val searchHistory = withContext(Dispatchers.IO) {
                DatabaseHolder.Database.searchHistoryDao().getAll()
            }
            if (searchHistory.isNotEmpty()) {
                val topSearches = searchHistory.reversed().take(3)
                topSearches.forEach { item ->
                    if (item.query.isNotBlank()) {
                        personalizedQueries.add("${item.query} shorts")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. Append diverse fallback categories
        personalizedQueries.addAll(defaultCategories.shuffled())
        currentQueryIndex = 0
    }

    private suspend fun fetchInitialPersonalizedBatch(context: Context): List<StreamItem> {
        val candidates = mutableListOf<StreamItem>()
        val mediaRepo = MediaServiceRepository.instance

        val queriesToFetch = if (personalizedQueries.isNotEmpty()) {
            personalizedQueries.take(3)
        } else {
            defaultCategories.take(3)
        }
        currentQueryIndex = queriesToFetch.size

        val jobs = withContext(Dispatchers.IO) {
            queriesToFetch.map { query ->
                async {
                    try {
                        val searchResult = mediaRepo.getSearchResults(query, "all")
                        filterAndCollectShorts(searchResult.items)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }
        }

        val batches = jobs.awaitAll()
        batches.forEach { candidates.addAll(it) }

        if (candidates.size < 6) {
            try {
                val searchResult = mediaRepo.getSearchResults("#shorts", "all")
                nextPageToken = searchResult.nextpage
                candidates.addAll(filterAndCollectShorts(searchResult.items))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Pre-validate that only shorts with valid streaming links are added
        return validateAndCollectPlayableShorts(candidates)
    }

    fun loadMoreShorts(context: Context) {
        if (isFetching || _isLoadingMore.value == true) return
        isFetching = true
        _isLoadingMore.value = true

        viewModelScope.launch {
            try {
                val newShorts = withContext(Dispatchers.IO) {
                    fetchShortsBatch(context)
                }

                if (newShorts.isNotEmpty()) {
                    allShorts.addAll(newShorts)
                    _shortsList.value = allShorts.toList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isFetching = false
                _isLoadingMore.value = false
            }
        }
    }

    private suspend fun fetchShortsBatch(context: Context): List<StreamItem> {
        val candidates = mutableListOf<StreamItem>()
        val mediaRepo = MediaServiceRepository.instance

        if (!nextPageToken.isNullOrBlank()) {
            val query = getNextQuery()
            try {
                val searchResult = mediaRepo.getSearchResultsNextPage(query, "all", nextPageToken!!)
                nextPageToken = searchResult.nextpage
                val items = filterAndCollectShorts(searchResult.items)
                candidates.addAll(items)
            } catch (e: Exception) {
                nextPageToken = null
            }
        }

        if (candidates.size < 6) {
            val query = getNextQuery()
            try {
                val searchResult = mediaRepo.getSearchResults(query, "all")
                nextPageToken = searchResult.nextpage
                val items = filterAndCollectShorts(searchResult.items)
                candidates.addAll(items)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (candidates.size < 4) {
            try {
                val region = PreferenceHelper.getTrendingRegion(context)
                val trending = mediaRepo.getTrending(region, com.github.airstream.api.TrendingCategory.LIVE)
                val shortsFromTrending = filterAndCollectShorts(trending)
                candidates.addAll(shortsFromTrending)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return validateAndCollectPlayableShorts(candidates)
    }

    private suspend fun validateAndCollectPlayableShorts(candidates: List<StreamItem>): List<StreamItem> {
        val validItems = mutableListOf<StreamItem>()
        for (item in candidates) {
            val videoId = item.url?.toID() ?: continue
            val streams = getStreamInfo(videoId)
            if (streams != null && hasPlayableStream(streams)) {
                validItems.add(item)
            }
        }
        return validItems
    }

    private fun getNextQuery(): String {
        if (personalizedQueries.isEmpty()) {
            return defaultCategories[currentQueryIndex++ % defaultCategories.size]
        }
        val query = personalizedQueries[currentQueryIndex % personalizedQueries.size]
        currentQueryIndex++
        return query
    }

    private fun filterAndCollectShorts(items: List<Any>): List<StreamItem> {
        val filtered = mutableListOf<StreamItem>()
        for (item in items) {
            val streamItem = when (item) {
                is StreamItem -> item
                is com.github.airstream.api.obj.ContentItem -> item.toStreamItem()
                else -> null
            } ?: continue

            val videoId = streamItem.url?.toID() ?: continue
            if (seenVideoIds.contains(videoId)) continue

            val duration = streamItem.duration
            // Strict short validation: If duration is known and > 90 seconds, reject immediately (it's a long video!)
            if (duration != null && duration > 90) continue

            // Must be an actual Short (isShort flag, or duration <= 90s, or url contains "/shorts/")
            val isShortVideo = (streamItem.isShort && (duration == null || duration <= 90)) ||
                    (duration != null && duration in 1..90) ||
                    (streamItem.url?.contains("/shorts/", ignoreCase = true) == true)

            if (isShortVideo) {
                seenVideoIds.add(videoId)
                filtered.add(streamItem)
            }
        }
        return filtered
    }

    suspend fun getStreamInfo(videoId: String): Streams? {
        val id = videoId.toID()
        streamsCache[id]?.let {
            if (hasPlayableStream(it)) return it
        }

        return try {
            val streams = withContext(Dispatchers.IO) {
                MediaServiceRepository.instance.getStreams(id)
            }
            if (hasPlayableStream(streams)) {
                streamsCache[id] = streams
                streams
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun hasPlayableStream(streams: Streams): Boolean {
        return streams.videoStreams.any { !it.url.isNullOrBlank() } ||
                (!streams.isLive && streams.serverAbrStreamingUrl != null) ||
                (streams.dash != null) ||
                (streams.hls != null)
    }

    fun toggleLike(videoId: String): Boolean {
        val id = videoId.toID()
        val currentlyLiked = likedShorts[id] == true
        if (currentlyLiked) {
            likedShorts[id] = false
        } else {
            likedShorts[id] = true
            dislikedShorts[id] = false
        }
        return likedShorts[id] == true
    }

    fun toggleDislike(videoId: String): Boolean {
        val id = videoId.toID()
        val currentlyDisliked = dislikedShorts[id] == true
        if (currentlyDisliked) {
            dislikedShorts[id] = false
        } else {
            dislikedShorts[id] = true
            likedShorts[id] = false
        }
        return dislikedShorts[id] == true
    }

    companion object {
        private val commonStopWords = setOf(
            "this", "that", "with", "from", "your", "have", "more", "what", "when",
            "video", "watch", "live", "full", "free", "best", "part", "episode"
        )
    }
}