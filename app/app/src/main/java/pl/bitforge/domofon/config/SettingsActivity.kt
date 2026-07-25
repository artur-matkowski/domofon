package pl.bitforge.domofon.config

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import pl.bitforge.domofon.R
import pl.bitforge.domofon.domain.ConnectionState
import pl.bitforge.domofon.domain.ConnectionStatus
import pl.bitforge.domofon.gate.GateRepository
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

        val toolbar = findViewById<Toolbar>(R.id.settings_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // targetSdk 36 enforces edge-to-edge. The toolbar absorbs the top inset (its
        // background extends under the status bar), the list absorbs the bottom one so the
        // last preference clears the navigation bar.
        val container = findViewById<View>(R.id.settings_container)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            toolbar.setPadding(bars.left, bars.top, bars.right, 0)
            container.setPadding(bars.left, 0, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

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

    /**
     * The settings screen holds the broker connection open while it is on screen, so the
     * status row can report what the credentials being typed actually do. This is what the
     * owner count in [GateRepository] is for — MainActivity has already let go by the time
     * we get here, and taking a slot of our own costs nothing when it has not.
     */
    override fun onStart() {
        super.onStart()
        GateRepository.connect()
    }

    /** Settings changes are only worth anything once they reach Play Services. */
    override fun onStop() {
        GateRepository.disconnect()
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

            plainText(ConfigStore.K_HOST)
            plainText(ConfigStore.K_USER)
            numeric(ConfigStore.K_PORT)
            numeric(ConfigStore.K_NODE_ID)
            numeric(ConfigStore.K_KEEP_ALIVE)
            numeric(ConfigStore.K_SNAPSHOT_SECS)
            signedDecimal(ConfigStore.K_LAT)
            signedDecimal(ConfigStore.K_LON)
            numeric(ConfigStore.K_RADIUS)

            masked(ConfigStore.K_PASS)

            credentialUrl(ConfigStore.K_SNAPSHOT_URL, R.string.pref_snapshot_url_summary)
            credentialUrl(ConfigStore.K_RTSP_URL, R.string.pref_rtsp_url_summary)

            // Switching the geofence on is the user asking for the feature — and the only
            // moment at which asking for background location is justified.
            findPreference<SwitchPreferenceCompat>(ConfigStore.K_GEOFENCE)
                ?.setOnPreferenceChangeListener { _, enabled ->
                    if (enabled == true) (activity as? SettingsActivity)?.startLocationFlow()
                    true
                }
        }

        /**
         * Not [onCreatePreferences]: that runs from `onCreate`, before there is a view, and
         * `viewLifecycleOwner` throws there — which crashed the whole screen on open.
         */
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            connectionStatusRow()
            versionRow()
        }

        /**
         * Build identity, read from this APK's own [android.content.pm.PackageInfo] rather
         * than a compiled-in string — so it is by definition whatever was stamped into the
         * installed artifact and can never lag behind it. Both fields come from the manifest,
         * which the build fills from git (see build.gradle.kts): the release script's clean
         * semver as versionName, the commit count as versionCode. A static value, so unlike
         * [connectionStatusRow] it needs no collector.
         */
        private fun versionRow() {
            val row = findPreference<Preference>(KEY_VERSION) ?: return
            val ctx = requireContext()
            val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION") pkg.versionCode.toLong()
            }
            row.summary = "${pkg.versionName} ($code)"
        }

        /**
         * The live readout at the top of the broker category — the reason this screen holds
         * the connection open at all.
         *
         * Two collectors, because they answer different questions. The connection flow says
         * what the broker did with these credentials; [ConfigStore.config] says the user
         * just changed them, which is the cue to throw the old connection away and try the
         * new ones. Both are scoped to `viewLifecycleOwner`, so they stop with the view.
         */
        private fun connectionStatusRow() {
            val row = findPreference<Preference>(KEY_STATUS) ?: return
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        GateRepository.connection.collect { row.summary = describe(it) }
                    }
                    launch {
                        // drop(1): the current value is what we already connected with.
                        ConfigStore.config.drop(1).collect { GateRepository.refresh() }
                    }
                }
            }
        }

        private fun describe(state: ConnectionState): String = when (state.status) {
            ConnectionStatus.CONNECTED -> getString(R.string.status_connected)
            ConnectionStatus.CONNECTING -> getString(R.string.status_connecting)
            ConnectionStatus.DISCONNECTED -> getString(R.string.status_disconnected)
            // The reason is the whole point of these two; it is already user-facing prose
            // and already free of the host and credentials.
            ConnectionStatus.DEGRADED, ConnectionStatus.FAILED -> state.reason
        }

        private fun edit(key: String): EditTextPreference? = findPreference(key)

        private fun numeric(key: String) = edit(key)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
        }

        /**
         * Identifiers typed verbatim — the broker host and username.
         *
         * Without this they get the default text input, which means auto-capitalisation and
         * autocorrect: `mqtt-user` is offered back as `Mqtt-user`, and a tap on a suggestion
         * appends a space. Both are invisible in the summary row and both make the broker
         * reject credentials that work everywhere else. [ConfigStore] trims as a second
         * line of defence; this stops the mangling at the source, where the capital letter
         * that trimming cannot undo is introduced.
         */
        private fun plainText(key: String) = edit(key)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }

        private fun signedDecimal(key: String) = edit(key)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
        }

        /**
         * Both camera URLs carry the credentials inline (`scheme://user:pass@host/…`), so
         * they get URI input while typing but a summary that strips the userinfo — the row
         * must not print the password, same as [masked] fields. Fully masking the input
         * would make a long URL uneditable, and the value is Keystore-encrypted at rest
         * like the passwords are (see ConfigStore.SECRET_KEYS).
         *
         * @param emptySummary what to say when nothing is set — the example URL, which is
         *   the only hint the user gets about what shape of address this field wants.
         */
        private fun credentialUrl(key: String, emptySummary: Int) {
            val pref = edit(key) ?: return
            pref.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                if (it.text.isNullOrBlank()) getString(emptySummary)
                else it.text!!.replace(USERINFO, "//")
            }
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

        private companion object {
            /**
             * `//user:pass@` in a URL. Greedy `[^/]*` runs to the *last* `@` before the
             * path, so a password containing `@` is still stripped in full.
             */
            val USERINFO = Regex("//[^/]*@")

            /** Matches the app:key in preferences.xml; holds no value, so not a ConfigStore key. */
            const val KEY_STATUS = "status.connection"
            const val KEY_VERSION = "about.version"
        }
    }
}
