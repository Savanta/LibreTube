package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.os.postDelayed
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.marginStart
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.findFragment
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TimeBar
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.CustomExoPlayerViewTemplateBinding
import com.github.libretube.databinding.DoubleTapOverlayBinding
import com.github.libretube.databinding.ExoStyledPlayerControlViewBinding
import com.github.libretube.databinding.PlayerGestureControlsViewBinding
import com.github.libretube.extensions.dpToPx
import com.github.libretube.extensions.navigateVideo
import com.github.libretube.extensions.normalize
import com.github.libretube.extensions.round
import com.github.libretube.extensions.seekBy
import com.github.libretube.extensions.togglePlayPauseState
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.AudioHelper
import com.github.libretube.helpers.BrightnessHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.sender.NowPlayingStatus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.WindowHelper
import com.github.libretube.obj.BottomSheetItem
import com.github.libretube.sender.LoungeSender
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.controllers.FullscreenGestureAnimationController
import com.github.libretube.ui.extensions.toggleSystemBars
import com.github.libretube.ui.fragments.PlayerFragment
import com.github.libretube.ui.interfaces.PlayerGestureOptions
import com.github.libretube.ui.interfaces.PlayerOptions
import com.github.libretube.ui.listeners.PlayerGestureController
import com.github.libretube.ui.models.ChaptersViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.sheets.ChaptersBottomSheet
import com.github.libretube.ui.sheets.PlaybackOptionsSheet
import com.github.libretube.ui.sheets.PlayingQueueSheet
import com.github.libretube.ui.sheets.SleepTimerSheet
import com.github.libretube.ui.tools.SleepTimer
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.abs

@SuppressLint("ClickableViewAccessibility")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
abstract class CustomExoPlayerView(
    context: Context,
    attributeSet: AttributeSet? = null
) : PlayerView(context, attributeSet), PlayerOptions, PlayerGestureOptions {
    @Suppress("LeakingThis")
    val binding = ExoStyledPlayerControlViewBinding.bind(this)
    val backgroundBinding = CustomExoPlayerViewTemplateBinding.bind(this)

    /**
     * Objects for player tap and swipe gesture
     */
    private val gestureViewBinding: PlayerGestureControlsViewBinding get() = backgroundBinding.playerGestureControlsView.binding
    private val doubleTapOverlayBinding: DoubleTapOverlayBinding get() = backgroundBinding.doubleTapOverlay.binding

    private var playerGestureController: PlayerGestureController
    private var brightnessHelper: BrightnessHelper
    private var audioHelper: AudioHelper
    private lateinit var chaptersViewModel: ChaptersViewModel
    private lateinit var seekBarListener: TimeBar.OnScrubListener
    private var fullscreenGestureAnimationController: FullscreenGestureAnimationController
    private var chaptersBottomSheet: ChaptersBottomSheet? = null
    private var scrubbingTimeBar = false
    private val loungeSender by lazy { LoungeSender(context) }
    private var isLoungeCasting = false
    private var loungeIsPlaying = false
    private var loungePositionMs: Long = 0L
    private var loungeLastRealtimeMs: Long = 0L
    private var loungeSeekGuardJob: Job? = null
    private var loungeDeviceReachable: Boolean = false
    private var loungeReachabilityLastCheck: Long = 0L
    private var loungeReachabilityLastSuccess: Long = 0L
    private var lastLoungeVideoId: String? = null
    private var lastLoungeIndex: Int = -1
    private var lastAutoCastVideoId: String? = null
    private var isApplyingRemoteLoungeState: Boolean = false
    private var loungeLastSyncSignature: String? = null
    private var loungeLastSyncMs: Long = 0L
    private var loungeRemoteDriveUntilMs: Long = 0L
    private var loungeLastTransitionSignature: String? = null
    private var loungeLastTransitionMs: Long = 0L
    private val loungeSessionController by lazy { LoungeSessionController(loungeSender) }

    /**
     * Objects from the parent fragment
     */

    private val runnableHandler = Handler(Looper.getMainLooper())
    var isPlayerLocked: Boolean = false
    var isLive: Boolean = false
        set(value) {
            field = value
            updateDisplayedDurationType()
            updateCurrentPosition()
        }

    private var resizeModePref: Int
        set(value) {
            PreferenceHelper.putInt(
                PreferenceKeys.PLAYER_RESIZE_MODE,
                value
            )
        }
        get() = PreferenceHelper.getInt(
            PreferenceKeys.PLAYER_RESIZE_MODE,
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        )
    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to R.string.resize_mode_fit,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to R.string.resize_mode_zoom,
        AspectRatioFrameLayout.RESIZE_MODE_FILL to R.string.resize_mode_fill
    )

    val activity get() = context as BaseActivity

    private val supportFragmentManager
        get() = activity.supportFragmentManager

    /**
     * Playback speed that has been set before the fast forward mode
     * has been triggered by a long press.
     */
    private var rememberedPlaybackSpeed: Float? = null

    private fun toggleController(show: Boolean = !isControllerFullyVisible) {
        if (show) showController() else hideController()
    }

    init {
        brightnessHelper = BrightnessHelper(activity)
        playerGestureController = PlayerGestureController(activity, this)
        audioHelper = AudioHelper(context)
        fullscreenGestureAnimationController = FullscreenGestureAnimationController(
            playerView = this,
            videoFrameView = backgroundBinding.exoContentFrame,
            onSwipeUpCompleted = {
                if (!isFullscreen()) togglePlayerFullscreen(true)
            },
            onSwipeDownCompleted = ::minimizeOrExitPlayer
        )
    }

    fun initialize(chaptersViewModel: ChaptersViewModel) {
        this.chaptersViewModel = chaptersViewModel

        initializeGestureProgress()

        initRewindAndForward()
        applyCaptionsStyle()
        initializeAdvancedOptions()
        initializeCastButton()

        // don't let the player view hide its controls automatically
        controllerShowTimeoutMs = -1
        // don't let the player view show its controls automatically
        controllerAutoShow = false

        // locking the player
        binding.lockPlayer.setOnClickListener {
            // change the locked/unlocked icon
            val icon = if (!isPlayerLocked) R.drawable.ic_locked else R.drawable.ic_unlocked
            val tooltip = if (!isPlayerLocked) {
                R.string.tooltip_unlocked
            } else {
                R.string.tooltip_locked
            }

            binding.lockPlayer.setImageResource(icon)
            TooltipCompat.setTooltipText(binding.lockPlayer, context.getString(tooltip))

            // show/hide all the controls
            lockPlayer(isPlayerLocked)

            // change locked status
            isPlayerLocked = !isPlayerLocked

            if (isFullscreen()) toggleSystemBars(!isPlayerLocked)
        }

        resizeMode = resizeModePref

        binding.playPauseBTN.setOnClickListener {
            if (isLoungeCasting) {
                toggleLoungePlayback()
            } else {
                player?.togglePlayPauseState()
            }
        }

        player?.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                super.onEvents(player, events)
                this@CustomExoPlayerView.onPlaybackEvents(player, events)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                keepScreenOn = isPlaying
            }
        })

        player?.let { player ->
            binding.playPauseBTN.setImageResource(
                PlayerHelper.getPlayPauseActionIcon(player)
            )
        }

        player?.let {
            binding.exoProgress.setPlayer(it)
            if (it.isPlaying) keepScreenOn = true
        }

        // prevent the controls from disappearing while scrubbing the time bar
        if (!::seekBarListener.isInitialized) {
            seekBarListener = object : TimeBar.OnScrubListener {
                override fun onScrubStart(timeBar: TimeBar, position: Long) {
                    cancelHideControllerTask()
                }

                override fun onScrubMove(timeBar: TimeBar, position: Long) {
                    cancelHideControllerTask()

                    setCurrentChapterName(forceUpdate = true, enqueueNew = false)
                    scrubbingTimeBar = true
                }

                override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                    enqueueHideControllerTask()

                    setCurrentChapterName(forceUpdate = true, enqueueNew = false)
                    scrubbingTimeBar = false

                    if (isLoungeCasting && !canceled) {
                        player?.seekTo(position)
                        sendLoungeSeek(position)
                    }
                }
            }
            binding.exoProgress.addSeekBarListener(seekBarListener)
        }

        binding.autoPlay.isChecked = PlayerHelper.autoPlayEnabled

        binding.autoPlay.setOnCheckedChangeListener { _, isChecked ->
            PlayerHelper.autoPlayEnabled = isChecked
        }

        // restore the duration type from the previous session
        updateDisplayedDurationType()

        binding.duration.setOnClickListener {
            updateDisplayedDurationType(true)
        }
        binding.timeLeft.setOnClickListener {
            updateDisplayedDurationType(false)
        }
        binding.position.setOnClickListener {
            if (isLive) player?.let { it.seekTo(it.duration) }
        }

        // forward touch events to the time bar for better accessibility
        binding.progressBar.setOnTouchListener { _, motionEvent ->
            binding.exoProgress.onTouchEvent(motionEvent)
        }

        updateCurrentPosition()

        activity.supportFragmentManager.setFragmentResultListener(
            ChaptersBottomSheet.SEEK_TO_POSITION_REQUEST_KEY,
            findViewTreeLifecycleOwner() ?: activity
        ) { _, bundle ->
            player?.seekTo(bundle.getLong(IntentData.currentPosition))
        }

        // enable the chapters dialog in the player
        binding.chapterName.setOnClickListener {
            val sheet = chaptersBottomSheet ?: ChaptersBottomSheet()
                .apply {
                    arguments = bundleOf(
                        IntentData.duration to player?.duration?.div(1000)
                    )
                }
                .also {
                    chaptersBottomSheet = it
                }

            if (sheet.isVisible) {
                sheet.dismiss()
            } else {
                sheet.show(activity.supportFragmentManager)
            }
        }

        supportFragmentManager.setFragmentResultListener(
            PlayingQueueSheet.PLAYING_QUEUE_REQUEST_KEY,
            findViewTreeLifecycleOwner() ?: activity
        ) { _, args ->
            (player as? MediaController)?.navigateVideo(
                args.getString(IntentData.videoId) ?: return@setFragmentResultListener
            )
        }
        binding.queueToggle.setOnClickListener {
            PlayingQueueSheet().show(supportFragmentManager, null)
        }

        updateMarginsByFullscreenMode()
    }

    /**
     * Set the name of the video chapter in the [CustomExoPlayerView]
     * @param forceUpdate Update the current chapter name no matter if the seek bar is scrubbed
     * @param enqueueNew set a timeout to automatically repeat this function again in 100ms
     */
    fun setCurrentChapterName(forceUpdate: Boolean = false, enqueueNew: Boolean = true) {
        val player = player ?: return
        val chapters = chaptersViewModel.chapters

        binding.chapterName.isInvisible = chapters.isEmpty()

        // the following logic to set the chapter title can be skipped if no chapters are available
        if (chapters.isEmpty()) return

        // call the function again in 100ms
        if (enqueueNew) postDelayed(this::setCurrentChapterName, 100)

        // if the user is scrubbing the time bar, don't update
        if (scrubbingTimeBar && !forceUpdate) return

        val currentIndex = PlayerHelper.getCurrentChapterIndex(player.currentPosition, chapters)
        val newChapterName = currentIndex?.let { chapters[it].title.trim() }.orEmpty()

        chaptersViewModel.currentChapterIndex.updateIfChanged(currentIndex ?: -1)

        // change the chapter name textView text to the chapterName
        if (newChapterName != binding.chapterName.text) {
            binding.chapterName.text = newChapterName
        }
    }

    fun toggleSystemBars(showBars: Boolean) {
        getWindow().toggleSystemBars(
            types = if (showBars) {
                WindowHelper.getGestureControlledBars(context)
            } else {
                WindowInsetsCompat.Type.systemBars()
            },
            showBars = showBars
        )
    }

    open fun onPlayerEvent(player: Player, playerEvents: Player.Events) = Unit

    private fun updateDisplayedDurationType(showTimeLeft: Boolean? = null) {
        var shouldShowTimeLeft = showTimeLeft ?: PreferenceHelper
            .getBoolean(PreferenceKeys.SHOW_TIME_LEFT, false)
        // always show the time left only if it's a livestream
        if (isLive) shouldShowTimeLeft = true
        if (showTimeLeft != null) {
            // save whether to show time left or duration for next session
            PreferenceHelper.putBoolean(PreferenceKeys.SHOW_TIME_LEFT, shouldShowTimeLeft)
        }
        binding.timeLeft.isVisible = shouldShowTimeLeft
        binding.duration.isGone = shouldShowTimeLeft
    }

    private fun enqueueHideControllerTask() {
        if (isLoungeCasting) return
        runnableHandler.postDelayed(AUTO_HIDE_CONTROLLER_DELAY, HIDE_CONTROLLER_TOKEN) {
            hideController()
        }
    }

    private fun cancelHideControllerTask() {
        runnableHandler.removeCallbacksAndMessages(HIDE_CONTROLLER_TOKEN)
    }

    override fun hideController() {
        if (isLoungeCasting) return
        // remove the callback to hide the controller
        cancelHideControllerTask()
        super.hideController()
        backgroundBinding.exoControlsBackground.animate()
            .alpha(0f)
            .setDuration(500)
            .start()
    }

    override fun showController() {
        // remove the previous callback from the queue to prevent a flashing behavior
        cancelHideControllerTask()
        if (!isLoungeCasting) {
            // automatically hide the controller after 2 seconds
            enqueueHideControllerTask()
        }
        super.showController()
        backgroundBinding.exoControlsBackground.animate()
            .alpha(1f)
            .setDuration(200)
            .start()
    }

    fun showControllerPermanently() {
        // remove the previous callback from the queue to prevent a flashing behavior
        cancelHideControllerTask()
        super.showController()
    }

    private fun initRewindAndForward() {
        val seekIncrementText = (PlayerHelper.seekIncrement / 1000).toString()
        listOf(
            doubleTapOverlayBinding.rewindLayout.rewindTV,
            doubleTapOverlayBinding.forwardLayout.forwardTV,
            binding.seekButtonForward.forwardTV,
            binding.seekButtonRewind.rewindTV
        ).forEach {
            it.text = seekIncrementText
        }
        binding.seekButtonForward.forwardBTN.setOnClickListener {
            player?.seekBy(PlayerHelper.seekIncrement)
        }
        binding.seekButtonRewind.rewindBTN.setOnClickListener {
            player?.seekBy(-PlayerHelper.seekIncrement)
        }

        if (!PlayerHelper.doubleTapToSeek) {
            binding.seekButtonForward.forwardBTN.isVisible = !isPlayerLocked
            binding.seekButtonRewind.rewindBTN.isVisible = !isPlayerLocked
        }
    }

    private fun initializeAdvancedOptions() {
        binding.toggleOptions.setOnClickListener {
            val items = getOptionsMenuItems()
            val bottomSheetFragment = BaseBottomSheet().setItems(items, null)
            bottomSheetFragment.show(supportFragmentManager, null)
        }
    }

    open fun getOptionsMenuItems(): List<BottomSheetItem> = listOf(
        BottomSheetItem(
            context.getString(R.string.repeat_mode),
            R.drawable.ic_repeat,
            {
                when (PlayingQueue.repeatMode) {
                    Player.REPEAT_MODE_OFF -> context.getString(R.string.repeat_mode_none)
                    Player.REPEAT_MODE_ONE -> context.getString(R.string.repeat_mode_current)
                    Player.REPEAT_MODE_ALL -> context.getString(R.string.repeat_mode_all)
                    else -> throw IllegalArgumentException()
                }
            }
        ) {
            onRepeatModeClicked()
        },
        BottomSheetItem(
            context.getString(R.string.player_resize_mode),
            R.drawable.ic_aspect_ratio,
            {
                resizeModes.find { it.first == resizeMode }?.second?.let {
                    context.getString(it)
                }
            }
        ) {
            onResizeModeClicked()
        },
        BottomSheetItem(
            context.getString(R.string.playback_speed),
            R.drawable.ic_speed,
            {
                "${player?.playbackParameters?.speed?.round(2)}x"
            }
        ) {
            onPlaybackSpeedClicked()
        },
        BottomSheetItem(
            context.getString(R.string.sleep_timer),
            R.drawable.ic_sleep,
            {
                if (SleepTimer.timeLeftMillis > 0) {
                    val minutesLeft =
                        ceil(SleepTimer.timeLeftMillis.toDouble() / DateUtils.MINUTE_IN_MILLIS).toInt()
                    context.resources.getQuantityString(
                        R.plurals.minutes_left,
                        minutesLeft,
                        minutesLeft
                    )
                } else {
                    context.getString(R.string.disabled)
                }
            }
        ) {
            onSleepTimerClicked()
        }
    )

    // lock the player
    private fun lockPlayer(isLocked: Boolean) {
        // isLocked is the current (old) state of the player lock
        binding.exoTopBarRight.isVisible = isLocked
        binding.exoCenterControls.isVisible = isLocked
        binding.bottomBar.isVisible = isLocked
        binding.closeImageButton.isVisible = isLocked
        binding.exoTitle.isVisible = isLocked
        binding.playPauseBTN.isVisible = isLocked

        if (!PlayerHelper.doubleTapToSeek) {
            binding.seekButtonRewind.rewindBTN.isVisible = isLocked
            binding.seekButtonForward.forwardBTN.isVisible = isLocked
        }

        // hide the dimming background overlay if locked
        backgroundBinding.exoControlsBackground.setBackgroundColor(
            if (isLocked) {
                ContextCompat.getColor(
                    context,
                    androidx.media3.ui.R.color.exo_black_opacity_60
                )
            } else {
                Color.TRANSPARENT
            }
        )

        // disable tap and swipe gesture if the player is locked
        playerGestureController.areControlsLocked = !isLocked
    }

    private fun rewind() {
        if (isLoungeCasting) {
            sendLoungeSeekDelta(-PlayerHelper.seekIncrement)
            return
        }
        player?.seekBy(-PlayerHelper.seekIncrement)

        // show the rewind button
        doubleTapOverlayBinding.apply {
            animateSeeking(
                rewindLayout.rewindBTN,
                rewindLayout.rewindIV,
                rewindLayout.rewindTV,
                true
            )

            // start callback to hide the button
            runnableHandler.removeCallbacksAndMessages(HIDE_REWIND_BUTTON_TOKEN)
            runnableHandler.postDelayed(700, HIDE_REWIND_BUTTON_TOKEN) {
                rewindLayout.rewindBTN.isGone = true
            }
        }
    }

    private fun forward() {
        if (isLoungeCasting) {
            sendLoungeSeekDelta(PlayerHelper.seekIncrement)
            return
        }
        player?.seekBy(PlayerHelper.seekIncrement)

        // show the forward button
        doubleTapOverlayBinding.apply {
            animateSeeking(
                forwardLayout.forwardBTN,
                forwardLayout.forwardIV,
                forwardLayout.forwardTV,
                false
            )

            // start callback to hide the button
            runnableHandler.removeCallbacksAndMessages(HIDE_FORWARD_BUTTON_TOKEN)
            runnableHandler.postDelayed(700, HIDE_FORWARD_BUTTON_TOKEN) {
                forwardLayout.forwardBTN.isGone = true
            }
        }
    }

    private fun animateSeeking(
        container: FrameLayout,
        imageView: ImageView,
        textView: TextView,
        isRewind: Boolean
    ) {
        container.isVisible = true
        // the direction of the action
        val direction = if (isRewind) -1 else 1

        // clear previous animation
        imageView.animate()
            .rotation(0F)
            .setDuration(0)
            .start()

        textView.animate()
            .translationX(0f)
            .setDuration(0)
            .start()

        // start the rotate animation of the drawable
        imageView.animate()
            .rotation(direction * 30F)
            .setDuration(ANIMATION_DURATION)
            .withEndAction {
                // reset the animation when finished
                imageView.animate()
                    .rotation(0F)
                    .setDuration(ANIMATION_DURATION)
                    .start()
            }
            .start()

        // animate the text view to move outside the image view
        textView.animate()
            .translationX(direction * 100f)
            .setDuration((ANIMATION_DURATION * 1.5).toLong())
            .withEndAction {
                // move the text back into the button
                runnableHandler.postDelayed(100) {
                    textView.animate()
                        .setDuration(ANIMATION_DURATION / 2)
                        .translationX(0f)
                        .start()
                }
            }
    }

    private fun initializeGestureProgress() {
        gestureViewBinding.brightnessProgressBar.let { bar ->
            bar.progress =
                brightnessHelper.getBrightnessWithScale(bar.max.toFloat(), saved = true).toInt()
        }
        gestureViewBinding.volumeProgressBar.let { bar ->
            bar.progress = audioHelper.getVolumeWithScale(bar.max)
        }
    }

    private fun updateBrightness(distance: Float) {
        gestureViewBinding.brightnessControlView.isVisible = true
        val bar = gestureViewBinding.brightnessProgressBar

        if (bar.progress == 0) {
            // If brightness progress goes to below 0, set to system brightness
            if (distance <= 0) {
                brightnessHelper.resetToSystemBrightness()
                gestureViewBinding.brightnessImageView.setImageResource(
                    R.drawable.ic_brightness_auto
                )
                gestureViewBinding.brightnessTextView.text = resources.getString(R.string.auto)
                return
            }
            gestureViewBinding.brightnessImageView.setImageResource(R.drawable.ic_brightness)
        }

        bar.incrementProgressBy(distance.toInt())
        gestureViewBinding.brightnessTextView.text = "${bar.progress.normalize(0, bar.max, 0, 100)}"
        brightnessHelper.setBrightnessWithScale(bar.progress.toFloat(), bar.max.toFloat())
    }

    private fun updateVolume(distance: Float) {
        val bar = gestureViewBinding.volumeProgressBar
        gestureViewBinding.volumeControlView.apply {
            if (isGone) {
                isVisible = true
                // Volume could be changed using other mediums, sync progress
                // bar with new value.
                bar.progress = audioHelper.getVolumeWithScale(bar.max)
            }
        }

        if (bar.progress == 0) {
            gestureViewBinding.volumeImageView.setImageResource(
                when {
                    distance > 0 -> R.drawable.ic_volume_up
                    else -> R.drawable.ic_volume_off
                }
            )
        }
        bar.incrementProgressBy(distance.toInt())
        audioHelper.setVolumeWithScale(bar.progress, bar.max)

        gestureViewBinding.volumeTextView.text = "${bar.progress.normalize(0, bar.max, 0, 100)}"
    }

    override fun onPlaybackSpeedClicked() {
        (player as? MediaController)?.let {
            PlaybackOptionsSheet(it).show(supportFragmentManager)
        }
    }

    override fun onResizeModeClicked() {
        // switching between original aspect ratio (black bars) and zoomed to fill device screen
        BaseBottomSheet()
            .setSimpleItems(
                resizeModes.map { context.getString(it.second) },
                preselectedItem = resizeModes.first { it.first == resizeMode }.second.let {
                    context.getString(it)
                }
            ) { index ->
                resizeMode = resizeModes[index].first
            }
            .show(supportFragmentManager)
    }

    override fun setResizeMode(resizeMode: Int) {
        super.setResizeMode(resizeMode)
        // automatically remember the resize mode for the next session
        resizeModePref = resizeMode
    }

    override fun onRepeatModeClicked() {
        // repeat mode options dialog
        BaseBottomSheet()
            .setSimpleItems(
                PlayerHelper.repeatModes.map { context.getString(it.second) },
                preselectedItem = PlayerHelper.repeatModes
                    .firstOrNull { it.first == PlayingQueue.repeatMode }
                    ?.second?.let {
                        context.getString(it)
                    }
            ) { index ->
                PlayingQueue.repeatMode = PlayerHelper.repeatModes[index].first
            }
            .show(supportFragmentManager)
    }

    override fun onSleepTimerClicked() {
        SleepTimerSheet().show(supportFragmentManager)
    }

    open fun isFullscreen() =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)

        updateMarginsByFullscreenMode()
    }

    /**
     * Updates the margins according to the current orientation and fullscreen mode
     */
    fun updateMarginsByFullscreenMode() {
        // add a larger bottom margin to the time bar in landscape mode
        binding.progressBar.updateLayoutParams<MarginLayoutParams> {
            bottomMargin = (if (isFullscreen()) 20f else 10f).dpToPx()
        }

        updateTopBarMargin()

        // don't add extra padding if there's no cutout and no margin set that would need to be undone
        if (!activity.hasCutout && binding.topBar.marginStart == LANDSCAPE_MARGIN_HORIZONTAL_NONE) return

        // add a margin to the top and the bottom bar in landscape mode for notches
        val isForcedLandscape =
            activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val isInLandscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val horizontalMargin =
            if (isFullscreen() && (isInLandscape || isForcedLandscape)) LANDSCAPE_MARGIN_HORIZONTAL else LANDSCAPE_MARGIN_HORIZONTAL_NONE

        listOf(binding.topBar, binding.bottomBar).forEach {
            it.updateLayoutParams<MarginLayoutParams> {
                marginStart = horizontalMargin
                marginEnd = horizontalMargin
            }
        }

        binding.fullscreen.layoutParams =
            (binding.fullscreen.layoutParams as MarginLayoutParams).apply {
                if (isFullscreen()) {
                    // Add extra bottom margin in fullscreen mode
                    bottomMargin =
                        resources.getDimensionPixelSize(R.dimen.fullscreen_button_margin_bottom)
                    marginEnd =
                        resources.getDimensionPixelSize(R.dimen.fullscreen_button_margin_end)
                } else {
                    // Reset to default margin
                    bottomMargin =
                        resources.getDimensionPixelSize(R.dimen.normal_button_margin_bottom)
                    marginEnd = resources.getDimensionPixelSize(R.dimen.normal_button_margin_end)
                }
            }
    }

    /**
     * Load the captions style according to the users preferences
     */
    private fun applyCaptionsStyle() {
        val captionStyle = PlayerHelper.getCaptionStyle(context)
        subtitleView?.apply {
            setApplyEmbeddedFontSizes(false)
            setFixedTextSize(Cue.TEXT_SIZE_TYPE_ABSOLUTE, PlayerHelper.captionsTextSize)
            if (PlayerHelper.useRichCaptionRendering) setViewType(SubtitleView.VIEW_TYPE_WEB)
            if (!PlayerHelper.useSystemCaptionStyle) return
            setApplyEmbeddedStyles(captionStyle == CaptionStyleCompat.DEFAULT)
            setStyle(captionStyle)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateCurrentPosition() {
        val positionMs = currentLoungePositionMs()
        val position = positionMs / 1000
        val durationMs = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0
        val timeLeft = (durationMs - positionMs).coerceAtLeast(0L) / 1000

        binding.position.text =
            if (isLive) context.getString(R.string.live) else DateUtils.formatElapsedTime(position)
        binding.timeLeft.text = "-${DateUtils.formatElapsedTime(timeLeft)}"

        if (isLoungeCasting) {
            binding.exoProgress.setDuration(durationMs)
            binding.exoProgress.setPosition(positionMs)
            binding.exoProgress.setBufferedPosition(positionMs)
            val localPos = player?.currentPosition
            if (localPos != null && kotlin.math.abs(localPos - positionMs) > 300) {
                // Keep the local player position aligned to the cast clock so controller updates don't fight.
                player?.seekTo(positionMs)
            }
        }

        runnableHandler.postDelayed(100, UPDATE_POSITION_TOKEN, this::updateCurrentPosition)
    }

    private fun currentLoungePositionMs(): Long {
        if (!isLoungeCasting) return player?.currentPosition ?: 0L
        val base = loungePositionMs
        val elapsed = if (loungeIsPlaying) SystemClock.elapsedRealtime() - loungeLastRealtimeMs else 0L
        return (base + elapsed).coerceAtLeast(0L)
    }

    private fun startLoungeHeartbeat(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        loungeSessionController.startHeartbeat(
            scope = lifecycleOwner.lifecycleScope,
            isPaused = { !loungeIsPlaying },
            lastRealtimeMs = { loungeLastRealtimeMs },
            onNowPlaying = { status -> updateLoungeNowPlaying(status) },
            onPingResult = { reachable, status ->
                loungeDeviceReachable = reachable
                loungeReachabilityLastCheck = SystemClock.elapsedRealtime()
                if (reachable) {
                    loungeReachabilityLastSuccess = loungeReachabilityLastCheck
                    status?.let { updateLoungeNowPlaying(it) }
                }
                updateCastIcon()
                (context as? MainActivity)?.invalidateMenu()
            },
            onConnectionLost = { onLoungeConnectionLost() }
        )
    }

    private fun stopLoungeHeartbeat() {
        loungeSeekGuardJob?.cancel()
        loungeSeekGuardJob = null
        loungeSessionController.stopHeartbeat()
        loungeSessionController.stopReachability()
    }

    private fun onLoungeConnectionLost() {
        stopLoungeHeartbeat()
        resetLoungeSession(clearActiveDevice = true)
    }

    private fun detachProgressFromPlayer() {
        binding.exoProgress.setPlayer(null)
        // Align the local player's position to the lounge clock to avoid UI jumps without detaching controls.
        player?.seekTo(loungePositionMs)
    }

    private fun attachProgressToPlayer() {
        player?.let { binding.exoProgress.setPlayer(it) }
    }

    private fun updateLoungeNowPlaying(status: NowPlayingStatus) {
        handleRemoteLoungeQueue(status)

        val position = status.currentTimeMs
        if (position != null) {
            loungePositionMs = position
            loungeLastRealtimeMs = SystemClock.elapsedRealtime()
        }
        status.isPlaying?.let { isPlaying ->
            loungeIsPlaying = isPlaying
            val icon = if (loungeIsPlaying) R.drawable.ic_pause else R.drawable.ic_play
            binding.playPauseBTN.setImageResource(icon)
        }
        keepScreenOn = loungeIsPlaying
    }

    private fun handleRemoteLoungeQueue(status: NowPlayingStatus) {
        val remoteVideoId = status.videoId ?: return
        if (isApplyingRemoteLoungeState) return

        val localCurrentId = PlayingQueue.getCurrent()?.url?.toID()
        val remoteQueueIds = status.videoIds
        val remoteIndex = status.currentIndex
        val now = SystemClock.elapsedRealtime()

        isApplyingRemoteLoungeState = true
        try {
            remoteQueueIds?.let { ids ->
                val streamsById = PlayingQueue.getStreams().associateBy { it.url?.toID() }
                val reordered = ids.mapNotNull { id -> streamsById[id] }
                if (reordered.isNotEmpty()) {
                    PlayingQueue.setStreams(reordered)
                    val newCurrent = remoteIndex?.let { reordered.getOrNull(it) }
                        ?: reordered.firstOrNull { it.url?.toID() == remoteVideoId }
                        ?: reordered.firstOrNull()
                    newCurrent?.let { PlayingQueue.updateCurrent(it) }
                }
            }

            if (remoteVideoId != localCurrentId) {
                (player as? MediaController)?.navigateVideo(remoteVideoId)
            }

            val queueIds = PlayingQueue.getStreams().mapNotNull { it.url?.toID() }
            val indexForSignature = remoteIndex
                ?: PlayingQueue.currentIndex().let { idx -> if (idx in queueIds.indices) idx else 0 }

            lastLoungeVideoId = remoteVideoId
            lastLoungeIndex = indexForSignature
            loungeLastSyncSignature = buildLoungeQueueSignature(
                remoteVideoId,
                indexForSignature,
                queueIds.ifEmpty { listOf(remoteVideoId) },
                listId = null,
                params = null,
                playerParams = null
            )
            loungeLastSyncMs = now
            loungeRemoteDriveUntilMs = now + 3_500
        } finally {
            isApplyingRemoteLoungeState = false
        }
    }

    private fun updateLoungeArtwork(currentItem: com.github.libretube.api.obj.StreamItem?) {
        val artwork = backgroundBinding.exoArtwork
        if (isLoungeCasting && currentItem?.thumbnail != null) {
            artwork.isVisible = true
            ImageHelper.loadImage(currentItem.thumbnail, artwork)
        } else {
            artwork.setImageDrawable(null)
            artwork.isVisible = false
        }
    }

    /**
     * Add extra margin to the top bar to not overlap the status bar.
     */
    fun updateTopBarMargin() {
        binding.topBar.updateLayoutParams<MarginLayoutParams> {
            topMargin = (if (isFullscreen()) 18f else 0f).dpToPx()
        }
    }

    override fun onSingleTap(areControlsLocked: Boolean) {
        if (areControlsLocked) {
            // keep showing the 'locked' icon
            toggleController(true)
            return
        }
        toggleController()
    }

    override fun onDoubleTapCenterScreen() {
        player?.togglePlayPauseState()
    }

    override fun onDoubleTapLeftScreen() {
        if (!PlayerHelper.doubleTapToSeek) return
        rewind()
    }

    override fun onDoubleTapRightScreen() {
        if (!PlayerHelper.doubleTapToSeek) return
        forward()
    }

    override fun onSwipeLeftScreen(distanceY: Float, positionY: Float) {
        if (!PlayerHelper.swipeGestureEnabled) {
            if (PlayerHelper.fullscreenGesturesEnabled) onSwipeCenterScreen(distanceY, positionY)
            return
        }

        if (isControllerFullyVisible) hideController()
        updateBrightness(distanceY)
    }

    override fun onSwipeRightScreen(distanceY: Float, positionY: Float) {
        if (!PlayerHelper.swipeGestureEnabled) {
            if (PlayerHelper.fullscreenGesturesEnabled) onSwipeCenterScreen(distanceY, positionY)
            return
        }

        if (isControllerFullyVisible) hideController()
        updateVolume(distanceY)
    }

    override fun onSwipeCenterScreen(distanceY: Float, positionY: Float) {
        if (!PlayerHelper.fullscreenGesturesEnabled) return
        fullscreenGestureAnimationController.onSwipe(distanceY, positionY)
    }

    override fun onSwipeEnd() {
        fullscreenGestureAnimationController.onSwipeEnd()
        gestureViewBinding.brightnessControlView.isGone = true
        gestureViewBinding.volumeControlView.isGone = true
    }

    override fun onZoom() {
        if (!PlayerHelper.pinchGestureEnabled) return
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            subtitleView?.setBottomPaddingFraction(SUBTITLE_BOTTOM_PADDING_FRACTION)
        }
    }

    override fun onMinimize() {
        if (!PlayerHelper.pinchGestureEnabled) return
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

        subtitleView?.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
    }

    override fun onLongPress() {
        if (!PlayerHelper.doubleTapToSeek) return

        backgroundBinding.fastForwardView.isVisible = true
        val player = player ?: return

        // using the fast forward action wouldn't change anything in this case
        if (player.playbackParameters.speed >= PlayerHelper.MAXIMUM_PLAYBACK_SPEED) {
            return
        }

        // backup current playback speed in order to restore it
        // after the fast forward action is done
        rememberedPlaybackSpeed = player.playbackParameters.speed

        val newSpeed = minOf(
            player.playbackParameters.speed * PlayerHelper.FAST_FORWARD_SPEED_FACTOR,
            PlayerHelper.MAXIMUM_PLAYBACK_SPEED
        )
        player.playbackParameters = PlaybackParameters(newSpeed, player.playbackParameters.pitch)
    }

    override fun onLongPressEnd() {
        if (!PlayerHelper.doubleTapToSeek) return

        backgroundBinding.fastForwardView.isGone = true

        val player = player ?: return
        rememberedPlaybackSpeed?.let {
            player.playbackParameters = PlaybackParameters(it, player.playbackParameters.pitch)
        }
        rememberedPlaybackSpeed = null
    }

    override fun onFullscreenChange(isFullscreen: Boolean) {
        if (isFullscreen) {
            if (PlayerHelper.swipeGestureEnabled) {
                brightnessHelper.restoreSavedBrightness()
            }
            subtitleView?.setFixedTextSize(
                Cue.TEXT_SIZE_TYPE_ABSOLUTE,
                PlayerHelper.captionsTextSize * 1.5f
            )
            if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                subtitleView?.setBottomPaddingFraction(SUBTITLE_BOTTOM_PADDING_FRACTION)
            }
        } else {
            if (PlayerHelper.swipeGestureEnabled) {
                brightnessHelper.resetToSystemBrightness()
            }
            subtitleView?.setFixedTextSize(
                Cue.TEXT_SIZE_TYPE_ABSOLUTE,
                PlayerHelper.captionsTextSize
            )
            subtitleView?.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        }

        updateMarginsByFullscreenMode()
    }

    /**
     * Listen for all child touch events
     */
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        // when a control is clicked, restart the countdown to hide the controller
        if (isControllerFullyVisible) {
            cancelHideControllerTask()
            enqueueHideControllerTask()
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        if (!useController) return false

        return playerGestureController.onTouchEvent(event)
    }

    fun onKeyBoardAction(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (isLoungeCasting) {
                    toggleLoungePlayback()
                } else {
                    player?.togglePlayPauseState()
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (isLoungeCasting) {
                    sendLoungeSeekDelta(PlayerHelper.seekIncrement)
                } else {
                    forward()
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (isLoungeCasting) {
                    sendLoungeSeekDelta(-PlayerHelper.seekIncrement)
                } else {
                    rewind()
                }
            }

            KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_NAVIGATE_NEXT -> {
                if (isLoungeCasting) {
                    sendLoungeNext()
                } else {
                    PlayingQueue.getNext()?.let { (player as? MediaController)?.navigateVideo(it) }
                }
            }

            KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_NAVIGATE_PREVIOUS -> {
                if (isLoungeCasting) {
                    sendLoungePrevious()
                } else {
                    PlayingQueue.getPrev()?.let { (player as? MediaController)?.navigateVideo(it) }
                }
            }

            KeyEvent.KEYCODE_F -> {
                togglePlayerFullscreen()
            }

            else -> return false
        }

        return true
    }

    fun togglePlayerFullscreen(isFullscreen: Boolean = !isFullscreen()) {
        try {
            findFragment<PlayerFragment>().toggleFullscreen(isFullscreen)
        } catch (error: IllegalStateException) {
            Log.e(this::class.simpleName, error.message.toString())
        }
    }

    override fun getViewMeasures(): Pair<Int, Int> {
        return width to height
    }

    private fun initializeCastButton() {
        val castEnabled = PreferenceHelper.getBoolean(PreferenceKeys.CAST_ENABLED, true)
        if (!castEnabled) {
            binding.castButton.isGone = true
            return
        }

        binding.castButton.setOnClickListener(null)
        setupLoungeSenderButton()
        updateCastIcon()

        findViewTreeLifecycleOwner()?.let { startLoungeReachabilityPing(it) }
    }

    private fun setupLoungeSenderButton() {
        binding.castButton.isVisible = true
        TooltipCompat.setTooltipText(binding.castButton, context.getString(R.string.cast_sender_pair))
        binding.castButton.setOnClickListener { showLoungeChooser() }
    }

    private fun showLoungeChooser() {
        val lifecycleOwner = findViewTreeLifecycleOwner() ?: return
        val sender = loungeSender
        lifecycleOwner.lifecycleScope.launch {
            startLoungeReachabilityPing(lifecycleOwner)
            val actions = mutableListOf<Pair<String, () -> Unit>>()
            val pairedDevices = sender.pairedDevices()
            val reachability = withContext(Dispatchers.IO) {
                pairedDevices.associateWith { device -> sender.ping(device).isSuccess }
            }

            val activeDevice = sender.currentDevice()

            actions.add(context.getString(R.string.cast_sender_refresh_devices) to {
                showLoungeChooser()
            })

            pairedDevices.forEach { device ->
                val reachable = reachability[device] == true
                val status = context.getString(
                    if (reachable) R.string.cast_sender_status_online else R.string.cast_sender_status_offline
                )
                val label = context.getString(R.string.cast_sender_device_item, device.name) + " \u2022 " + status
                actions.add(label to {
                    loungeReachabilityLastCheck = SystemClock.elapsedRealtime()
                    if (reachable) {
                        loungeDeviceReachable = true
                        loungeReachabilityLastSuccess = loungeReachabilityLastCheck
                        sender.setActiveDevice(device)
                        lastAutoCastVideoId = null
                        sendCurrentToLounge(sender, device, lifecycleOwner)
                    } else {
                        loungeDeviceReachable = false
                        loungeReachabilityLastSuccess = 0L
                        Toast.makeText(context, R.string.cast_sender_device_unreachable, Toast.LENGTH_SHORT).show()
                        updateCastIcon()
                        (context as? MainActivity)?.invalidateMenu()
                    }
                })
            }

            if (pairedDevices.isNotEmpty() && reachability.values.none { it }) {
                loungeDeviceReachable = false
                loungeReachabilityLastCheck = SystemClock.elapsedRealtime()
                loungeReachabilityLastSuccess = 0L
                Toast.makeText(context, R.string.cast_sender_device_unreachable, Toast.LENGTH_SHORT).show()
            }

            if (isLoungeCasting) {
                activeDevice?.let { device ->
                    actions.add(context.getString(R.string.cast_sender_disconnect, device.name) to {
                        disconnectFromLounge()
                    })
                }
            } else if (activeDevice != null) {
                actions.add(context.getString(R.string.cast_sender_disconnect, activeDevice.name) to {
                    loungeSender.clearActiveDevice()
                    loungeDeviceReachable = false
                    lastAutoCastVideoId = null
                    updateCastIcon()
                    (context as? MainActivity)?.invalidateMenu()
                    Toast.makeText(context, R.string.cast_disconnected, Toast.LENGTH_SHORT).show()
                })
            }
            actions.add(context.getString(R.string.cast_sender_pair_via_code) to {
                try {
                    findFragment<PlayerFragment>().parentFragmentManager.let {
                        com.github.libretube.ui.dialogs.CastPairingDialog().show(
                            it,
                            com.github.libretube.ui.dialogs.CastPairingDialog.REQUEST_KEY
                        )
                    }
                } catch (error: Exception) {
                    Log.w(this::class.simpleName, "Unable to open pairing dialog: ${error.message}")
                }
            })

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.cast)
                .setItems(actions.map { it.first }.toTypedArray()) { dialog, which ->
                    actions.getOrNull(which)?.second?.invoke()
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun sendCurrentToLounge(
        sender: LoungeSender,
        pairedDevice: com.github.libretube.sender.LoungeDevice,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        onSuccess: (() -> Unit)? = null
    ) {
        val currentItem = PlayingQueue.getCurrent()
        val videoId = currentItem?.url?.toID()
        if (videoId.isNullOrBlank()) {
            Toast.makeText(context, R.string.cast_pair_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val position = player?.currentPosition ?: 0L
        val queueIds = PlayingQueue.getStreams().mapNotNull { it.url?.toID() }.ifEmpty { listOf(videoId) }
        val currentIndex = PlayingQueue.currentIndex().let { idx ->
            if (idx in queueIds.indices) idx else 0
        }
        val listId = PlayingQueue.currentPlaylistId()
        val params = PlayingQueue.currentParams()
        val playerParams = PlayingQueue.currentPlayerParams()

        lifecycleOwner.lifecycleScope.launch {
            val preStatus = withContext(Dispatchers.IO) { sender.ping(pairedDevice).getOrNull() }
            val remoteVideoId = preStatus?.videoId
            val shouldBacksync = !isLoungeCasting && remoteVideoId != null
            if (shouldBacksync) {
                val status = preStatus
                val videoIdNonNull = remoteVideoId
                Log.d(
                    "LoungeSender",
                    "backsync remote playback videoId=$videoIdNonNull index=${status.currentIndex} pos=${status.currentTimeMs}"
                )
                status.currentTimeMs?.let {
                    loungePositionMs = it
                    loungeLastRealtimeMs = SystemClock.elapsedRealtime()
                }
                status.isPlaying?.let { loungeIsPlaying = it }
                lastLoungeVideoId = videoIdNonNull
                lastLoungeIndex = status.currentIndex ?: lastLoungeIndex
                loungeDeviceReachable = true
                loungeReachabilityLastSuccess = SystemClock.elapsedRealtime()
                isLoungeCasting = true
                updateLoungeNowPlaying(status)
                detachProgressFromPlayer()
                updateLoungeArtwork(PlayingQueue.getCurrent())
                player?.pause()
                binding.playPauseBTN.setImageResource(if (loungeIsPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                updateCastIcon()
                (context as? MainActivity)?.invalidateMenu()
                loungeRemoteDriveUntilMs = SystemClock.elapsedRealtime() + 3_500
                val queueSignature = buildLoungeQueueSignature(
                    videoIdNonNull,
                    status.currentIndex ?: currentIndex,
                    status.videoIds ?: queueIds,
                    listId,
                    params,
                    playerParams
                )
                loungeLastSyncSignature = queueSignature
                loungeLastSyncMs = SystemClock.elapsedRealtime()
                pinControllerForCasting()
                startLoungeHeartbeat(lifecycleOwner)
                Toast.makeText(context, R.string.cast_connected, Toast.LENGTH_SHORT).show()
                return@launch
            }

            Log.d("LoungeSender", "sendCurrentToLounge videoId=$videoId position=$position device=${pairedDevice.screenId}")
            Toast.makeText(context, R.string.cast_sender_sending, Toast.LENGTH_SHORT).show()
            val result = sender.sendVideo(
                videoId = videoId,
                startPositionMs = position,
                queue = queueIds,
                currentIndex = currentIndex,
                listId = listId,
                params = params,
                playerParams = playerParams
            )
            if (result.isSuccess) {
                onSuccess?.invoke()
                isLoungeCasting = true
                loungeIsPlaying = true
                loungePositionMs = position
                loungeLastRealtimeMs = SystemClock.elapsedRealtime()
                lastLoungeVideoId = videoId
                lastLoungeIndex = currentIndex
                loungeLastSyncSignature = buildLoungeQueueSignature(videoId, currentIndex, queueIds, listId, params, playerParams)
                loungeLastSyncMs = SystemClock.elapsedRealtime()
                loungeDeviceReachable = true
                loungeReachabilityLastSuccess = SystemClock.elapsedRealtime()
                detachProgressFromPlayer()
                updateLoungeArtwork(currentItem)
                player?.pause()
                binding.playPauseBTN.setImageResource(R.drawable.ic_pause)
                updateCastIcon()
                (context as? MainActivity)?.invalidateMenu()
                pinControllerForCasting() // Added pinControllerForCasting to manage casting state
                startLoungeHeartbeat(lifecycleOwner)
                Toast.makeText(
                    context,
                    context.getString(R.string.cast_sender_sent, pairedDevice.name),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Log.e("LoungeSender", "sendVideo failed", result.exceptionOrNull())
                Toast.makeText(context, R.string.cast_pair_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun disconnectFromLounge() {
        val lifecycleOwner = findViewTreeLifecycleOwner() ?: return
        lifecycleOwner.lifecycleScope.launch {
            loungeSender.pause()
            loungeSender.clearActiveDevice()
        }

        stopLoungeHeartbeat()
        resetLoungeSession(clearActiveDevice = false)
    }

    private fun resetLoungeSession(
        clearActiveDevice: Boolean = true,
        resumeLocalPlayback: Boolean = true,
        toastResId: Int? = R.string.cast_disconnected
    ) {
        if (clearActiveDevice) loungeSender.clearActiveDevice()

        isLoungeCasting = false
        loungeIsPlaying = false
        loungePositionMs = 0L
        loungeDeviceReachable = false
        loungeReachabilityLastCheck = 0L
        loungeReachabilityLastSuccess = 0L
        lastAutoCastVideoId = null
        loungeLastSyncSignature = null
        loungeLastSyncMs = 0L
        loungeRemoteDriveUntilMs = 0L
        loungeLastTransitionSignature = null
        loungeLastTransitionMs = 0L

        updateLoungeArtwork(null)

        if (resumeLocalPlayback) {
            player?.play()
        }
        player?.let { binding.playPauseBTN.setImageResource(PlayerHelper.getPlayPauseActionIcon(it)) }
        keepScreenOn = player?.isPlaying == true
        attachProgressToPlayer()
        updateCastIcon()
        (context as? MainActivity)?.invalidateMenu()

        toastResId?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }

        findViewTreeLifecycleOwner()?.let { startLoungeReachabilityPing(it) }
    }

    private fun pinControllerForCasting() {
        if (isLoungeCasting) showControllerPermanently()
    }

    private fun updateCastIcon() {
        val now = SystemClock.elapsedRealtime()
        val freshReachable = loungeDeviceReachable && (now - loungeReachabilityLastSuccess <= LOUNGE_REACHABILITY_STALE_MS)
        val connected = isLoungeCasting || freshReachable
        val icon = if (connected) R.drawable.ic_cast_connected else R.drawable.ic_cast
        // Follow Cast design: default tint, highlight when connected.
        val tintAttr = if (connected) androidx.appcompat.R.attr.colorPrimary else androidx.appcompat.R.attr.colorControlNormal
        val tintColor = MaterialColors.getColor(binding.castButton, tintAttr, Color.WHITE)

        binding.castButton.setImageResource(icon)
        ImageViewCompat.setImageTintList(binding.castButton, ColorStateList.valueOf(tintColor))
        binding.castButton.contentDescription = context.getString(if (connected) R.string.cast_connected else R.string.cast)
        (context as? MainActivity)?.invalidateMenu()
    }

    private suspend fun syncLoungePlaylist(positionMs: Long? = null, reason: String) {
        if (!isLoungeCasting) return

        val currentItem = PlayingQueue.getCurrent()
        val videoId = currentItem?.url?.toID() ?: return
        val queueIds = PlayingQueue.getStreams().mapNotNull { it.url?.toID() }.ifEmpty { listOf(videoId) }
        val currentIndex = PlayingQueue.currentIndex().let { idx -> if (idx in queueIds.indices) idx else 0 }
        val listId = PlayingQueue.currentPlaylistId()
        val params = PlayingQueue.currentParams()
        val playerParams = PlayingQueue.currentPlayerParams()
        val startPosition = positionMs ?: player?.currentPosition ?: 0L
        val now = SystemClock.elapsedRealtime()
        val signature = buildLoungeQueueSignature(videoId, currentIndex, queueIds, listId, params, playerParams)

        if (now < loungeRemoteDriveUntilMs) {
            Log.d("LoungeSender", "skip lounge sync reason=$reason remote drive window active")
            return
        }

        if (videoId == lastLoungeVideoId && currentIndex == lastLoungeIndex) {
            Log.d("LoungeSender", "skip lounge sync reason=$reason video unchanged id=$videoId index=$currentIndex")
            return
        }

        if (signature == loungeLastSyncSignature && now - loungeLastSyncMs < 4_000) {
            Log.d(
                "LoungeSender",
                "skip lounge sync reason=$reason signature unchanged within ${now - loungeLastSyncMs}ms"
            )
            return
        }

        if (reason == "media_item_transition") {
            val sinceLast = now - loungeLastTransitionMs
            if (signature == loungeLastTransitionSignature && sinceLast < 2_000) {
                Log.d("LoungeSender", "skip lounge sync reason=$reason duplicate transition within ${sinceLast}ms")
                return
            }
            loungeLastTransitionSignature = signature
            loungeLastTransitionMs = now
        }

        Log.d(
            "LoungeSender",
            "syncLoungePlaylist reason=$reason videoId=$videoId positionMs=$startPosition index=$currentIndex queueSize=${queueIds.size}"
        )

        val result = loungeSender.sendVideo(
            videoId = videoId,
            startPositionMs = startPosition,
            queue = queueIds,
            currentIndex = currentIndex,
            listId = listId,
            params = params,
            playerParams = playerParams
        )

        if (result.isSuccess) {
            isLoungeCasting = true
            loungeIsPlaying = true
            loungePositionMs = startPosition
            loungeLastRealtimeMs = SystemClock.elapsedRealtime()
            lastLoungeVideoId = videoId
            lastLoungeIndex = currentIndex
            loungeLastSyncSignature = signature
            loungeLastSyncMs = now
            loungeDeviceReachable = true
            detachProgressFromPlayer()
            updateLoungeArtwork(currentItem)
            player?.pause()
            binding.playPauseBTN.setImageResource(R.drawable.ic_pause)
            updateCastIcon()
            (context as? MainActivity)?.invalidateMenu()
            findViewTreeLifecycleOwner()?.let { startLoungeHeartbeat(it) }
            pinControllerForCasting()
        } else {
            Log.e("LoungeSender", "syncLoungePlaylist failed reason=$reason", result.exceptionOrNull())
        }
    }

    private fun toggleLoungePlayback() {
        val lifecycleOwner = findViewTreeLifecycleOwner() ?: return
        lifecycleOwner.lifecycleScope.launch {
            val result = if (loungeIsPlaying) loungeSender.pause() else loungeSender.play()
            if (result.isSuccess) {
                if (loungeIsPlaying) {
                    // pausing: fix the tracked position to the current elapsed time
                    loungePositionMs = currentLoungePositionMs()
                } else {
                    // resuming: start counting from now
                    loungeLastRealtimeMs = SystemClock.elapsedRealtime()
                }
                loungeIsPlaying = !loungeIsPlaying
                val icon = if (loungeIsPlaying) R.drawable.ic_pause else R.drawable.ic_play
                binding.playPauseBTN.setImageResource(icon)
            } else {
                Toast.makeText(context, R.string.cast_pair_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendLoungeSeek(position: Long) {
        val lifecycleOwner = findViewTreeLifecycleOwner() ?: return
        loungePositionMs = position
        if (loungeIsPlaying) loungeLastRealtimeMs = SystemClock.elapsedRealtime()

        loungeSeekGuardJob?.cancel()
        loungeSeekGuardJob = lifecycleOwner.lifecycleScope.launch {
            delay(1_200)
            val drift = abs(currentLoungePositionMs() - position)
            if (drift > 800) {
                Log.d("LoungeSender", "seek guard ping drift=$drift expected=$position")
                val status = withContext(Dispatchers.IO) { loungeSender.ping() }.getOrNull()
                status?.let { updateLoungeNowPlaying(it) }
            }
        }

        lifecycleOwner.lifecycleScope.launch {
            Log.d("LoungeSender", "seek request targetMs=$position playing=$loungeIsPlaying")
            val seekResult = loungeSender.seekTo(position)
            if (seekResult.isFailure) {
                Log.w("LoungeSender", "seek failed", seekResult.exceptionOrNull())
                return@launch
            }

            // Some receivers do not emit nowPlaying after a seek; ping once to refresh.
            delay(350)
            val pingResult = loungeSender.ping()
            val status = pingResult.getOrNull()
            if (status != null) {
                updateLoungeNowPlaying(status)
            } else if (pingResult.isFailure) {
                Log.d("LoungeSender", "ping after seek failed", pingResult.exceptionOrNull())
            }
        }
    }

    private fun sendLoungeSeekDelta(delta: Long) {
        val currentPosition = currentLoungePositionMs()
        val target = (currentPosition + delta).coerceAtLeast(0L)
        player?.seekTo(target)
        sendLoungeSeek(target)
    }

    private fun sendLoungeNext() {
        val lifecycleOwner = findViewTreeLifecycleOwner() ?: return
        lifecycleOwner.lifecycleScope.launch { loungeSender.next() }
    }

    private fun sendLoungePrevious() {
        val lifecycleOwner = findViewTreeLifecycleOwner() ?: return
        lifecycleOwner.lifecycleScope.launch { loungeSender.previous() }
    }

    private fun maybeAutoCastCurrentItem(events: Player.Events) {
        if (!events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_TIMELINE_CHANGED
            )
        ) return

        if (isLoungeCasting) {
            Log.d("LoungeSender", "autoCast skipped: already casting")
            return
        }

        val castEnabled = PreferenceHelper.getBoolean(PreferenceKeys.CAST_ENABLED, true)
        if (!castEnabled) {
            Log.d("LoungeSender", "autoCast skipped: cast disabled in prefs")
            return
        }

        val lifecycleOwner = findViewTreeLifecycleOwner()
        if (lifecycleOwner == null) {
            Log.d("LoungeSender", "autoCast skipped: no lifecycleOwner")
            return
        }

        val device = loungeSender.currentDevice()
        if (device == null) {
            Log.d("LoungeSender", "autoCast skipped: no active lounge device")
            return
        }

        val currentItem = PlayingQueue.getCurrent()
        val videoId = currentItem?.url?.toID()
        if (videoId.isNullOrBlank()) {
            Log.d("LoungeSender", "autoCast skipped: current videoId missing")
            return
        }

        if (lastAutoCastVideoId == videoId) {
            Log.d("LoungeSender", "autoCast skipped: already sent videoId=$videoId")
            return
        }

        lifecycleOwner.lifecycleScope.launch {
            Log.d(
                "LoungeSender",
                "autoCast sendCurrent videoId=$videoId device=${device.screenId} name=${device.name}"
            )
            sendCurrentToLounge(loungeSender, device, lifecycleOwner) {
                lastAutoCastVideoId = videoId
            }
        }
    }

    private fun startLoungeReachabilityPing(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        loungeSessionController.startReachability(
            lifecycleOwner.lifecycleScope,
            onReachabilityUpdate = { reachable, status ->
                loungeDeviceReachable = reachable
                loungeReachabilityLastCheck = SystemClock.elapsedRealtime()
                if (reachable) {
                    loungeReachabilityLastSuccess = loungeReachabilityLastCheck
                } else {
                    loungeReachabilityLastSuccess = 0L
                    lastAutoCastVideoId = null
                }

                // If we are not currently casting but the receiver is already playing something, surface its state.
                if (!isLoungeCasting && status?.videoId != null) {
                    Log.d(
                        "LoungeSender",
                        "reachability nowPlaying videoId=${status.videoId} index=${status.currentIndex} queue=${status.videoIds?.size ?: 0}"
                    )
                    updateLoungeNowPlaying(status)
                }

                updateCastIcon()
                (context as? MainActivity)?.invalidateMenu()
            },
            onDeviceCleared = {
                loungeDeviceReachable = false
                loungeReachabilityLastSuccess = 0L
                lastAutoCastVideoId = null
                updateCastIcon()
                (context as? MainActivity)?.invalidateMenu()
            }
        )
    }

    open fun onPlaybackEvents(player: Player, events: Player.Events) {
        pinControllerForCasting()
        maybeAutoCastCurrentItem(events)

        if (isLoungeCasting && events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                syncLoungePlaylist(reason = "media_item_transition")
            }
        }

        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED
            )
        ) {
            if (isLoungeCasting) {
                val icon = if (loungeIsPlaying) R.drawable.ic_pause else R.drawable.ic_play
                binding.playPauseBTN.setImageResource(icon)
                keepScreenOn = loungeIsPlaying
                return
            }
            binding.playPauseBTN.setImageResource(
                PlayerHelper.getPlayPauseActionIcon(player)
            )

            // keep screen on if the video is playing
            keepScreenOn = player.isPlaying == true
            onPlayerEvent(player, events)
        }
    }

    open fun minimizeOrExitPlayer() = Unit

    open fun getWindow(): Window = activity.window

    companion object {
        private const val HIDE_CONTROLLER_TOKEN = "hideController"
        private const val HIDE_FORWARD_BUTTON_TOKEN = "hideForwardButton"
        private const val HIDE_REWIND_BUTTON_TOKEN = "hideRewindButton"
        private const val UPDATE_POSITION_TOKEN = "updatePosition"

        private const val SUBTITLE_BOTTOM_PADDING_FRACTION = 0.158f
        private const val ANIMATION_DURATION = 100L
        private const val AUTO_HIDE_CONTROLLER_DELAY = 2000L
        private val LANDSCAPE_MARGIN_HORIZONTAL = 20f.dpToPx()
        private val LANDSCAPE_MARGIN_HORIZONTAL_NONE = 0f.dpToPx()
        private const val LOUNGE_REACHABILITY_STALE_MS = 45_000L
    }
}
