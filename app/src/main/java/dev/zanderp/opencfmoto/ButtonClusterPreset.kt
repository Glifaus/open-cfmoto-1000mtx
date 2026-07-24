// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Recommended handlebar mappings for the common CFMOTO left-switch clusters. Physical hardware
 * differs in what AVRCP events the bike actually sends — especially long-press and double-tap —
 * so one default map is not enough. Riders pick the photo that matches their bars in
 * [ButtonMappingActivity]; [apply] writes the mapping for the **selected bike**.
 *
 * Evidence (field logs 2026-07-21 / 07-22 / 07-24 + 800MT Explore):
 *  - **BACK/SET diamond** — long-press collapses to a single tap; discrete double-tap is absent.
 *    Some units still coalesce ▲/▼ *volume* into a big jump (= ×2) — we keep that path.
 *  - **MODE/ENT** — singles + occasional Select ×2; hold on track keys when the dash sends UP.
 *  - **5-way Explore (Fn / ★ / voice center)** — ◀/▶ + start with full tap / ×2 / hold on all three.
 */
enum class ButtonClusterPreset(
    val id: String,
    val title: String,
    val summary: String,
    /**
     * When true, [MediaButtonBridge] fires singles immediately (no double-tap wait). Use for
     * clusters that cannot express discrete ×2 — waiting only adds lag.
     */
    val instantSingles: Boolean,
) {
    FIVE_WAY(
        id = "five_way",
        title = "5-way Explore (Fn / ★ / voice)",
        summary = "◀/▶ = knob (move) · ◀◀/▶▶ = D-pad ←/→ (into apps) · ★ = Select · " +
            "★★ = Back · ★ hold = Home. D-pad ↑↓ unused on these AA screens.",
        instantSingles = false,
    ),
    MODE_ENT(
        id = "mode_ent",
        title = "MODE / ENT cluster",
        summary = "Same AA navigation as 5-way: knob · ×2 = D-pad ←/→ · ★★ = Back. " +
            "Raise the double-tap delay in Setup if ×2 feels picky.",
        instantSingles = false,
    ),
    BACK_SET(
        id = "back_set",
        title = "BACK / SET diamond (limited)",
        summary = "Snappy singles (no double-tap wait). Discrete hold / Select ×2 usually don't " +
            "exist — but a hard ▲▲ / ▼▼ volume flick often coalesces into ×2 → Back / Home. " +
            "On-screen pad covers anything else.",
        instantSingles = true,
    );

    /** Mapping this preset writes. Unlisted gestures keep [ButtonGesture.default] via [ButtonMap.resetAll]. */
    fun mapping(): Map<ButtonGesture, ButtonAction> = when (this) {
        FIVE_WAY -> ButtonGesture.entries.associateWith { it.default }
        MODE_ENT -> mapOf(
            ButtonGesture.NAV_BACK to ButtonAction.KNOB_BACK,
            ButtonGesture.NAV_FWD to ButtonAction.KNOB_FORWARD,
            ButtonGesture.SELECT_PRESS to ButtonAction.SELECT,
            ButtonGesture.NAV_BACK_LONG to ButtonAction.NONE,
            ButtonGesture.NAV_FWD_LONG to ButtonAction.NONE,
            ButtonGesture.SELECT_LONG to ButtonAction.HOME,
            ButtonGesture.SELECT_DOUBLE to ButtonAction.BACK,
            ButtonGesture.NAV_BACK_DOUBLE to ButtonAction.DPAD_LEFT,
            ButtonGesture.NAV_FWD_DOUBLE to ButtonAction.DPAD_RIGHT,
        )
        BACK_SET -> mapOf(
            ButtonGesture.NAV_BACK to ButtonAction.KNOB_BACK,
            ButtonGesture.NAV_FWD to ButtonAction.KNOB_FORWARD,
            ButtonGesture.SELECT_PRESS to ButtonAction.SELECT,
            ButtonGesture.NAV_BACK_LONG to ButtonAction.NONE,
            ButtonGesture.NAV_FWD_LONG to ButtonAction.NONE,
            ButtonGesture.SELECT_LONG to ButtonAction.NONE,
            ButtonGesture.SELECT_DOUBLE to ButtonAction.NONE,
            // Volume-coalesced ▲▲ / ▼▼ (jump ≥ 3) still fire these on many CFDL16 logs.
            ButtonGesture.NAV_BACK_DOUBLE to ButtonAction.BACK,
            ButtonGesture.NAV_FWD_DOUBLE to ButtonAction.HOME,
        )
    }

    fun apply(context: Context) {
        // Clear first so gestures not listed fall back to shipped defaults (FIVE_WAY), then
        // overwrite every entry for limited presets (including NONE).
        ButtonMap.resetAll(context)
        for ((gesture, action) in mapping()) {
            ButtonMap.set(context, gesture, action)
        }
        saveActive(context, this)
    }

    companion object {
        private const val PREF = "button_cluster_preset"
        private const val KEY_ACTIVE = "active"

        fun byId(id: String?): ButtonClusterPreset? = entries.firstOrNull { it.id == id }

        fun active(context: Context): ButtonClusterPreset? =
            byId(BikeScope.getString(prefs(context), context, KEY_ACTIVE, null))

        /** True when singles should fire with no double-tap delay (BACK/SET, or inferred). */
        fun prefersInstantSingles(context: Context): Boolean {
            active(context)?.let { return it.instantSingles }
            // Infer: rider manually disabled every multi-press / hold gesture.
            return listOf(
                ButtonGesture.SELECT_DOUBLE,
                ButtonGesture.SELECT_LONG,
                ButtonGesture.NAV_BACK_DOUBLE,
                ButtonGesture.NAV_FWD_DOUBLE,
                ButtonGesture.NAV_BACK_LONG,
                ButtonGesture.NAV_FWD_LONG,
            ).all { ButtonMap.get(context, it) == ButtonAction.NONE }
        }

        fun saveActive(context: Context, preset: ButtonClusterPreset?) {
            val p = prefs(context)
            if (preset == null) BikeScope.remove(p, context, KEY_ACTIVE)
            else BikeScope.putString(p, context, KEY_ACTIVE, preset.id)
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }
}
