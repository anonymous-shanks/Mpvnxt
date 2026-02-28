package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.GesturePreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.repository.wyzie.WyzieSearchRepository
import app.marlboroadvance.mpvex.repository.wyzie.WyzieSubtitle
import app.marlboroadvance.mpvex.utils.media.MediaInfoParser
import `is`.xyz.mpv.MPVLib
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import app.marlboroadvance.mpvex.ui.preferences.CustomButton
import java.io.File
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

enum class RepeatMode {
  OFF,      // No repeat
  ONE,      // Repeat current file
  ALL       // Repeat all (playlist)
}

class PlayerViewModelProviderFactory(
  private val host: PlayerHost,
) : ViewModelProvider.Factory {
  override fun <T : ViewModel> create(
    modelClass: Class<T>,
    extras: CreationExtras,
  ): T {
    if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
      @Suppress("UNCHECKED_CAST")
      return PlayerViewModel(host) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}

@Suppress("TooManyFunctions")
class PlayerViewModel(
  private val host: PlayerHost,
) : ViewModel(),
  KoinComponent {
  private val playerPreferences: PlayerPreferences by inject()
  private val appearancePreferences: AppearancePreferences by inject()
  private val gesturePreferences: GesturePreferences by inject()
  private val audioPreferences: AudioPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val advancedPreferences: AdvancedPreferences by inject()
  private val json: Json by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val wyzieRepository: WyzieSearchRepository by inject()

  private val _playlistItems = MutableStateFlow<List<app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlaylistItem>>(emptyList())
  val playlistItems: StateFlow<List<app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlaylistItem>> = _playlistItems.asStateFlow()

  private val _wyzieSearchResults = MutableStateFlow<List<WyzieSubtitle>>(emptyList())
  val wyzieSearchResults: StateFlow<List<WyzieSubtitle>> = _wyzieSearchResults.asStateFlow()

  private val _isDownloadingSub = MutableStateFlow(false)
  val isDownloadingSub: StateFlow<Boolean> = _isDownloadingSub.asStateFlow()

  private val _isSearchingSub = MutableStateFlow(false)
  val isSearchingSub: StateFlow<Boolean> = _isSearchingSub.asStateFlow()

  private val _isOnlineSectionExpanded = MutableStateFlow(true)
  val isOnlineSectionExpanded: StateFlow<Boolean> = _isOnlineSectionExpanded.asStateFlow()

  private val _mediaSearchResults = MutableStateFlow<List<app.marlboroadvance.mpvex.repository.wyzie.WyzieTmdbResult>>(emptyList())
  val mediaSearchResults: StateFlow<List<app.marlboroadvance.mpvex.repository.wyzie.WyzieTmdbResult>> = _mediaSearchResults.asStateFlow()

  private val _isSearchingMedia = MutableStateFlow(false)
  val isSearchingMedia: StateFlow<Boolean> = _isSearchingMedia.asStateFlow()

  private val _selectedTvShow = MutableStateFlow<app.marlboroadvance.mpvex.repository.wyzie.WyzieTvShowDetails?>(null)
  val selectedTvShow: StateFlow<app.marlboroadvance.mpvex.repository.wyzie.WyzieTvShowDetails?> = _selectedTvShow.asStateFlow()

  private val _isFetchingTvDetails = MutableStateFlow(false)
  val isFetchingTvDetails: StateFlow<Boolean> = _isFetchingTvDetails.asStateFlow()

  private val _selectedSeason = MutableStateFlow<app.marlboroadvance.mpvex.repository.wyzie.WyzieSeason?>(null)
  val selectedSeason: StateFlow<app.marlboroadvance.mpvex.repository.wyzie.WyzieSeason?> = _selectedSeason.asStateFlow()

  private val _seasonEpisodes = MutableStateFlow<List<app.marlboroadvance.mpvex.repository.wyzie.WyzieEpisode>>(emptyList())
  val seasonEpisodes: StateFlow<List<app.marlboroadvance.mpvex.repository.wyzie.WyzieEpisode>> = _seasonEpisodes.asStateFlow()

  private val _isFetchingEpisodes = MutableStateFlow(false)
  val isFetchingEpisodes: StateFlow<Boolean> = _isFetchingEpisodes.asStateFlow()

  private val _selectedEpisode = MutableStateFlow<app.marlboroadvance.mpvex.repository.wyzie.WyzieEpisode?>(null)
  val selectedEpisode: StateFlow<app.marlboroadvance.mpvex.repository.wyzie.WyzieEpisode?> = _selectedEpisode.asStateFlow()

  fun toggleOnlineSection() { _isOnlineSectionExpanded.value = !_isOnlineSectionExpanded.value }

  private val metadataCache = object : android.util.LruCache<String, Triple<String, String, Boolean>>(100) {}

  private fun updateMetadataCache(key: String, value: Triple<String, String, Boolean>) { metadataCache.put(key, value) }

  val paused by MPVLib.propBoolean["pause"].collectAsState(viewModelScope)
  val pos by MPVLib.propInt["time-pos"].collectAsState(viewModelScope)
  val duration by MPVLib.propInt["duration"].collectAsState(viewModelScope)

  private val _precisePosition = MutableStateFlow(0f)
  val precisePosition = _precisePosition.asStateFlow()

  private val _preciseDuration = MutableStateFlow(0f)
  val preciseDuration = _preciseDuration.asStateFlow()

  val currentVolume = MutableStateFlow(host.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
  private val volumeBoostCap by MPVLib.propInt["volume-max"].collectAsState(viewModelScope)

  init {
    viewModelScope.launch {
      while (isActive) {
        MPVLib.getPropertyDouble("time-pos")?.let { _precisePosition.value = it.toFloat() }
        delay(16)
      }
    }
    viewModelScope.launch {
      MPVLib.propInt["duration"].collect {
        MPVLib.getPropertyDouble("duration")?.let { if (it > 0) _preciseDuration.value = it.toFloat() }
      }
    }
  }

  val maxVolume = host.audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

  val subtitleTracks: StateFlow<List<TrackNode>> = MPVLib.propNode["track-list"].map { node ->
    node?.toObject<List<TrackNode>>(json)?.filter { it.isSubtitle }?.toImmutableList() ?: persistentListOf()
  }.stateIn(viewModelScope, SharingStarted.Lazily, persistentListOf())

  val audioTracks: StateFlow<List<TrackNode>> = MPVLib.propNode["track-list"].map { node ->
    node?.toObject<List<TrackNode>>(json)?.filter { it.isAudio }?.toImmutableList() ?: persistentListOf()
  }.stateIn(viewModelScope, SharingStarted.Lazily, persistentListOf())

  val chapters: StateFlow<List<dev.vivvvek.seeker.Segment>> = MPVLib.propNode["chapter-list"].map { node ->
    node?.toObject<List<ChapterNode>>(json)?.map { it.toSegment() }?.toImmutableList() ?: persistentListOf()
  }.stateIn(viewModelScope, SharingStarted.Lazily, persistentListOf())

  private val _controlsShown = MutableStateFlow(false)
  val controlsShown: StateFlow<Boolean> = _controlsShown.asStateFlow()
  private val _seekBarShown = MutableStateFlow(false)
  val seekBarShown: StateFlow<Boolean> = _seekBarShown.asStateFlow()
  private val _areControlsLocked = MutableStateFlow(false)
  val areControlsLocked: StateFlow<Boolean> = _areControlsLocked.asStateFlow()

  val playerUpdate = MutableStateFlow<PlayerUpdates>(PlayerUpdates.None)
  val isBrightnessSliderShown = MutableStateFlow(false)
  val isVolumeSliderShown = MutableStateFlow(false)
  val volumeSliderTimestamp = MutableStateFlow(0L)
  val brightnessSliderTimestamp = MutableStateFlow(0L)
  val currentBrightness = MutableStateFlow(runCatching {
    Settings.System.getFloat(host.hostContentResolver, Settings.System.SCREEN_BRIGHTNESS).normalize(0f, 255f, 0f, 1f)
  }.getOrElse { 0f })

  val sheetShown = MutableStateFlow(Sheets.None)
  val panelShown = MutableStateFlow(Panels.None)

  private val _seekText = MutableStateFlow<String?>(null)
  val seekText: StateFlow<String?> = _seekText.asStateFlow()
  private val _doubleTapSeekAmount = MutableStateFlow(0)
  val doubleTapSeekAmount: StateFlow<Int> = _doubleTapSeekAmount.asStateFlow()
  private val _isSeekingForwards = MutableStateFlow(false)
  val isSeekingForwards: StateFlow<Boolean> = _isSeekingForwards.asStateFlow()

  private val _currentFrame = MutableStateFlow(0)
  val currentFrame: StateFlow<Int> = _currentFrame.asStateFlow()
  private val _totalFrames = MutableStateFlow(0)
  val totalFrames: StateFlow<Int> = _totalFrames.asStateFlow()
  private val _isFrameNavigationExpanded = MutableStateFlow(false)
  val isFrameNavigationExpanded: StateFlow<Boolean> = _isFrameNavigationExpanded.asStateFlow()
  private val _isSnapshotLoading = MutableStateFlow(false)
  val isSnapshotLoading: StateFlow<Boolean> = _isSnapshotLoading.asStateFlow()

  private val _videoZoom = MutableStateFlow(0f)
  val videoZoom: StateFlow<Float> = _videoZoom.asStateFlow()
  
  // FIXED: ADDED PAN VARIABLES
  private val _videoPanX = MutableStateFlow(0f)
  val videoPanX: StateFlow<Float> = _videoPanX.asStateFlow()
  
  private val _videoPanY = MutableStateFlow(0f)
  val videoPanY: StateFlow<Float> = _videoPanY.asStateFlow()

  private var timerJob: Job? = null
  private val _remainingTime = MutableStateFlow(0)
  val remainingTime: StateFlow<Int> = _remainingTime.asStateFlow()

  var currentMediaTitle: String = ""
  private var lastAutoSelectedMediaTitle: String? = null
  private val _externalSubtitles = mutableListOf<String>()
  val externalSubtitles: List<String> get() = _externalSubtitles.toList()
  private val mpvPathToUriMap = mutableMapOf<String, String>()

  private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
  val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()
  private val _shuffleEnabled = MutableStateFlow(false)
  val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

  private val _abLoopA = MutableStateFlow<Double?>(null)
  val abLoopA: StateFlow<Double?> = _abLoopA.asStateFlow()
  private val _abLoopB = MutableStateFlow<Double?>(null)
  val abLoopB: StateFlow<Double?> = _abLoopB.asStateFlow()
  private val _isABLoopExpanded = MutableStateFlow(false)
  val isABLoopExpanded: StateFlow<Boolean> = _isABLoopExpanded.asStateFlow()

  private val _isMirrored = MutableStateFlow(false)
  val isMirrored: StateFlow<Boolean> = _isMirrored.asStateFlow()
  private val _isVerticalFlipped = MutableStateFlow(false)
  val isVerticalFlipped: StateFlow<Boolean> = _isVerticalFlipped.asStateFlow()

  init {
    _repeatMode.value = playerPreferences.repeatMode.get()
    _shuffleEnabled.value = playerPreferences.shuffleEnabled.get()

    viewModelScope.launch {
      audioPreferences.volumeBoostCap.changes().collect { cap ->
        val maxVol = 100 + cap
        MPVLib.setPropertyString("volume-max", maxVol.toString())
        val currentMpvVol = MPVLib.getPropertyInt("volume") ?: 100
        if (currentMpvVol > maxVol) MPVLib.setPropertyInt("volume", maxVol)
      }
    }

    viewModelScope.launch {
      combine(MPVLib.propInt["duration"], abLoopA, abLoopB) { d, la, lb -> Triple(d, la, lb) }.collect { (d, la, lb) ->
        val videoDuration = d ?: 0
        val isLoopActive = la != null || lb != null
        val shouldUsePreciseSeeking = playerPreferences.usePreciseSeeking.get() || videoDuration < 120 || isLoopActive
        MPVLib.setPropertyString("hr-seek", if (shouldUsePreciseSeeking) "yes" else "no")
        MPVLib.setPropertyString("hr-seek-framedrop", if (shouldUsePreciseSeeking) "no" else "yes")
      }
    }

    setupCustomButtons()
  }

  // ==================== Custom Buttons ====================
  data class CustomButtonState(val id: String, val label: String, val isLeft: Boolean)
  private val _customButtons = MutableStateFlow<List<CustomButtonState>>(emptyList())
  val customButtons: StateFlow<List<CustomButtonState>> = _customButtons.asStateFlow()

  private fun setupCustomButtons() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val buttons = mutableListOf<CustomButtonState>()
        if (!advancedPreferences.enableLuaScripts.get()) { _customButtons.value = buttons; return@launch }
        val scriptContent = buildString {
          val jsonString = playerPreferences.customButtons.get()
          if (jsonString.isNotBlank()) {
            try {
               val slotsData = json.decodeFromString<app.marlboroadvance.mpvex.ui.preferences.CustomButtonSlots>(jsonString)
               slotsData.slots.forEachIndexed { index, btn -> if (btn != null) processButton(btn.id, btn.id.replace("-", "_"), btn.title, btn.content, btn.longPressContent, btn.onStartup, index < 4, buttons) }
            } catch (e: Exception) {
               try {
                 val list = json.decodeFromString<List<app.marlboroadvance.mpvex.ui.preferences.CustomButton>>(jsonString)
                 list.forEachIndexed { index, btn -> processButton(btn.id, btn.id.replace("-", "_"), btn.title, btn.content, btn.longPressContent, btn.onStartup, index < 4, buttons) }
               } catch (e2: Exception) { e2.printStackTrace() }
            }
          }
        }
        _customButtons.value = buttons
        if (scriptContent.isNotEmpty()) {
          val scriptsDir = File(host.context.filesDir, "scripts").apply { if (!exists()) mkdirs() }
          File(scriptsDir, "custombuttons.lua").apply { writeText(scriptContent); MPVLib.command("load-script", absolutePath) }
        }
      } catch (e: Exception) { Log.e(TAG, "Custom button error", e) }
    }
  }

  fun callCustomButton(id: String) { MPVLib.command("script-message", "call_button_${id.replace("-", "_")}") }
  fun callCustomButtonLongPress(id: String) { MPVLib.command("script-message", "call_button_long_${id.replace("-", "_")}") }

  private fun StringBuilder.processButton(oid: String, sid: String, label: String, cmd: String, lpCmd: String, startup: String, left: Boolean, uiList: MutableList<CustomButtonState>) {
    if (label.isNotBlank()) {
      uiList.add(CustomButtonState(oid, label, left))
      if (startup.isNotBlank()) append(startup).append("\n")
      if (cmd.isNotBlank()) append("function button_$sid()\n    $cmd\nend\nmp.register_script_message('call_button_$sid', button_$sid)\n")
      if (lpCmd.isNotBlank()) append("function button_long_$sid()\n    $lpCmd\nend\nmp.register_script_message('call_button_long_$sid', button_long_$sid)\n")
    }
  }

  // ==================== Playback & UI Logic ====================
  fun pause() { viewModelScope.launch(Dispatchers.IO) { MPVLib.setPropertyBoolean("pause", true); withContext(Dispatchers.Main) { host.abandonAudioFocus() } } }
  fun unpause() { viewModelScope.launch(Dispatchers.IO) { withContext(Dispatchers.Main) { host.requestAudioFocus() }; MPVLib.setPropertyBoolean("pause", false) } }
  fun pauseUnpause() { if (paused == true) unpause() else pause() }
  fun applyPersistedShuffleState() { if (_shuffleEnabled.value) (host as? PlayerActivity)?.onShuffleToggled(true) }

  fun showControls() {
    if (sheetShown.value != Sheets.None || panelShown.value != Panels.None) return
    try {
      if (playerPreferences.showSystemStatusBar.get()) host.windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
      if (playerPreferences.showSystemNavigationBar.get()) host.windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
    } catch (e: Exception) { Log.e(TAG, "Bars show error", e) }
    _controlsShown.value = true
  }

  fun hideControls() {
    try {
      if (playerPreferences.showSystemStatusBar.get()) host.windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
      if (playerPreferences.showSystemNavigationBar.get()) host.windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
    } catch (e: Exception) { Log.e(TAG, "Bars hide error", e) }
    _controlsShown.value = false; _seekBarShown.value = false
  }

  fun autoHideControls() { hideControls(); _seekBarShown.value = true }
  fun showSeekBar() { if (sheetShown.value == Sheets.None) _seekBarShown.value = true }
  fun hideSeekBar() { _seekBarShown.value = false }
  fun lockControls() { _areControlsLocked.value = true }
  fun unlockControls() { _areControlsLocked.value = false }

  fun seekBy(offset: Int) { coalesceSeek(offset) }
  fun seekTo(position: Int) {
    viewModelScope.launch(Dispatchers.IO) {
      val maxDur = MPVLib.getPropertyInt("duration") ?: 0
      val clampedPos = position.coerceIn(0, maxDur)
      seekCoalesceJob?.cancel(); pendingSeekOffset = 0
      val mode = if (playerPreferences.usePreciseSeeking.get() || maxDur < 120) "absolute+exact" else "absolute+keyframes"
      MPVLib.command("seek", clampedPos.toString(), mode)
    }
  }

  private fun coalesceSeek(offset: Int) {
    pendingSeekOffset += offset
    seekCoalesceJob?.cancel()
    seekCoalesceJob = viewModelScope.launch(Dispatchers.IO) {
      delay(60); val toApply = pendingSeekOffset; pendingSeekOffset = 0
      if (toApply != 0) {
        val d = MPVLib.getPropertyInt("duration") ?: 0
        val mode = if (playerPreferences.usePreciseSeeking.get() || d < 120) "relative+exact" else "relative+keyframes"
        MPVLib.command("seek", toApply.toString(), mode)
      }
    }
  }

  fun leftSeek() { if ((pos ?: 0) > 0) _doubleTapSeekAmount.value -= doubleTapToSeekDuration; _isSeekingForwards.value = false; seekBy(-doubleTapToSeekDuration) }
  fun rightSeek() { if ((pos ?: 0) < (duration ?: 0)) _doubleTapSeekAmount.value += doubleTapToSeekDuration; _isSeekingForwards.value = true; seekBy(doubleTapToSeekDuration) }
  fun updateSeekAmount(a: Int) { _doubleTapSeekAmount.value = a }
  fun updateSeekText(t: String?) { _seekText.value = t }

  fun changeBrightnessTo(brightness: Float) {
    val b = brightness.coerceIn(0f, 1f)
    host.hostWindow.attributes = host.hostWindow.attributes.apply { screenBrightness = b }
    currentBrightness.value = b
    if (playerPreferences.rememberBrightness.get()) playerPreferences.defaultBrightness.set(b)
  }
  fun displayBrightnessSlider() { isBrightnessSliderShown.value = true; brightnessSliderTimestamp.value = System.currentTimeMillis() }

  fun changeVolumeBy(change: Int) {
    val currentMpv = MPVLib.getPropertyInt("volume") ?: 100
    val maxMpv = 100 + audioPreferences.volumeBoostCap.get()
    if (currentVolume.value == maxVolume && change > 0 && currentMpv < maxMpv) { changeMPVVolumeTo(currentMpv + change); return }
    if (currentMpv > 100 && change < 0) { changeMPVVolumeTo(currentMpv + change); return }
    changeVolumeTo(currentVolume.value + change)
  }
  fun changeVolumeTo(volume: Int) {
    val v = volume.coerceIn(0..maxVolume)
    host.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
    currentVolume.value = v
  }
  fun changeMPVVolumeTo(volume: Int) { MPVLib.setPropertyInt("volume", volume) }
  fun displayVolumeSlider() { isVolumeSliderShown.value = true; volumeSliderTimestamp.value = System.currentTimeMillis() }

  fun changeVideoAspect(aspect: VideoAspect, showUpdate: Boolean = true) {
    when (aspect) {
      VideoAspect.Fit -> { MPVLib.setPropertyDouble("panscan", 0.0); MPVLib.setPropertyDouble("video-aspect-override", -1.0) }
      VideoAspect.Crop -> MPVLib.setPropertyDouble("panscan", 1.0)
      VideoAspect.Stretch -> {
        val dm = DisplayMetrics(); host.hostWindowManager.defaultDisplay.getRealMetrics(dm)
        MPVLib.setPropertyDouble("video-aspect-override", dm.widthPixels / dm.heightPixels.toDouble())
      }
    }
    playerPreferences.videoAspect.set(aspect)
    if (showUpdate) playerUpdate.value = PlayerUpdates.AspectRatio
  }
  fun setCustomAspectRatio(ratio: Double) { MPVLib.setPropertyDouble("panscan", 0.0); MPVLib.setPropertyDouble("video-aspect-override", ratio); playerPreferences.currentAspectRatio.set(ratio.toFloat()); playerUpdate.value = PlayerUpdates.AspectRatio }
  fun restoreCustomAspectRatio() { val r = playerPreferences.currentAspectRatio.get(); if (r > 0) { MPVLib.setPropertyDouble("panscan", 0.0); MPVLib.setPropertyDouble("video-aspect-override", r.toDouble()) } }

  fun cycleScreenRotations() {
    host.hostRequestedOrientation = when (host.hostRequestedOrientation) {
      ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
  }

  fun handleLuaInvocation(property: String, value: String) {
    val data = value.removeSurrounding("\"").ifEmpty { return }
    when (property.substringAfterLast("/")) {
      "show_text" -> playerUpdate.value = PlayerUpdates.ShowText(data)
      "toggle_ui" -> when(data) { "show" -> showControls(); "toggle" -> if (controlsShown.value) hideControls() else showControls(); "hide" -> { sheetShown.value = Sheets.None; panelShown.value = Panels.None; hideControls() } }
      "show_panel" -> if (data == "frame_navigation") sheetShown.value = Sheets.FrameNavigation else panelShown.value = when(data) { "subtitle_settings" -> Panels.SubtitleSettings; "subtitle_delay" -> Panels.SubtitleDelay; "audio_delay" -> Panels.AudioDelay; "video_filters" -> Panels.VideoFilters; else -> Panels.None }
      "seek_to" -> seekTo(data.toInt())
    }
    MPVLib.setPropertyString(property, "")
  }

  fun handleLeftDoubleTap() { if (gesturePreferences.leftSingleActionGesture.get() == SingleActionGesture.Seek) leftSeek() else if (gesturePreferences.leftSingleActionGesture.get() == SingleActionGesture.PlayPause) pauseUnpause() }
  fun handleRightDoubleTap() { if (gesturePreferences.rightSingleActionGesture.get() == SingleActionGesture.Seek) rightSeek() else if (gesturePreferences.rightSingleActionGesture.get() == SingleActionGesture.PlayPause) pauseUnpause() }
  fun handleCenterDoubleTap() { if (gesturePreferences.centerSingleActionGesture.get() == SingleActionGesture.PlayPause) pauseUnpause() }
  fun handleCenterSingleTap() { if (gesturePreferences.centerSingleActionGesture.get() == SingleActionGesture.PlayPause) pauseUnpause() }

  fun setVideoZoom(zoom: Float) { _videoZoom.value = zoom; MPVLib.setPropertyDouble("video-zoom", zoom.toDouble()) }
  
  // FIXED: PAN FUNCTIONS
  fun setVideoPan(x: Float, y: Float) { _videoPanX.value = x; _videoPanY.value = y; MPVLib.setPropertyDouble("video-pan-x", x.toDouble()); MPVLib.setPropertyDouble("video-pan-y", y.toDouble()) }
  fun resetVideoPan() { setVideoPan(0f, 0f) }
  fun resetVideoZoom() { setVideoZoom(0f) }

  fun updateFrameInfo() { _currentFrame.value = MPVLib.getPropertyInt("estimated-frame-number") ?: 0; val dur = MPVLib.getPropertyDouble("duration") ?: 0.0; val fps = MPVLib.getPropertyDouble("container-fps") ?: 0.0; _totalFrames.value = if (dur > 0 && fps > 0) (dur * fps).toInt() else 0 }
  fun toggleFrameNavigationExpanded() { if (!_isFrameNavigationExpanded.value) { if (paused != true) pauseUnpause(); updateFrameInfo(); playerUpdate.value = PlayerUpdates.FrameInfo(_currentFrame.value, _totalFrames.value); resetFrameNavigationTimer() }; _isFrameNavigationExpanded.update { !it } }
  fun frameStepForward() { viewModelScope.launch(Dispatchers.IO) { MPVLib.command("no-osd", "frame-step"); delay(100); updateFrameInfo(); resetFrameNavigationTimer() } }
  fun frameStepBackward() { viewModelScope.launch(Dispatchers.IO) { MPVLib.command("no-osd", "frame-back-step"); delay(100); updateFrameInfo(); resetFrameNavigationTimer() } }
  fun resetFrameNavigationTimer() { timerJob?.cancel(); timerJob = viewModelScope.launch { delay(10000); _isFrameNavigationExpanded.value = false } }

  fun takeSnapshot(context: Context) {
    viewModelScope.launch(Dispatchers.IO) {
      _isSnapshotLoading.value = true
      try {
        val file = File(context.cacheDir, "snap_${System.currentTimeMillis()}.png")
        MPVLib.command("screenshot-to-file", file.absolutePath, if (playerPreferences.includeSubtitlesInSnapshot.get()) "subtitles" else "video")
        delay(500)
        withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.player_sheets_frame_navigation_snapshot_saved), Toast.LENGTH_SHORT).show() }
      } catch (e: Exception) { Log.e(TAG, "Snapshot error", e) } finally { _isSnapshotLoading.value = false }
    }
  }

  // ==================== Playlist Management ====================
  fun hasPlaylistSupport(): Boolean = playerPreferences.playlistMode.get() && ((host as? PlayerActivity)?.playlist?.isNotEmpty() ?: false)
  fun getPlaylistInfo(): String? { val a = host as? PlayerActivity ?: return null; return if (a.playlist.isEmpty()) null else "${a.playlistIndex + 1}/${a.playlist.size}" }
  fun isPlaylistM3U(): Boolean = (host as? PlayerActivity)?.isCurrentPlaylistM3U() ?: false
  fun getPlaylistTotalCount(): Int = (host as? PlayerActivity)?.playlist?.size ?: 0
  fun playPlaylistItem(index: Int) { (host as? PlayerActivity)?.playPlaylistItem(index) }
  fun hasNext(): Boolean = (host as? PlayerActivity)?.hasNext() ?: false
  fun hasPrevious(): Boolean = (host as? PlayerActivity)?.hasPrevious() ?: false
  fun playNext() { (host as? PlayerActivity)?.playNext() }
  fun playPrevious() { (host as? PlayerActivity)?.playPrevious() }

  fun cycleRepeatMode() {
    _repeatMode.value = when (_repeatMode.value) { RepeatMode.OFF -> RepeatMode.ONE; RepeatMode.ONE -> if (getPlaylistTotalCount() > 1) RepeatMode.ALL else RepeatMode.OFF; RepeatMode.ALL -> RepeatMode.OFF }
    playerPreferences.repeatMode.set(_repeatMode.value)
    playerUpdate.value = PlayerUpdates.RepeatMode(_repeatMode.value)
  }

  fun toggleShuffle() { _shuffleEnabled.value = !_shuffleEnabled.value; playerPreferences.shuffleEnabled.set(_shuffleEnabled.value); (host as? PlayerActivity)?.onShuffleToggled(_shuffleEnabled.value); playerUpdate.value = PlayerUpdates.Shuffle(_shuffleEnabled.value) }
  fun shouldRepeatCurrentFile(): Boolean = _repeatMode.value == RepeatMode.ONE || (_repeatMode.value == RepeatMode.ALL && getPlaylistTotalCount() <= 1)
  fun shouldRepeatPlaylist(): Boolean = _repeatMode.value == RepeatMode.ALL && getPlaylistTotalCount() > 1

  fun setLoopA() { val p = MPVLib.getPropertyDouble("time-pos") ?: return; _abLoopA.value = p; MPVLib.setPropertyDouble("ab-loop-a", p) }
  fun setLoopB() { val p = MPVLib.getPropertyDouble("time-pos") ?: return; _abLoopB.value = p; MPVLib.setPropertyDouble("ab-loop-b", p) }
  fun clearABLoop() { _abLoopA.value = null; _abLoopB.value = null; MPVLib.setPropertyString("ab-loop-a", "no"); MPVLib.setPropertyString("ab-loop-b", "no") }
  fun toggleABLoopExpanded() { _isABLoopExpanded.update { !it } }
  fun toggleMirroring() { _isMirrored.value = !_isMirrored.value; if (_isMirrored.value) MPVLib.command("vf", "add", "@mpvex_hflip:hflip") else MPVLib.command("vf", "remove", "@mpvex_hflip") }
  fun toggleVerticalFlip() { _isVerticalFlipped.value = !_isVerticalFlipped.value; if (_isVerticalFlipped.value) MPVLib.command("vf", "add", "@mpvex_vflip:vflip") else MPVLib.command("vf", "remove", "@mpvex_vflip") }
  fun formatTimestamp(s: Double): String { val t = s.toInt(); val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60; return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec) }

  // ==================== Audio/Subtitle Management ====================
  fun addAudio(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        val path = uri.resolveUri(host.context) ?: return@launch
        MPVLib.command("audio-add", path, "cached")
        withContext(Dispatchers.Main) { showToast("Audio track added") }
      }.onFailure { showToast("Failed to load audio") }
    }
  }

  fun addSubtitle(uri: Uri, select: Boolean = true, silent: Boolean = false) {
    viewModelScope.launch(Dispatchers.IO) {
      val uriString = uri.toString()
      if (_externalSubtitles.contains(uriString)) return@launch
      runCatching {
        val fileName = getFileNameFromUri(uri) ?: "subtitle.srt"
        if (!isValidSubtitleFile(fileName)) return@launch
        if (uri.scheme == "content") try { host.context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
        val mpvPath = uri.resolveUri(host.context) ?: uri.toString()
        mpvPathToUriMap[mpvPath] = uri.toString()
        MPVLib.command("sub-add", mpvPath, if (select) "select" else "auto")
        _externalSubtitles.add(uriString)
        if (!silent) withContext(Dispatchers.Main) { showToast("Subtitle added") }
      }.onFailure { if (!silent) showToast("Failed to load subtitle") }
    }
  }

  private fun scanLocalSubtitles(mediaTitle: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val folder = subtitlesPreferences.subtitleSaveFolder.get()
      if (folder.isBlank()) return@launch
      try {
        val sanitized = MediaInfoParser.parse(mediaTitle).title
        val dir = DocumentFile.fromTreeUri(host.context, Uri.parse(folder))?.findFile(sanitized) ?: return@launch
        if (dir.isDirectory) dir.listFiles().forEach { if (it.isFile && isValidSubtitleFile(it.name ?: "")) withContext(Dispatchers.Main) { addSubtitle(it.uri, false, true) } }
      } catch (e: Exception) {}
    }
  }

  fun setMediaTitle(t: String) { if (currentMediaTitle != t) { currentMediaTitle = t; _externalSubtitles.clear(); scanLocalSubtitles(t) } }
  fun removeSubtitle(id: Int) {
    viewModelScope.launch(Dispatchers.IO) {
      subtitleTracks.value.firstOrNull { it.id == id }?.let { if (it.external && it.externalFilename != null) { val uri = mpvPathToUriMap[it.externalFilename]; if (uri != null && wyzieRepository.deleteSubtitleFile(Uri.parse(uri))) { _externalSubtitles.remove(uri); mpvPathToUriMap.remove(it.externalFilename) } } }
      MPVLib.command("sub-remove", id.toString())
    }
  }

  fun searchMedia(q: String) {
    mediaSearchJob?.cancel()
    if (q.isBlank()) { _mediaSearchResults.value = emptyList(); return }
    mediaSearchJob = viewModelScope.launch { delay(300); _isSearchingMedia.value = true; wyzieRepository.searchMedia(q).onSuccess { _mediaSearchResults.value = it }; _isSearchingMedia.value = false }
  }

  fun selectMedia(r: app.marlboroadvance.mpvex.repository.wyzie.WyzieTmdbResult) {
    _mediaSearchResults.value = emptyList(); _wyzieSearchResults.value = emptyList()
    if (r.mediaType == "tv") fetchTvShowDetails(r.id) else searchSubtitles(r.title)
  }

  private fun fetchTvShowDetails(id: Int) {
    viewModelScope.launch {
      _isFetchingTvDetails.value = true
      wyzieRepository.getTvShowDetails(id).onSuccess { d ->
        _selectedTvShow.value = d.copy(seasons = d.seasons.filter { it.season_number > 0 }.sortedBy { it.season_number })
      }.onFailure { showToast("Details error") }
      _isFetchingTvDetails.value = false
    }
  }

  fun selectSeason(s: app.marlboroadvance.mpvex.repository.wyzie.WyzieSeason) {
    val id = _selectedTvShow.value?.id ?: return; _selectedSeason.value = s
    viewModelScope.launch { _isFetchingEpisodes.value = true; wyzieRepository.getSeasonEpisodes(id, s.season_number).onSuccess { _seasonEpisodes.value = it.filter { it.episode_number > 0 }.sortedBy { it.episode_number } }; _isFetchingEpisodes.value = false }
  }

  fun selectEpisode(e: app.marlboroadvance.mpvex.repository.wyzie.WyzieEpisode) { _selectedEpisode.value = e; searchSubtitles(_selectedTvShow.value?.name ?: currentMediaTitle, e.season_number, e.episode_number) }
  fun clearMediaSelection() { _selectedTvShow.value = null; _selectedSeason.value = null; _seasonEpisodes.value = emptyList(); _selectedEpisode.value = null; _mediaSearchResults.value = emptyList() }
  fun searchSubtitles(q: String, s: Int? = null, ep: Int? = null) { viewModelScope.launch { _isSearchingSub.value = true; wyzieRepository.search(q, s, ep).onSuccess { _wyzieSearchResults.value = it }; _isSearchingSub.value = false } }
  fun downloadSubtitle(s: WyzieSubtitle) { viewModelScope.launch { _isDownloadingSub.value = true; wyzieRepository.download(s, currentMediaTitle).onSuccess { addSubtitle(it) }; _isDownloadingSub.value = false } }
  fun toggleSubtitle(id: Int) { val pSid = MPVLib.getPropertyInt("sid") ?: 0; val sSid = MPVLib.getPropertyInt("secondary-sid") ?: 0; when { id == pSid -> MPVLib.setPropertyString("sid", "no"); id == sSid -> MPVLib.setPropertyString("secondary-sid", "no"); pSid <= 0 -> MPVLib.setPropertyInt("sid", id); sSid <= 0 -> MPVLib.setPropertyInt("secondary-sid", id); else -> MPVLib.setPropertyInt("sid", id) } }
  fun isSubtitleSelected(id: Int): Boolean { val p = MPVLib.getPropertyInt("sid") ?: 0; val s = MPVLib.getPropertyInt("secondary-sid") ?: 0; return (id == p && p > 0) || (id == s && s > 0) }

  private fun getFileNameFromUri(uri: Uri): String? = when (uri.scheme) { "content" -> host.context.contentResolver.query(uri, null, null, null, null)?.use { if (it.moveToFirst()) it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME).coerceAtLeast(0)) else null }; else -> uri.lastPathSegment }
  private fun isValidSubtitleFile(n: String): Boolean = n.substringAfterLast('.', "").lowercase() in VALID_SUBTITLE_EXTENSIONS

  fun getPlaylistData(): List<app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlaylistItem>? {
    val a = host as? PlayerActivity ?: return null
    if (a.playlist.isEmpty()) return null
    val cPos = pos ?: 0; val cDur = duration ?: 0
    val cProg = if (cDur > 0) ((cPos.toFloat() / cDur.toFloat()) * 100f).coerceIn(0f, 100f) else 0f
    return a.playlist.mapIndexed { idx, uri ->
      val (d, r, n) = synchronized(metadataCache) { metadataCache[uri.toString()] } ?: Triple("", "", false)
      app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlaylistItem(uri, a.getPlaylistItemTitle(uri), idx, idx == a.playlistIndex, cProg, idx == a.playlistIndex && cProg >= 95f, n, uri.toString(), d, r)
    }
  }
  
  // FIXED: Exact logic to generate mediaIdentifier from history database
  private fun getMediaIdentifierForUri(uri: Uri): String {
    val dummyIntent = Intent()
    if (uri.scheme == "file" || uri.scheme == "content") {
        dummyIntent.data = uri
    } else {
        dummyIntent.putExtra(Intent.EXTRA_TEXT, uri.toString())
        dummyIntent.type = "text/plain"
    }
    
    val fileName = getFileNameFromUri(uri)
    val parsedUri = dummyIntent.data ?: Uri.parse(dummyIntent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
    
    return when {
        parsedUri.scheme == "file" -> parsedUri.path ?: parsedUri.toString()
        parsedUri.scheme == "content" && fileName != null -> fileName
        else -> parsedUri.toString()
    }
  }

  private suspend fun checkIsNewVideo(uri: Uri): Boolean {
    if (!appearancePreferences.showUnplayedOldVideoLabel.get()) return false
    val threshold = appearancePreferences.unplayedOldVideoDays.get() * 24L * 60 * 60 * 1000L
    var date = 0L
    try {
      if (uri.scheme == "file") File(uri.path ?: "").let { if (it.exists()) date = it.lastModified() }
      else if (uri.scheme == "content") host.context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { if (it.moveToFirst()) date = it.getLong(0) * 1000L }
    } catch (e: Exception) {}
    if (date == 0L || (System.currentTimeMillis() - date) > threshold) return false
    val identifier = getMediaIdentifierForUri(uri)
    val history = playbackStateRepository.getVideoDataByTitle(identifier)
    return history == null || history.lastPosition <= 0
  }

  fun refreshPlaylistItems() { viewModelScope.launch(Dispatchers.IO) { getPlaylistData()?.let { _playlistItems.value = it; loadPlaylistMetadataAsync(it) } } }
  private fun loadPlaylistMetadataAsync(items: List<app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlaylistItem>) {
    viewModelScope.launch(Dispatchers.IO) {
      if ((host as? PlayerActivity)?.isCurrentPlaylistM3U() == true) return@launch
      items.chunked(5).forEach { batch ->
        val updates = mutableMapOf<String, Triple<String, String, Boolean>>()
        batch.forEach { item ->
          val key = item.uri.toString()
          if (metadataCache.get(key) == null) {
            val meta = getVideoMetadata(item.uri); val isN = checkIsNewVideo(item.uri)
            updateMetadataCache(key, Triple(meta.first, meta.second, isN)); updates[key] = Triple(meta.first, meta.second, isN)
          }
        }
        if (updates.isNotEmpty()) _playlistItems.value = _playlistItems.value.map { updates[it.uri.toString()]?.let { u -> it.copy(duration = u.first, resolution = u.second, isNew = u.third) } ?: it }
      }
    }
  }

  private fun getVideoMetadata(uri: Uri): Pair<String, String> {
    if (uri.scheme?.startsWith("http") == true) return "" to ""
    val retriever = android.media.MediaMetadataRetriever()
    return try {
      if (uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(host.context, uri)
      val dMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
      val dStr = if (dMs != null) { val s = dMs.toLong() / 1000; "${s / 60}:${(s % 60).toString().padStart(2, '0')}" } else ""
      val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
      val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
      dStr to if (w != null) "${w}x${h}" else ""
    } catch (e: Exception) { "" to "" } finally { retriever.release() }
  }

  fun showToast(m: String) { Toast.makeText(host.context, m, Toast.LENGTH_SHORT).show() }
}

fun Float.normalize(im: Float, iM: Float, om: Float, oM: Float): Float = (this - im) * (oM - om) / (iM - im) + om
fun <T> Flow<T>.collectAsState(scope: CoroutineScope, initialValue: T? = null) = object : ReadOnlyProperty<Any?, T?> {
  private var value: T? = initialValue
  init { scope.launch { collect { value = it } } }
  override fun getValue(thisRef: Any?, property: KProperty<*>) = value
}
