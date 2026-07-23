// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * One-shot app flags shared between the first-run [OnboardingActivity] and [MainActivity].
 *
 * The "onboarding seen" flag deliberately reuses the exact prefs key that the old Setup wizard used
 * ([PREFS] / [KEY_SEEN]) so that an upgrade install — where the user already completed Setup — is NOT
 * dropped back into onboarding. [SetupActivity.hasSeen]/[SetupActivity.markSeen] delegate here.
 */
object AppFlags {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_SEEN = "setup_seen"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True once the user has finished (or skipped) the first-run wizard. */
    fun onboardingSeen(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SEEN, false)

    fun markOnboardingSeen(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_SEEN, true).apply()
    }
}
