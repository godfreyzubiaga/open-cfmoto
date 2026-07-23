// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Static compatibility reference, moved out of the Settings scroll so that screen stays about
 * settings. Content comes entirely from string resources (same text as the README).
 */
class SupportedBikesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_supported_bikes)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.supported_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, b.top, v.paddingRight, b.bottom)
            insets
        }
    }

    companion object {
        fun start(ctx: android.content.Context) {
            ctx.startActivity(Intent(ctx, SupportedBikesActivity::class.java))
        }
    }
}
