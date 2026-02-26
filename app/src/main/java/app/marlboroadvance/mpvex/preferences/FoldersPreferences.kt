package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore

/**
 * Preferences for folder management
 */
class FoldersPreferences(
  preferenceStore: PreferenceStore,
) {
  // Set of folder paths that should be hidden from the folder list
  val blacklistedFolders = preferenceStore.getStringSet("blacklisted_folders", emptySet())

  // Ordered string of pinned folder paths (separated by |) to preserve user arrangement
  val pinnedFoldersOrder = preferenceStore.getString("pinned_folders_order", "")
}
