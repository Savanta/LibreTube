package com.github.libretube.helpers

import android.content.Context
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession

/**
 * Helper for managing Google Cast functionality
 * Handles Cast context initialization, player creation, and session management
 */
object CastHelper {
    private const val TAG = "CastHelper"
    
    private var castContext: CastContext? = null
    private var castPlayer: CastPlayer? = null
    
    /**
     * Initialize Cast context
     * Safe to call multiple times - will only initialize once
     */
    fun initialize(context: Context) {
        if (castContext != null) return
        
        try {
            castContext = CastContext.getSharedInstance(context.applicationContext)
            Log.d(TAG, "Cast context initialized successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Cast Framework not available: ${e.message}")
            // This is expected on devices without Google Play Services
        }
    }
    
    /**
     * Get or create CastPlayer instance
     * Returns null if Cast is not available
     */
    fun getCastPlayer(context: Context): CastPlayer? {
        initialize(context)
        
        if (castPlayer == null && castContext != null) {
            try {
                castPlayer = CastPlayer(castContext!!)
                Log.d(TAG, "CastPlayer created")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create CastPlayer", e)
            }
        }
        
        return castPlayer
    }
    
    /**
     * Check if there's an active Cast session
     */
    fun isCastSessionAvailable(): Boolean {
        return getCurrentCastSession() != null
    }
    
    /**
     * Get current active Cast session
     */
    fun getCurrentCastSession(): CastSession? {
        return castContext?.sessionManager?.currentCastSession
    }
    
    /**
     * Get Cast context
     * Will attempt to initialize if not already done
     */
    fun getCastContext(context: Context): CastContext? {
        initialize(context)
        return castContext
    }
    
    /**
     * Get device name of currently connected Cast device
     */
    fun getConnectedDeviceName(): String? {
        return getCurrentCastSession()?.castDevice?.friendlyName
    }
    
    /**
     * Check if Cast is supported on this device
     */
    fun isCastAvailable(): Boolean {
        return castContext != null
    }
    
    /**
     * Release Cast player resources
     * Call this when the player is no longer needed
     */
    fun release() {
        castPlayer?.release()
        castPlayer = null
        Log.d(TAG, "CastPlayer released")
    }
    
    /**
     * Set listener for Cast session availability changes
     */
    fun setSessionAvailabilityListener(
        player: CastPlayer?,
        listener: SessionAvailabilityListener?
    ) {
        player?.setSessionAvailabilityListener(listener)
    }
}
