package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.preferences.preference.AndroidPreference
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore

class FoldersPreferences(store: PreferenceStore) {
    val defaultFolder = store.getString("default_folder", "")
    
    // Restore kiya gaya Hidden Files wala feature
    val showHiddenFiles = store.getBoolean("show_hidden_files", false)
    
    // Tumhara custom Pin Folders wala feature
    val pinnedFolders = store.getStringSet("pinned_folders", emptySet())
}
