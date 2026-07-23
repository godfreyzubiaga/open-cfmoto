// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * First-run wizard. Consolidates what used to be scattered across the app: the just-in-time permission
 * requests and the old Setup "Get started" checklist (install Android Auto, head-unit/developer mode,
 * add a bike). A returning user (or an upgrade install that already finished the old Setup) never sees
 * this — [MainActivity] only launches it when [AppFlags.onboardingSeen] is false, and completion is
 * tracked with the same shared flag so it shows exactly once.
 *
 * Kept deliberately simple: a [ViewFlipper] of five steps driven by an integer index — no Fragments,
 * matching the rest of the app's plain-Activity style.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var flipper: ViewFlipper
    private lateinit var progress: LinearProgressIndicator
    private lateinit var backBtn: MaterialButton
    private lateinit var skipBtn: MaterialButton
    private lateinit var nextBtn: MaterialButton

    private val stepCount get() = flipper.childCount
    private val step get() = flipper.displayedChild

    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshStep() }

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        if (result.resultCode != RESULT_OK || raw == null) return@registerForActivityResult
        val qr = QrData.parse(raw)
        if (qr == null) {
            Toast.makeText(this, "That QR wasn't a bike pairing code", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        BikeMemory.save(this, raw, qr)
        refreshStep()
        Toast.makeText(this, "Bike added", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.onboarding_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, b.top, v.paddingRight, b.bottom)
            insets
        }

        flipper = findViewById(R.id.ob_flipper)
        progress = findViewById(R.id.ob_progress)
        backBtn = findViewById(R.id.ob_back)
        skipBtn = findViewById(R.id.ob_skip)
        nextBtn = findViewById(R.id.ob_next)
        progress.max = 100

        backBtn.setOnClickListener { if (step > 0) show(step - 1) }
        skipBtn.setOnClickListener { finishOnboarding() }
        nextBtn.setOnClickListener { if (step < stepCount - 1) show(step + 1) else finishOnboarding() }

        findViewById<MaterialButton>(R.id.ob_perm_btn).setOnClickListener {
            val missing = SetupHelper.missingConnectPermissions(this)
            if (missing.isEmpty()) { refreshStep(); return@setOnClickListener }
            permsLauncher.launch(missing.toTypedArray())
        }
        findViewById<MaterialButton>(R.id.ob_aa_btn).setOnClickListener {
            if (!SetupHelper.openAndroidAutoOrStore(this)) {
                Toast.makeText(this, "Couldn't open the Play Store", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<MaterialButton>(R.id.ob_aa_settings_btn).setOnClickListener {
            if (!SetupHelper.openAndroidAutoSettings(this)) {
                Toast.makeText(this, "Couldn't open Android Auto settings", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<MaterialButton>(R.id.ob_scan_btn).setOnClickListener {
            try {
                scanLauncher.launch(Intent(this, QrScanActivity::class.java))
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open the scanner: $e", Toast.LENGTH_SHORT).show()
            }
        }

        show(0)
    }

    override fun onResume() {
        super.onResume()
        // Returning from the Play Store, a permission prompt, or Android Auto settings should re-tick.
        refreshStep()
    }

    private fun show(index: Int) {
        flipper.displayedChild = index.coerceIn(0, stepCount - 1)
        refreshStep()
    }

    /** Update the nav bar + per-step live status for the currently shown step. */
    private fun refreshStep() {
        val s = step
        progress.setProgressCompat((s + 1) * 100 / stepCount, true)
        backBtn.visibility = if (s == 0) android.view.View.INVISIBLE else android.view.View.VISIBLE
        val last = s == stepCount - 1
        skipBtn.visibility = if (last) android.view.View.INVISIBLE else android.view.View.VISIBLE
        nextBtn.text = if (last) "Start riding" else "Next"

        when (s) {
            1 -> {
                val granted = SetupHelper.missingConnectPermissions(this).isEmpty()
                setStatus(R.id.ob_perm_status,
                    if (granted) "✓ Permissions granted" else "Not granted yet",
                    granted)
                findViewById<MaterialButton>(R.id.ob_perm_btn).apply {
                    text = if (granted) "All set" else "Grant permissions"
                    isEnabled = !granted
                }
            }
            2 -> {
                val installed = SetupHelper.isAndroidAutoInstalled(this)
                setStatus(R.id.ob_aa_status,
                    if (installed) "✓ Android Auto is installed" else "Android Auto isn't installed yet",
                    installed)
                findViewById<MaterialButton>(R.id.ob_aa_btn).text =
                    if (installed) "Open Android Auto" else "Install Android Auto"
            }
            3 -> {
                val has = BikeMemory.hasSaved(this)
                val name = BikeMemory.lastBikeName(this)
                setStatus(R.id.ob_bike_status,
                    if (has) "✓ Added ${name ?: "your bike"}" else "No bike added yet",
                    has)
                findViewById<MaterialButton>(R.id.ob_scan_btn).text =
                    if (has) "Scan another bike" else "Scan dash QR"
            }
        }
    }

    private fun setStatus(id: Int, text: String, ok: Boolean) {
        findViewById<TextView>(id).apply {
            this.text = text
            setTextColor(androidx.core.content.ContextCompat.getColor(
                this@OnboardingActivity, if (ok) R.color.status_live else R.color.status_busy))
        }
    }

    private fun finishOnboarding() {
        AppFlags.markOnboardingSeen(this)
        finish()
    }

    override fun onBackPressed() {
        if (step > 0) show(step - 1) else super.onBackPressed()
    }

    companion object {
        fun start(ctx: android.content.Context) {
            ctx.startActivity(Intent(ctx, OnboardingActivity::class.java))
        }
    }
}
