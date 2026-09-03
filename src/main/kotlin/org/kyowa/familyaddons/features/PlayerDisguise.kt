package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.config.FamilyConfigManager

object PlayerDisguise {
    fun isEnabled(): Boolean = FamilyConfigManager.config.playerDisguise.enabled
    fun getMobId(): String = FamilyConfigManager.config.playerDisguise.mobId.trim().lowercase()
    fun getScope(): Int = FamilyConfigManager.config.playerDisguise.scope
    fun isBaby(): Boolean = FamilyConfigManager.config.playerDisguise.baby
    fun isSheared(): Boolean = FamilyConfigManager.config.playerDisguise.sheared
    fun showFriendsDisguises(): Boolean = FamilyConfigManager.config.playerDisguise.showFriendsDisguises

    /**
     * Returns the effective scale multiplier for the disguise.
     * If Custom Scaling is off, returns 1.0 (no transformation).
     * If on, returns the slider value (1-50, representing tenths) divided by 10
     * and clamped to [0.1, 5.0].
     */
    fun getCustomScale(): Float {
        val cfg = FamilyConfigManager.config.playerDisguise
        if (!cfg.customScalingEnabled) return 1.0f
        return (cfg.customScaleTenths / 10f).coerceIn(0.1f, 5.0f)
    }

    fun isCustomScalingEnabled(): Boolean =
        FamilyConfigManager.config.playerDisguise.customScalingEnabled
}