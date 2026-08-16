// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto

import android.content.Context

/** Fixed product choices for the dedicated CFMOTO 1000MT-X build. */
object Mtx1000Config {
    val profile = ProfileOverride.CFDL26_PORT
    val transport = WifiTransport.P2P
    const val forceNonTouch = true
    const val logTrips = false

    fun apply(context: Context) {
        ProfilePrefs.set(context, profile)
        AppSettings.setTransport(context, transport)
        AppSettings.setForceNonTouch(context, forceNonTouch)
        AppSettings.setLogTrips(context, logTrips)
    }
}
