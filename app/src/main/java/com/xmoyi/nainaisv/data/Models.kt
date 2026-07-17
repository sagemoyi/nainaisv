package com.xmoyi.nainaisv.data

data class DramaItem(
    val id: String,
    val bvid: String,
    val cid: Long,
    val page: Int,
    val title: String,
    val ownerMid: Long,
    val ownerName: String,
    val coverUrl: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val publishedAt: Long,
    val playable: Boolean = true,
    val candidate: Boolean = false,
    val score: Int = 0,
)

data class WatchState(
    val dramaId: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val completion: Float = 0f,
    val skipCount: Int = 0,
    val lastWatchedAt: Long = 0,
    val completed: Boolean = false,
)

data class PlaybackSource(
    val urls: List<String>,
    val backupUrls: List<String>,
    val headers: Map<String, String>,
    val quality: Int,
    val expiresAt: Long,
)

data class SearchCandidate(
    val bvid: String,
    val title: String,
    val ownerMid: Long,
    val ownerName: String,
    val coverUrl: String,
    val durationMs: Long,
    val publishedAt: Long,
)

data class CreatorUpload(
    val bvid: String,
    val title: String,
    val ownerMid: Long,
    val ownerName: String,
    val coverUrl: String,
    val durationMs: Long,
    val publishedAt: Long,
)

data class VideoDetails(
    val items: List<DramaItem>,
    val paidOrRestricted: Boolean,
)
