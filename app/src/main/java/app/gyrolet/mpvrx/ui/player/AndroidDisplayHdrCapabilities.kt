/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.os.Build
import android.view.Display

/**
 * HDR formats reported by the display currently hosting the player Surface.
 *
 * [known] is deliberately separate from the flags: an empty supported-type list means that the
 * display was queried successfully and reported no HDR formats, while null/failed queries mean
 * that the capability is unknown. Direct Dolby Vision output is disabled in the latter case.
 */
internal data class DisplayHdrSupport(
  val known: Boolean,
  val dolbyVision: Boolean = false,
  val hdr10: Boolean = false,
  val hdr10Plus: Boolean = false,
  val hlg: Boolean = false,
) {
  companion object {
    val Unknown = DisplayHdrSupport(known = false)
  }
}

/** Queries the actual output display rather than assuming the default physical display. */
internal object AndroidDisplayHdrCapabilities {
  @Suppress("DEPRECATION")
  fun query(display: Display?): DisplayHdrSupport {
    if (display == null) return DisplayHdrSupport.Unknown

    val supportedTypes =
      runCatching {
        // Android 14 added per-display-mode HDR reporting. Earlier Android versions expose the
        // same set through Display.HdrCapabilities.
        if (Build.VERSION.SDK_INT >= 34) {
          display.mode.supportedHdrTypes
        } else {
          display.hdrCapabilities?.supportedHdrTypes
        }
      }.getOrNull() ?: return DisplayHdrSupport.Unknown

    return DisplayHdrSupport(
      known = true,
      dolbyVision = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in supportedTypes,
      hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in supportedTypes,
      hdr10Plus =
        Build.VERSION.SDK_INT >= 29 &&
          Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in supportedTypes,
      hlg = Display.HdrCapabilities.HDR_TYPE_HLG in supportedTypes,
    )
  }
}
