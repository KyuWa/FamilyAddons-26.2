package org.kyowa.familyaddons.features.safari

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.util.HypixelLocation

/**
 * Outlines every Critter Safari species, the awkward ones included: Hideyho,
 * Hideonwall, Hideonfloor, Duplico, Bloodbat and the rest of the 37.
 *
 * Hypixel labels a critter with a separate entity whose custom name is the species
 * name, sitting just above the mob itself. Matching the label is what makes this
 * reliable across species that render as bare vanilla mobs, and the mob underneath is
 * what gets outlined so the box sits on the critter rather than over its head.
 *
 * Independent of the Highlight category's master toggle: this is a Critter Safari
 * feature and lives in that category. Always drawn as an outline, by design.
 */
object SafariCritterEsp {

    private const val SCAN_INTERVAL = 5

    /** A label sits directly above its mob, so the pairing radius stays tight. */
    private const val LABEL_TO_MOB_RADIUS = 3.0

    private val tracked = mutableListOf<Entity>()
    private var tick = 0

    private fun cfg() = FamilyConfigManager.config.safari

    /** Area name carries "Safari" at the island and at its entrance. */
    private fun inSafari() = HypixelLocation.areaName()?.contains("safari", ignoreCase = true) == true

    private fun active() = cfg().enabled && cfg().critterEsp && inSafari()

    fun trackedEntities(): List<Entity> = if (active()) tracked else emptyList()

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> tracked.clear() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> tracked.clear() }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!active()) {
                if (tracked.isNotEmpty()) tracked.clear()
                return@register
            }
            if (tick++ % SCAN_INTERVAL != 0) return@register
            scan(client)
        }
    }

    /**
     * The species a label names, or null. "Sparkling Gemzie" resolves to Gemzie, so a
     * rare one is outlined like any other; [SparklingCritterHighlight] is what calls it
     * out as rare.
     */
    private fun speciesOf(entity: Entity): SafariCritter? {
        val raw = entity.customName?.string ?: return null
        val name = raw.replace(COLOR_CODE_REGEX, "").trim()
        if (name.isEmpty()) return null
        val bare = name.removePrefix("Sparkling").trim()
        return SafariCritters.ALL.firstOrNull { it.name.equals(bare, ignoreCase = true) }
            ?: SafariCritters.ALL.firstOrNull { bare.startsWith(it.name, ignoreCase = true) }
    }

    private fun scan(client: Minecraft) {
        val level = client.level ?: return
        tracked.clear()

        val labels = ArrayList<Entity>()
        val bodies = ArrayList<Entity>()
        for (entity in level.entitiesForRendering()) {
            if (!entity.isAlive || entity is Player) continue
            if (speciesOf(entity) != null) {
                labels.add(entity)
            } else if (entity is LivingEntity && entity !is ArmorStand) {
                bodies.add(entity)
            }
        }
        if (labels.isEmpty()) return

        val used = HashSet<Entity>()
        for (label in labels) {
            // The mob under the label, nearest first. Outlining that rather than the
            // label puts the box on the critter instead of floating above it.
            val body = bodies
                .filter { it !in used && it.position().distanceTo(label.position()) <= LABEL_TO_MOB_RADIUS }
                .minByOrNull { it.position().distanceTo(label.position()) }
            if (body != null) {
                used.add(body)
                tracked.add(body)
            } else {
                // No body found: outline the label, so a critter is never missed just
                // because its mob could not be paired.
                tracked.add(label)
            }
        }
    }
}
