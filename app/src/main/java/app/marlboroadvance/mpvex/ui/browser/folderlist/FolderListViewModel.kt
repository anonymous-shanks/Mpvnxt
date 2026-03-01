package app.marlboroadvance.mpvex.ui.browser.folderlist

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.marlboroadvance.mpvex.domain.media.model.Video
import app.marlboroadvance.mpvex.domain.media.model.VideoFolder
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.repository.MediaFileRepository
import app.marlboroadvance.mpvex.utils.history.RecentlyPlayedOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

data class FolderWithNewCount(
  val folder: VideoFolder,
  val newVideoCount: Int
)

class FolderListViewModel(
  application: Application
) : AndroidViewModel(application), KoinComponent {

  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val appearancePreferences: AppearancePreferences by inject()

  private val _videoFolders = MutableStateFlow<List<VideoFolder>>(emptyList())
  val videoFolders: StateFlow<List<VideoFolder>> = _videoFolders.asStateFlow()

  private val _foldersWithNewCount = MutableStateFlow<List<FolderWithNewCount>>(emptyList())
  val foldersWithNewCount: StateFlow<List<FolderWithNewCount>> = _foldersWithNewCount.asStateFlow()

  private val _recentlyPlayedFilePath = MutableStateFlow<String?>(null)
  val recentlyPlayedFilePath: StateFlow<String?> = _recentlyPlayedFilePath.asStateFlow()

  private val _isLoading = MutableStateFlow(true)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _scanStatus = MutableStateFlow<String?>(null)
  val scanStatus: StateFlow<String?> = _scanStatus.asStateFlow()

  private val _hasCompletedInitialLoad = MutableStateFlow(false)
  val hasCompletedInitialLoad: StateFlow<Boolean> = _hasCompletedInitialLoad.asStateFlow()

  private val _foldersWereDeleted = MutableStateFlow(false)
  val foldersWereDeleted: StateFlow<Boolean> = _foldersWereDeleted.asStateFlow()

  private var refreshJob: Job? = null

  init {
    refresh()
  }

  fun refresh() {
    refreshJob?.cancel()
    refreshJob = viewModelScope.launch(Dispatchers.IO) {
      if (_videoFolders.value.isEmpty()) {
        _isLoading.value = true
        _scanStatus.value = "Scanning folders..."
      }
      _foldersWereDeleted.value = false

      try {
        val context = getApplication<Application>().applicationContext
        
        // 1. Fetch folders (Super fast)
        val folders = MediaFileRepository.getAllVideoFoldersFast(context)
        _videoFolders.value = folders
        _hasCompletedInitialLoad.value = true

        // ** CRITICAL FIX: Turant UI ko unblock karo, taaki app baar-baar scan karta hua na dikhe **
        _isLoading.value = false
        _scanStatus.value = null

        // 2. Fetch recently played
        val recent = RecentlyPlayedOps.getRecentlyPlayed(1).firstOrNull()
        _recentlyPlayedFilePath.value = recent?.filePath

        // 3. Calculate "NEW" counts completely in background
        calculateNewVideoCounts(folders)

      } catch (e: Exception) {
        Log.e("FolderListViewModel", "Error fetching folders", e)
        _isLoading.value = false
        _scanStatus.value = null
      }
    }
  }

  fun recalculateNewVideoCounts() {
    viewModelScope.launch(Dispatchers.IO) {
      val recent = RecentlyPlayedOps.getRecentlyPlayed(1).firstOrNull()
      _recentlyPlayedFilePath.value = recent?.filePath
      
      if (_videoFolders.value.isNotEmpty()) {
        calculateNewVideoCounts(_videoFolders.value)
      }
    }
  }

  private suspend fun calculateNewVideoCounts(folders: List<VideoFolder>) {
    if (!appearancePreferences.showUnplayedOldVideoLabel.get()) {
      _foldersWithNewCount.value = emptyList()
      return
    }

    val thresholdDays = appearancePreferences.unplayedOldVideoDays.get()
    val thresholdMillis = thresholdDays * 24L * 60 * 60 * 1000L
    val currentTime = System.currentTimeMillis()
    val context = getApplication<Application>().applicationContext

    val resultList = mutableListOf<FolderWithNewCount>()

    for (folder in folders) {
      var newCount = 0
      try {
        val videos = MediaFileRepository.getVideosInFolder(context, folder.bucketId)
        for (video in videos) {
          if (checkIsNewVideo(video, thresholdMillis, currentTime)) {
            newCount++
          }
        }
      } catch (e: Exception) {
        Log.e("FolderListViewModel", "Error getting videos for folder ${folder.name}", e)
      }
      resultList.add(FolderWithNewCount(folder, newCount))
    }

    _foldersWithNewCount.value = resultList
  }

  private suspend fun checkIsNewVideo(video: Video, thresholdMillis: Long, currentTime: Long): Boolean {
    val dateAddedMillis = maxOf(video.dateAdded, video.dateModified) * 1000L
    if (dateAddedMillis == 0L || (currentTime - dateAddedMillis) > thresholdMillis) {
      return false
    }

    val possibleIdentifiers = mutableSetOf<String>()

    val dummyIntent = Intent()
    dummyIntent.data = video.uri
    val parsedUri = dummyIntent.data ?: Uri.parse(dummyIntent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
    val intentIdentifier = when {
        parsedUri.scheme == "file" -> parsedUri.path ?: parsedUri.toString()
        parsedUri.scheme == "content" && video.displayName.isNotBlank() -> video.displayName
        else -> parsedUri.toString()
    }
    possibleIdentifiers.add(intentIdentifier)
    possibleIdentifiers.add(video.uri.toString())

    if (video.path.isNotBlank()) {
        possibleIdentifiers.add(video.path)
    }

    if (video.displayName.isNotBlank()) {
        possibleIdentifiers.add(video.displayName)
    }

    for (identifier in possibleIdentifiers) {
        if (identifier.isBlank()) continue
        val history = playbackStateRepository.getVideoDataByTitle(identifier)
        if (history != null && history.lastPosition > 0) {
            return false
        }
    }
    
    return true
  }

  fun deleteVideos(videos: List<Video>) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val context = getApplication<Application>().applicationContext
        for (video in videos) {
          try {
            val deletedRows = context.contentResolver.delete(video.uri, null, null)
            if (deletedRows == 0 && video.path.isNotBlank()) {
              val file = File(video.path)
              if (file.exists()) {
                file.delete()
              }
            }
          } catch (e: Exception) {
            Log.e("FolderListViewModel", "Error deleting video", e)
          }
        }
        _foldersWereDeleted.value = true
        refresh()
      } catch (e: Exception) {
        Log.e("FolderListViewModel", "Error in deleteVideos", e)
      }
    }
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return FolderListViewModel(application) as T
      }
    }
  }
}
