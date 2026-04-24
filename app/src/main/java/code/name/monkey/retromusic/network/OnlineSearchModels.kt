package code.name.monkey.retromusic.network

import com.google.gson.annotations.SerializedName

// SoundCloud
data class ScSearchResponse(
    val success: Boolean,
    val results: List<ScTrack>
)

data class ScTrack(
    val title: String,
    val artist: String,
    val artwork: String,
    val url: String
) {
    val userSlug: String get() = url.trimEnd('/').substringBeforeLast('/').substringAfterLast('/')
    val trackSlug: String get() = url.trimEnd('/').substringAfterLast('/')
}

data class ScStreamResponse(
    val artist: String,
    val song: String,
    val artwork: String,
    @SerializedName("streamUrl") val streamUrl: String
)

// Spotify
data class SpTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val cover: String,
    @SerializedName("spotify_url") val spotifyUrl: String
)

// lrclib
data class LrcLibResponse(
    val id: Int?,
    val trackName: String?,
    val artistName: String?,
    val albumName: String?,
    val plainLyrics: String?,
    val syncedLyrics: String?
)
