package pl.bitforge.domofon.config

import androidx.preference.PreferenceDataStore

/**
 * The seam `androidx.preference` offers for putting a custom backend behind a preference
 * screen: SettingsActivity gets to be a plain XML `PreferenceFragmentCompat` while
 * passwords are still transparently encrypted on the way in and out. A thin adapter, so
 * [ConfigStore] itself no longer has to *be* a `PreferenceDataStore`.
 */
class ConfigPreferenceDataStore(private val store: ConfigStore) : PreferenceDataStore() {

    override fun putString(key: String, value: String?) = store.putString(key, value)

    override fun getString(key: String, defValue: String?): String? = store.getString(key, defValue)

    override fun putBoolean(key: String, value: Boolean) = store.putBoolean(key, value)

    override fun getBoolean(key: String, defValue: Boolean): Boolean = store.getBoolean(key, defValue)
}
