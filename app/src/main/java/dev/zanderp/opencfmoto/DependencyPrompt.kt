// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * One place that lists what OpenCfMoto needs and pops a dialog with Install / Grant / Settings
 * actions when something is missing — so a rider isn't stuck with a silent failure after install.
 */
object DependencyPrompt {

    private const val REQ_PERMS = 91
    @Volatile private var shownThisProcess = false

    data class Issue(
        val id: String,
        val title: String,
        val detail: String,
        val required: Boolean,
        val action: Action,
    )

    enum class Action {
        INSTALL_ANDROID_AUTO,
        INSTALL_PLAY_SERVICES,
        GRANT_PERMISSIONS,
        ENABLE_WIFI,
        OPEN_OVERLAY,
        OPEN_SETUP,
    }

    fun audit(ctx: android.content.Context): List<Issue> = buildList {
        if (!SetupHelper.isAndroidAutoInstalled(ctx)) {
            add(
                Issue(
                    id = "aa",
                    title = "Google Android Auto",
                    detail = "Required to project Maps / Waze to the dash. Install it from the Play Store.",
                    required = true,
                    action = Action.INSTALL_ANDROID_AUTO,
                ),
            )
        }
        if (!SetupHelper.isPlayServicesPresent(ctx)) {
            add(
                Issue(
                    id = "gms",
                    title = "Google Play services",
                    detail = "Android Auto needs Play services on this phone. Install or update them from the Play Store.",
                    required = true,
                    action = Action.INSTALL_PLAY_SERVICES,
                ),
            )
        }
        val missingPerms = SetupHelper.missingPermissions(ctx)
        if (missingPerms.isNotEmpty()) {
            add(
                Issue(
                    id = "perms",
                    title = "App permissions",
                    detail = "Still needed: ${missingPerms.joinToString(", ") { permLabel(it) }}.",
                    required = true,
                    action = Action.GRANT_PERMISSIONS,
                ),
            )
        }
        if (!WifiGate.isWifiEnabled(ctx)) {
            add(
                Issue(
                    id = "wifi",
                    title = "Phone Wi‑Fi is off",
                    detail = "The bike link uses Wi‑Fi. Turn Wi‑Fi on before Connect.",
                    required = true,
                    action = Action.ENABLE_WIFI,
                ),
            )
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            add(
                Issue(
                    id = "mic",
                    title = "Microphone (optional)",
                    detail = "Needed for Voice / Assistant on the pad. You can grant it later when you use Voice.",
                    required = false,
                    action = Action.GRANT_PERMISSIONS,
                ),
            )
        }
        if (!SetupHelper.canAutoResume(ctx)) {
            add(
                Issue(
                    id = "overlay",
                    title = "Display over other apps (optional)",
                    detail = "Lets OpenCfMoto relaunch Android Auto after a long stop without tapping a notification.",
                    required = false,
                    action = Action.OPEN_OVERLAY,
                ),
            )
        }
    }

    fun requiredMissing(ctx: android.content.Context): List<Issue> =
        audit(ctx).filter { it.required }

    /**
     * Show once per process on home launch when required deps are missing.
     * @return true if a dialog was shown.
     */
    fun showOnLaunchIfNeeded(activity: Activity): Boolean {
        if (shownThisProcess) return false
        val missing = requiredMissing(activity)
        if (missing.isEmpty()) return false
        shownThisProcess = true
        show(activity, missing, alsoOptional = true)
        return true
    }

    /** Always show when Connect is blocked by missing required deps. */
    fun showForConnect(activity: Activity): Boolean {
        val missing = requiredMissing(activity)
        if (missing.isEmpty()) return false
        show(activity, missing, alsoOptional = false)
        return true
    }

    private fun show(activity: Activity, required: List<Issue>, alsoOptional: Boolean) {
        val optional = if (alsoOptional) {
            audit(activity).filter { !it.required }
        } else {
            emptyList()
        }
        val body = buildString {
            append("OpenCfMoto needs a few things on this phone before it can talk to your bike:\n")
            for (issue in required) {
                append("\n• ").append(issue.title)
                append("\n  ").append(issue.detail)
            }
            if (optional.isNotEmpty()) {
                append("\n\nRecommended:")
                for (issue in optional) {
                    append("\n• ").append(issue.title)
                    append("\n  ").append(issue.detail)
                }
            }
        }
        val primary = required.firstOrNull() ?: optional.firstOrNull()
        val positiveLabel = when (primary?.action) {
            Action.INSTALL_ANDROID_AUTO -> "Install Android Auto"
            Action.INSTALL_PLAY_SERVICES -> "Install Play services"
            Action.GRANT_PERMISSIONS -> "Grant permissions"
            Action.ENABLE_WIFI -> "Wi‑Fi settings"
            Action.OPEN_OVERLAY -> "Enable overlay"
            Action.OPEN_SETUP, null -> "Open Setup"
        }
        try {
            AlertDialog.Builder(activity)
                .setTitle("Missing dependencies")
                .setMessage(body)
                .setPositiveButton(positiveLabel) { _, _ ->
                    primary?.let { fix(activity, it) } ?: SetupActivity.start(activity)
                }
                .setNeutralButton("Open Setup") { _, _ -> SetupActivity.start(activity) }
                .setNegativeButton("Later", null)
                .show()
        } catch (e: Exception) {
            LogBus.log("[deps] dialog failed: $e")
            Toast.makeText(activity, "Open Setup to finish prerequisites", Toast.LENGTH_LONG).show()
            SetupActivity.start(activity)
        }
    }

    fun fix(activity: Activity, issue: Issue) {
        when (issue.action) {
            Action.INSTALL_ANDROID_AUTO -> openPlay(activity, SetupHelper.GEARHEAD_PACKAGE)
            Action.INSTALL_PLAY_SERVICES -> openPlay(activity, SetupHelper.PLAY_SERVICES_PACKAGE)
            Action.GRANT_PERMISSIONS -> {
                val missing = SetupHelper.missingPermissions(activity).toMutableList()
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    missing.add(Manifest.permission.RECORD_AUDIO)
                }
                if (missing.isEmpty()) {
                    openAppSettings(activity)
                } else {
                    ActivityCompat.requestPermissions(activity, missing.distinct().toTypedArray(), REQ_PERMS)
                }
            }
            Action.ENABLE_WIFI -> WifiGate.openWifiSettings(activity)
            Action.OPEN_OVERLAY -> openOverlaySettings(activity)
            Action.OPEN_SETUP -> SetupActivity.start(activity)
        }
    }

    private fun permLabel(perm: String): String = when (perm) {
        Manifest.permission.CAMERA -> "Camera (scan QR)"
        Manifest.permission.ACCESS_FINE_LOCATION -> "Location (join bike Wi‑Fi)"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        Manifest.permission.RECORD_AUDIO -> "Microphone"
        else -> perm.substringAfterLast('.')
    }

    private fun openPlay(activity: Activity, pkg: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        try {
            activity.startActivity(market)
        } catch (_: Exception) {
            try {
                activity.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
                    ),
                )
            } catch (_: Exception) {
                Toast.makeText(activity, "Couldn't open the Play Store", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openAppSettings(activity: Activity) {
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null),
                ),
            )
        } catch (_: Exception) {
            Toast.makeText(activity, "Couldn't open app settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOverlaySettings(activity: Activity) {
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", activity.packageName, null),
                ),
            )
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(activity, "Couldn't open overlay settings", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
