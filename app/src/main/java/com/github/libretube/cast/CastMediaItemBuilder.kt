package com.github.libretube.cast

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.github.libretube.api.obj.Streams
import com.github.libretube.helpers.ProxyHelper

/**
 * Builder for creating MediaItems optimized for Google Cast
 * Converts LibreTube Streams to Media3 MediaItem with proper metadata and subtitles
 */
object CastMediaItemBuilder {
    
    /**
     * Build a MediaItem for Cast from Streams object
     * Prefers HLS streams for best Cast compatibility
     */
    fun buildFromStreams(streams: Streams, videoId: String): MediaItem {
        val streamUrl = getBestStreamUrl(streams, videoId)
        
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(videoId)
            .setMediaMetadata(buildMetadata(streams))
            .setSubtitleConfigurations(buildSubtitles(streams))
            .build()
    }
    
    /**
     * Build a minimal MediaItem when full Streams data is not available
     * Falls back to YouTube URL
     */
    fun buildMinimal(videoId: String, title: String? = null): MediaItem {
        return MediaItem.Builder()
            .setUri("https://www.youtube.com/watch?v=$videoId")
            .setMediaId(videoId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title ?: videoId)
                    .build()
            )
            .build()
    }
    
    /**
     * Get best stream URL for Cast
     * Priority: HLS > DASH > highest quality video stream
     */
    private fun getBestStreamUrl(streams: Streams, videoId: String): Uri {
        // Prefer HLS for Cast (best compatibility)
        streams.hls?.let { 
            return ProxyHelper.unwrapUrl(it).toUri()
        }
        
        // Try DASH as fallback
        streams.dash?.let {
            return ProxyHelper.unwrapUrl(it).toUri()
        }
        
        // Use highest quality video stream
        val bestStream = streams.videoStreams
            .filter { it.url != null }
            .maxByOrNull { it.height ?: 0 }
        
        bestStream?.let {
            val url = it.url ?: return "https://www.youtube.com/watch?v=$videoId".toUri()
            return ProxyHelper.unwrapUrl(url).toUri()
        }
        
        // Last resort: YouTube URL
        return "https://www.youtube.com/watch?v=$videoId".toUri()
    }
    
    /**
     * Build metadata for Cast receiver display
     */
    private fun buildMetadata(streams: Streams): MediaMetadata {
        return MediaMetadata.Builder()
            .setTitle(streams.title)
            .setArtist(streams.uploader)
            .setArtworkUri(getArtworkUri(streams))
            .setDescription(streams.description)
            .build()
    }
    
    /**
     * Get artwork URI for Cast
     * Uses proxy if configured
     */
    private fun getArtworkUri(streams: Streams): Uri? {
        val thumbnailUrl = streams.thumbnailUrl ?: return null
        return ProxyHelper.unwrapUrl(thumbnailUrl).toUri()
    }
    
    /**
     * Build subtitle configurations for Cast
     */
    private fun buildSubtitles(streams: Streams): List<MediaItem.SubtitleConfiguration> {
        return streams.subtitles.mapNotNull { subtitle ->
            try {
                val subUrl = subtitle.url ?: return@mapNotNull null
                MediaItem.SubtitleConfiguration.Builder(
                    ProxyHelper.unwrapUrl(subUrl).toUri()
                )
                    .setMimeType(getMimeType(subtitle.mimeType ?: ""))
                    .setLanguage(subtitle.code)
                    .setLabel(subtitle.name)
                    .setSelectionFlags(0) // Not auto-selected
                    .build()
            } catch (e: Exception) {
                null // Skip invalid subtitles
            }
        }
    }
    
    /**
     * Normalize MIME type for subtitles
     */
    private fun getMimeType(mimeType: String): String {
        return when {
            mimeType.contains("vtt") -> MimeTypes.TEXT_VTT
            mimeType.contains("srt") -> MimeTypes.APPLICATION_SUBRIP
            mimeType.contains("ttml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.TEXT_VTT // Default
        }
    }
}
