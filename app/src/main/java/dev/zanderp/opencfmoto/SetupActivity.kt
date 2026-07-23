// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Essentials settings — the handful of options most riders touch (display, power, auto-connect).
 * Everything else (per-dash tuning, handlebar timings, Wi‑Fi transport, log privacy, import/export)
 * lives one tap deeper in [AdvancedSettingsActivity]; the one-time prerequisites (install Android
 * Auto, permissions, head-unit mode, add a bike) moved into [OnboardingActivity]. Each selector
 * reflects its saved choice via a highlighted segment ([SegmentedControl]).
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var qualityDesc: TextView
    private lateinit var fitDesc: TextView
    private lateinit var powerDesc: TextView
    private lateinit var themeDesc: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.setup_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, b.top, v.paddingRight, b.bottom)
            insets
        }

        qualityDesc = findViewById(R.id.quality_desc)
        fitDesc = findViewById(R.id.fit_desc)
        powerDesc = findViewById(R.id.power_desc)
        themeDesc = findViewById(R.id.theme_desc)

        SegmentedControl.bind(this, VideoPrefs.get(this), { q ->
            VideoPrefs.set(this, q); refreshOptions(); toast("Video quality: ${q.label}")
        },
            R.id.quality_smooth to VideoQuality.SMOOTH,
            R.id.quality_balanced to VideoQuality.BALANCED,
            R.id.quality_sharp to VideoQuality.SHARP)

        SegmentedControl.bind(this, VideoPrefs.fit(this), { f ->
            VideoPrefs.setFit(this, f); refreshOptions(); toast("Screen fit: ${f.label}")
        },
            R.id.fit_fill to ScreenFit.FILL,
            R.id.fit_fit to ScreenFit.FIT,
            R.id.fit_stretch to ScreenFit.STRETCH)

        MapThemeControl.bind(this, R.id.theme_auto, R.id.theme_day, R.id.theme_night) { refreshOptions() }

        SegmentedControl.bind(this, VideoPrefs.power(this), { m ->
            VideoPrefs.setPower(this, m); refreshOptions(); toast("Power mode: ${m.label}")
        },
            R.id.power_auto to PowerMode.AUTO,
            R.id.power_smooth to PowerMode.SMOOTH,
            R.id.power_balanced to PowerMode.BALANCED,
            R.id.power_saver to PowerMode.SAVER)

        SegmentedControl.bind(this, AppSettings.autoConnect(this), { on ->
            AppSettings.setAutoConnect(this, on); refreshOptions()
            Toast.makeText(this, "Auto-connect ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
        },
            R.id.autoconnect_on to true,
            R.id.autoconnect_off to false)

        findViewById<android.view.View>(R.id.btn_advanced).setOnClickListener {
            AdvancedSettingsActivity.start(this)
        }
        findViewById<android.view.View>(R.id.btn_setup_guide).setOnClickListener {
            OnboardingActivity.start(this)
        }
        findViewById<android.view.View>(R.id.btn_supported_bikes).setOnClickListener {
            SupportedBikesActivity.start(this)
        }
        findViewById<android.view.View>(R.id.btn_about_settings).setOnClickListener {
            AboutActivity.start(this)
        }
        findViewById<android.view.View>(R.id.setup_done_btn).setOnClickListener {
            markSeen(this)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshOptions()
    }

    /** Update each selector's description + re-highlight the active segment. */
    private fun refreshOptions() {
        qualityDesc.text = VideoPrefs.get(this).label
        fitDesc.text = VideoPrefs.fit(this).label
        powerDesc.text = VideoPrefs.power(this).label
        themeDesc.text = NightPrefs.theme(this).label

        SegmentedControl.highlight(this, VideoPrefs.get(this),
            R.id.quality_smooth to VideoQuality.SMOOTH,
            R.id.quality_balanced to VideoQuality.BALANCED,
            R.id.quality_sharp to VideoQuality.SHARP)
        SegmentedControl.highlight(this, VideoPrefs.fit(this),
            R.id.fit_fill to ScreenFit.FILL,
            R.id.fit_fit to ScreenFit.FIT,
            R.id.fit_stretch to ScreenFit.STRETCH)
        MapThemeControl.highlight(this, R.id.theme_auto, R.id.theme_day, R.id.theme_night)
        SegmentedControl.highlight(this, VideoPrefs.power(this),
            R.id.power_auto to PowerMode.AUTO,
            R.id.power_smooth to PowerMode.SMOOTH,
            R.id.power_balanced to PowerMode.BALANCED,
            R.id.power_saver to PowerMode.SAVER)
        SegmentedControl.highlight(this, AppSettings.autoConnect(this),
            R.id.autoconnect_on to true,
            R.id.autoconnect_off to false)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, "$msg (applies next connect)", Toast.LENGTH_SHORT).show()

    companion object {
        /** First-run completion flag now lives in [AppFlags] (shared with [OnboardingActivity]). */
        fun hasSeen(ctx: android.content.Context): Boolean = AppFlags.onboardingSeen(ctx)

        fun markSeen(ctx: android.content.Context) = AppFlags.markOnboardingSeen(ctx)

        fun start(ctx: android.content.Context) {
            ctx.startActivity(Intent(ctx, SetupActivity::class.java))
        }
    }
}
