// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * The deep end of settings — per-dash tuning and networking split out of [SetupActivity] so the
 * everyday screen stays short. Nothing here is new behavior; every option calls the same prefs
 * setters the old single Setup screen did, so persistence and "applies next connect" semantics are
 * unchanged. Selection highlighting is shared via [SegmentedControl].
 */
class AdvancedSettingsActivity : AppCompatActivity() {

    private lateinit var resDesc: TextView
    private lateinit var nonTouchDesc: TextView
    private lateinit var dblTapDesc: TextView
    private lateinit var holdDesc: TextView
    private lateinit var profileDesc: TextView
    private lateinit var btStatus: TextView
    private lateinit var resumeBtn: MaterialButton

    private val importSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) importSettingsFromUri(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adv_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, b.top, v.paddingRight, b.bottom)
            insets
        }

        resDesc = findViewById(R.id.res_desc)
        nonTouchDesc = findViewById(R.id.nontouch_desc)
        dblTapDesc = findViewById(R.id.dbltap_desc)
        holdDesc = findViewById(R.id.hold_desc)
        profileDesc = findViewById(R.id.profile_desc)
        btStatus = findViewById(R.id.bt_status)
        resumeBtn = findViewById(R.id.resume_perm_btn)

        SegmentedControl.bind(this, VideoPrefs.resolution(this), { m ->
            VideoPrefs.setResolution(this, m); refreshOptions(); toast("Resolution: ${m.label}")
        },
            R.id.res_auto to ResolutionMode.AUTO,
            R.id.res_land_sd to ResolutionMode.LANDSCAPE_SD,
            R.id.res_land_hd to ResolutionMode.LANDSCAPE_HD,
            R.id.res_port_sd to ResolutionMode.PORTRAIT_SD,
            R.id.res_port_hd to ResolutionMode.PORTRAIT_HD)

        SegmentedControl.bind(this, AppSettings.forceNonTouch(this), { on ->
            AppSettings.setForceNonTouch(this, on); refreshOptions()
            toast("Disable touchscreen: ${if (on) "on" else "off"}")
        },
            R.id.nontouch_on to true,
            R.id.nontouch_off to false)

        SegmentedControl.bind(this, ButtonTimingPrefs.doubleTap(this), { d ->
            ButtonTimingPrefs.setDoubleTap(this, d); refreshOptions()
            Toast.makeText(this, "Double-tap delay: ${d.label}", Toast.LENGTH_SHORT).show()
        },
            R.id.dbltap_fast to DoubleTapDelay.FAST,
            R.id.dbltap_normal to DoubleTapDelay.NORMAL,
            R.id.dbltap_slow to DoubleTapDelay.SLOW)

        SegmentedControl.bind(this, ButtonTimingPrefs.longPress(this), { d ->
            ButtonTimingPrefs.setLongPress(this, d); refreshOptions()
            Toast.makeText(this, "Select hold delay: ${d.label}", Toast.LENGTH_SHORT).show()
        },
            R.id.hold_short to LongPressDelay.SHORT,
            R.id.hold_normal to LongPressDelay.NORMAL,
            R.id.hold_long to LongPressDelay.LONG)

        SegmentedControl.bind(this, ProfilePrefs.get(this), { ov ->
            ProfilePrefs.set(this, ov); refreshOptions(); toast("Bike profile: ${ov.shortLabel}")
        },
            R.id.profile_auto to ProfileOverride.AUTO,
            R.id.profile_legacy to ProfileOverride.LEGACY,
            R.id.profile_nk800 to ProfileOverride.NK800,
            R.id.profile_800mt to ProfileOverride.CFDL26_LAND,
            R.id.profile_1000mtx to ProfileOverride.CFDL26_PORT,
            R.id.profile_nk_adv to ProfileOverride.NK_ADV,
            R.id.profile_clc450 to ProfileOverride.CLC450)

        SegmentedControl.bind(this, AppSettings.autoRecovery(this), { on ->
            AppSettings.setAutoRecovery(this, on); refreshOptions()
            Toast.makeText(this, "Auto-recovery ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
        },
            R.id.recovery_on to true,
            R.id.recovery_off to false)

        SegmentedControl.bind(this, AppSettings.logTrips(this), { on ->
            AppSettings.setLogTrips(this, on); refreshOptions()
            Toast.makeText(this, "Trip logging ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
        },
            R.id.logtrips_on to true,
            R.id.logtrips_off to false)

        SegmentedControl.bind(this, AppSettings.transport(this), { t ->
            AppSettings.setTransport(this, t); refreshOptions(); toast("Wi‑Fi transport: ${t.label}")
        },
            R.id.transport_auto to WifiTransport.AUTO,
            R.id.transport_ap to WifiTransport.AP,
            R.id.transport_p2p to WifiTransport.P2P)

        SegmentedControl.bind(this, AppSettings.includeSecretsInLogs(this), { on ->
            AppSettings.setIncludeSecretsInLogs(this, on); refreshOptions()
            Toast.makeText(this,
                if (on) "Shared logs will include secrets — turn off before posting publicly"
                else "Log redaction on",
                Toast.LENGTH_SHORT).show()
        },
            R.id.secrets_on to true,
            R.id.secrets_off to false)

        findViewById<MaterialButton>(R.id.btn_screen_margins).setOnClickListener { ScreenMarginsActivity.start(this) }
        findViewById<MaterialButton>(R.id.btn_custom_resolution).setOnClickListener { CustomResolutionActivity.start(this) }
        findViewById<MaterialButton>(R.id.settings_share).setOnClickListener { shareSettingsJson() }
        findViewById<MaterialButton>(R.id.settings_import).setOnClickListener {
            importSettingsLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        findViewById<MaterialButton>(R.id.bt_settings_btn).setOnClickListener { BluetoothHelper.openBluetoothSettings(this) }
        resumeBtn.setOnClickListener { requestOverlayPermission() }
    }

    override fun onResume() {
        super.onResume()
        refreshOptions()
        refreshBluetooth()
        refreshResume()
    }

    private fun refreshOptions() {
        resDesc.text = VideoPrefs.resolution(this).label
        nonTouchDesc.text = if (AppSettings.forceNonTouch(this))
            "On — focus/knob UI so handlebar buttons work"
        else
            "Off — use the bike profile (touch dashes stay touch)"
        dblTapDesc.text = ButtonTimingPrefs.doubleTap(this).label
        holdDesc.text = ButtonTimingPrefs.longPress(this).label
        val pov = ProfilePrefs.get(this)
        profileDesc.text = "${pov.shortLabel} — ${pov.detail}"
        findViewById<TextView>(R.id.transport_desc).text = AppSettings.transport(this).label
        findViewById<TextView>(R.id.secrets_desc).text =
            if (AppSettings.includeSecretsInLogs(this)) "On — passwords/serials stay in shared logs"
            else "Off — passwords and serials are redacted (recommended)"

        SegmentedControl.highlight(this, VideoPrefs.resolution(this),
            R.id.res_auto to ResolutionMode.AUTO,
            R.id.res_land_sd to ResolutionMode.LANDSCAPE_SD,
            R.id.res_land_hd to ResolutionMode.LANDSCAPE_HD,
            R.id.res_port_sd to ResolutionMode.PORTRAIT_SD,
            R.id.res_port_hd to ResolutionMode.PORTRAIT_HD)
        SegmentedControl.highlight(this, AppSettings.forceNonTouch(this),
            R.id.nontouch_on to true, R.id.nontouch_off to false)
        SegmentedControl.highlight(this, ButtonTimingPrefs.doubleTap(this),
            R.id.dbltap_fast to DoubleTapDelay.FAST,
            R.id.dbltap_normal to DoubleTapDelay.NORMAL,
            R.id.dbltap_slow to DoubleTapDelay.SLOW)
        SegmentedControl.highlight(this, ButtonTimingPrefs.longPress(this),
            R.id.hold_short to LongPressDelay.SHORT,
            R.id.hold_normal to LongPressDelay.NORMAL,
            R.id.hold_long to LongPressDelay.LONG)
        SegmentedControl.highlight(this, ProfilePrefs.get(this),
            R.id.profile_auto to ProfileOverride.AUTO,
            R.id.profile_legacy to ProfileOverride.LEGACY,
            R.id.profile_nk800 to ProfileOverride.NK800,
            R.id.profile_800mt to ProfileOverride.CFDL26_LAND,
            R.id.profile_1000mtx to ProfileOverride.CFDL26_PORT,
            R.id.profile_nk_adv to ProfileOverride.NK_ADV,
            R.id.profile_clc450 to ProfileOverride.CLC450)
        SegmentedControl.highlight(this, AppSettings.autoRecovery(this),
            R.id.recovery_on to true, R.id.recovery_off to false)
        SegmentedControl.highlight(this, AppSettings.logTrips(this),
            R.id.logtrips_on to true, R.id.logtrips_off to false)
        SegmentedControl.highlight(this, AppSettings.transport(this),
            R.id.transport_auto to WifiTransport.AUTO,
            R.id.transport_ap to WifiTransport.AP,
            R.id.transport_p2p to WifiTransport.P2P)
        SegmentedControl.highlight(this, AppSettings.includeSecretsInLogs(this),
            R.id.secrets_on to true, R.id.secrets_off to false)
    }

    private fun refreshBluetooth() {
        btStatus.text = BluetoothHelper.status(this).describe()
    }

    private fun refreshResume() {
        val ok = SetupHelper.canAutoResume(this)
        resumeBtn.text = if (ok) "✓ Seamless resume enabled" else "Enable seamless resume"
        resumeBtn.isEnabled = !ok
    }

    /** Deep-link to the "Display over other apps" screen (overlay = seamless auto-resume). */
    private fun requestOverlayPermission() {
        if (SetupHelper.canAutoResume(this)) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", packageName, null)))
            Toast.makeText(this, "Turn on “Display over other apps” for seamless resume",
                Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't open the overlay permission screen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Write the portable settings JSON and open the system share sheet (Discord, Drive, …). */
    private fun shareSettingsJson() {
        try {
            val json = SettingsBackup.exportJson(this)
            val dir = File(cacheDir, "settings").apply { mkdirs() }
            val file = File(dir, SettingsBackup.suggestedFileName(this))
            file.writeText(json, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val profile = ProfilePrefs.get(this).shortLabel
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, "OpenCfMoto bike tuning — $profile")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "OpenCfMoto bike tuning ($profile) — profile / resolution / margins / buttons " +
                        "(no passwords, no bike name/SSID).\n" +
                        "Import via Settings → Advanced → Import…",
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
            }
            startActivity(Intent.createChooser(send, "Share settings JSON"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: $e", Toast.LENGTH_LONG).show()
        }
    }

    private fun importSettingsFromUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            if (text.isNullOrBlank()) {
                Toast.makeText(this, "Empty file", Toast.LENGTH_SHORT).show()
                return
            }
            val onto = BikeMemory.lastBikeName(this) ?: "the selected bike"
            AlertDialog.Builder(this)
                .setTitle("Import settings?")
                .setMessage(
                    "Replace bike tuning for $onto — profile, resolution, fit, power, margins, " +
                        "handlebar buttons, Control AA, non-touch, Wi‑Fi transport?\n\n" +
                        "Personal prefs (map theme, saved places, auto-connect, …) are left alone. " +
                        "Wi‑Fi passwords are never imported."
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Import") { _, _ ->
                    val result = SettingsBackup.importJson(this, text)
                    refreshOptions()
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: $e", Toast.LENGTH_LONG).show()
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, "$msg (applies next connect)", Toast.LENGTH_SHORT).show()

    companion object {
        fun start(ctx: android.content.Context) {
            ctx.startActivity(Intent(ctx, AdvancedSettingsActivity::class.java))
        }
    }
}
