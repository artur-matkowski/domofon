package pl.bitforge.domofon.config

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import pl.bitforge.domofon.R
import pl.bitforge.domofon.geo.GeofenceManager

/**
 * The settings screen. Everything the app needs to reach a gate is entered here and
 * nowhere else — there is no build-time configuration left.
 *
 * It is also where background location is requested, because this is where the feature
 * that needs it is switched on. Asking on first launch instead would be asking for the
 * most intrusive permission in the app before the user has expressed any interest in the
 * feature — rude, and a documented Play rejection reason.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Settings changes are only worth anything once they reach Play Services. */
    override fun onStop() {
        GeofenceManager.sync(this)
        super.onStop()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Step one: the prominent disclosure.
     *
     * Play requires this dialog *before* the runtime prompt, naming the data, stating that
     * access happens in the background, and naming the feature that uses it. Showing it
     * afterwards — or not at all — is on Google's published list of rejection reasons.
     */
    fun startLocationFlow() {
        if (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.location_rationale_title)
            .setMessage(R.string.location_rationale)
            .setNegativeButton(R.string.location_rationale_cancel, null)
            .setPositiveButton(R.string.location_rationale_continue) { _, _ ->
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) requestBackground()
                else requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQ_FOREGROUND,
                )
            }
            .show()
    }

    /**
     * Step two, only once foreground location is already granted. The two must not be
     * requested together: Android denies a combined request outright, which is the single
     * most common way geofencing silently never works.
     */
    private fun requestBackground() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_FOREGROUND -> requestBackground()
            REQ_BACKGROUND ->
                if (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                    GeofenceManager.sync(this)
                } else {
                    // Android 11+ will not grant "Allow all the time" from a dialog at all;
                    // system Settings is the only place it can be turned on. Offer it —
                    // never launch it unasked.
                    AlertDialog.Builder(this)
                        .setMessage(R.string.location_needs_all_the_time)
                        .setNegativeButton(R.string.location_rationale_cancel, null)
                        .setPositiveButton(R.string.location_rationale_continue) { _, _ ->
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", packageName, null),
                                )
                            )
                        }
                        .show()
                }
        }
    }

    private companion object {
        const val REQ_FOREGROUND = 1
        const val REQ_BACKGROUND = 2
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Must precede inflation: the framework resolves each preference's persisted
            // value through the data store as it builds the screen, so setting this
            // afterwards would let the first read hit default SharedPreferences instead.
            preferenceManager.preferenceDataStore = ConfigStore
            setPreferencesFromResource(R.xml.preferences, rootKey)

            numeric(ConfigStore.K_PORT)
            numeric(ConfigStore.K_NODE_ID)
            signedDecimal(ConfigStore.K_LAT)
            signedDecimal(ConfigStore.K_LON)
            numeric(ConfigStore.K_RADIUS)

            masked(ConfigStore.K_PASS)
            masked(ConfigStore.K_RTSP_PASS)

            uri(ConfigStore.K_RTSP_URL)

            // Switching the geofence on is the user asking for the feature — and the only
            // moment at which asking for background location is justified.
            findPreference<SwitchPreferenceCompat>(ConfigStore.K_GEOFENCE)
                ?.setOnPreferenceChangeListener { _, enabled ->
                    if (enabled == true) (activity as? SettingsActivity)?.startLocationFlow()
                    true
                }
        }

        private fun edit(key: String): EditTextPreference? = findPreference(key)

        private fun numeric(key: String) = edit(key)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
        }

        private fun signedDecimal(key: String) = edit(key)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
        }

        private fun uri(key: String) = edit(key)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        /**
         * Password fields: masked while typing, and never rendered into the summary line —
         * a settings screen that prints the broker password in plain sight on a list row
         * defeats the point of encrypting it on disk.
         */
        private fun masked(key: String) {
            val pref = edit(key) ?: return
            pref.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                if (it.text.isNullOrEmpty()) getString(R.string.pref_password_unset)
                else getString(R.string.pref_password_set)
            }
        }
    }
}
