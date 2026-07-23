// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.widget.Toast

/**
 * Single source of truth for the map day/night selector, which previously lived (copy-pasted) in
 * Setup, Controls and the HUD. Map theme applies live — no reconnect — so setting it here also pushes
 * the new value to any running Android Auto session via [AaVideoBridge.nightSink].
 */
object MapThemeControl {

    /** Persist [theme], push it live, and toast. Callers re-highlight from their own refresh path. */
    fun set(activity: Activity, theme: MapTheme) {
        NightPrefs.setTheme(activity, theme)
        AaVideoBridge.nightSink?.invoke(NightPrefs.isNightNow(activity))
        Toast.makeText(activity, "Map theme: ${theme.label}", Toast.LENGTH_SHORT).show()
    }

    /** Wire a three-button Auto/Day/Night group backed by the shared [SegmentedControl]. */
    fun bind(activity: Activity, autoId: Int, dayId: Int, nightId: Int, afterSelect: () -> Unit = {}) {
        SegmentedControl.bind(
            activity,
            NightPrefs.theme(activity),
            { theme -> set(activity, theme); afterSelect() },
            autoId to MapTheme.AUTO,
            dayId to MapTheme.DAY,
            nightId to MapTheme.NIGHT,
        )
    }

    fun highlight(activity: Activity, autoId: Int, dayId: Int, nightId: Int) {
        SegmentedControl.highlight(
            activity,
            NightPrefs.theme(activity),
            autoId to MapTheme.AUTO,
            dayId to MapTheme.DAY,
            nightId to MapTheme.NIGHT,
        )
    }
}
