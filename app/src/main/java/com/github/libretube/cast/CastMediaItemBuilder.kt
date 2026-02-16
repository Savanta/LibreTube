package com.github.libretube.cast

import android.net.Uri
import android.util.Log
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
        val mimeType = getBestStreamMimeType(streams)
        
        Log.d("CastMediaItemBuilder", "Building Cast MediaItem: url=$streamUrl, mimeType=$mimeType")
        
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(videoId)
            .setMimeType(mimeType)
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
     * Priority: HLS > DASH > muxed video stream (with audio) > fallback
     */
    private fun getBestStreamUrl(streams: Streams, videoId: String): Uri {
        // Prefer HLS for Cast (best compatibility, includes audio+video)
        streams.hls?.takeIf { it.isNotBlank() }?.let { 
            val unwrapped = ProxyHelper.unwrapUrl(it)
            Log.d("CastMediaItemBuilder", "Using HLS: unwrapped=$unwrapped")
            return unwrapped.toUri()
        }
        
        // Try DASH as fallback (includes audio+video)
        streams.dash?.takeIf { it.isNotBlank() }?.let {
            val unwrapped = ProxyHelper.unwrapUrl(it)
            Log.d("CastMediaItemBuilder", "Using DASH: unwrapped=$unwrapped")
            return unwrapped.toUri()
        }
        
        // Find best muxed stream (video+audio combined)
        // These streams have videoOnly = false or null
        val muxedStream = streams.videoStreams
            .filter { 
                it.url != null && 
                it.url!!.isNotBlank() && 
                it.videoOnly != true  // Include false and null (muxed streams)
            }
            .maxByOrNull { it.height ?: it.quality?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 }
        
        muxedStream?.let {
            val url = it.url!!
            val unwrapped = ProxyHelper.unwrapUrl(url)
            Log.d("CastMediaItemBuilder", "Using muxed stream: quality=${it.quality}, videoOnly=${it.videoOnly}, unwrapped=$unwrapped")
            return unwrapped.toUri()
        }
        
        // Last resort: YouTube URL (will work but requires internet)
        Log.w("CastMediaItemBuilder", "No HLS/DASH/muxed stream found, using YouTube URL for $videoId")
        return "https://www.youtube.com/watch?v=$videoId".toUri()
    }
    
    /**
     * Get MIME type for best stream
     */
    private fun getBestStreamMimeType(streams: Streams): String? {
        return when {
            streams.hls?.isNotBlank() == true -> MimeTypes.APPLICATION_M3U8
            streams.dash?.isNotBlank() == true -> MimeTypes.APPLICATION_MPD
            streams.videoStreams.isNotEmpty() -> {
                val muxedStreams = streams.videoStreams
                    .filter {
                        it.url != null &&
                                it.url!!.isNotBlank() &&
                                it.videoOnly != true
                    }
                val muxedStream = muxedStreams
                    .maxByOrNull { it.height ?: it.quality?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 }
                    ?: return MimeTypes.VIDEO_MP4

                muxedStream.mimeType
            }
            else -> MimeTypes.VIDEO_MP4
        }
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
        val thumbnailUrl = streams.thumbnailUrl
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
                    .setMimeType(getMimeType(subtitle.mimeType.orEmpty()))
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
