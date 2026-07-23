// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * One shared implementation of the "segmented control" pattern used across Setup, Advanced settings,
 * Controls and the HUD. Previously each screen re-implemented the same imperative tinting
 * (`SetupActivity.highlight` / `ControlsActivity.highlightTheme`); this centralizes it so the look is
 * identical everywhere and there is a single place to restyle.
 *
 * The selected segment is painted in brand color; the rest stay neutral tonal.
 */
object SegmentedControl {

    /** Paint the segment whose value == [selected] as active. */
    fun <T> highlight(ctx: Context, selected: T, vararg pairs: Pair<Int, T>) {
        val onBg = ContextCompat.getColor(ctx, R.color.brand_accent)
        val onText = ContextCompat.getColor(ctx, R.color.on_brand)
        val offBg = ContextCompat.getColor(ctx, R.color.surface_high)
        val offText = ContextCompat.getColor(ctx, R.color.text_primary)
        val root = (ctx as? Activity) ?: return
        for ((id, value) in pairs) {
            val btn = root.findViewById<MaterialButton>(id) ?: continue
            val on = value == selected
            btn.backgroundTintList = ColorStateList.valueOf(if (on) onBg else offBg)
            btn.setTextColor(if (on) onText else offText)
        }
    }

    /**
     * Wire click handlers for a segment group and paint the initial [selected]. Each click reports the
     * chosen value through [onSelect] (which typically persists it and re-reads state); callers should
     * then call [highlight] again from their refresh path so external changes stay reflected.
     */
    fun <T> bind(activity: Activity, selected: T, onSelect: (T) -> Unit, vararg pairs: Pair<Int, T>) {
        for ((id, value) in pairs) {
            activity.findViewById<MaterialButton>(id)?.setOnClickListener { onSelect(value) }
        }
        highlight(activity, selected, *pairs)
    }
}
