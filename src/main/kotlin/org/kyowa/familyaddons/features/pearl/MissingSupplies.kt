package org.kyowa.familyaddons.features.pearl

import net.minecraft.world.phys.Vec3

/**
 * Tracks supplies that have been called out as "missing" by the team. When a
 * player types `Party > X: No Triangle!`, the regex picks that up and adds
 * [Place.TRIANGLE] to [missing], indicating that Triangle's supply pickup is
 * empty and should be hidden / skipped.
 *
 * Note that this set is INTENTIONALLY mutable from outside — the chat handler
 * inside PearlWaypoints.kt mutates it directly. Cleared on world load.
 */
object MissingSupplies {
    val missing: MutableSet<Place> = LinkedHashSet()
    val missingPres: MutableSet<Pre> = LinkedHashSet()

    fun clear() {
        missing.clear()
        missingPres.clear()
    }
}

/**
 * Priority logic — given the [Pre] area the player is standing in, returns the
 * world position of the supply they should pearl-throw to.
 *
 * - For [Pre.SQUARE], throw to whatever supply has been called out as missing.
 *   (Square has no fixed assigned supply; it's a fallback aim point used when
 *   you're hauling a chest from a distance.)
 * - For other spots, use either [normalPrio] (identity mapping: SHOP→SHOP, X→X, …)
 *   or [newPrio] (a slightly different routing that swaps SHOP ↔ TRIANGLE for
 *   teams that prefer that pickup order).
 */
object Prio {
    @Volatile var useNewPrio: Boolean = false

    private val normalPrio: Map<Pre, Vec3> = mapOf(
        Pre.SHOP     to Place.SHOP.location,
        Pre.X        to Place.X.location,
        Pre.X_CANNON to Place.X_CANNON.location,
        Pre.EQUALS   to Place.EQUALS.location,
        Pre.SLASH    to Place.SLASH.location,
        Pre.TRIANGLE to Place.TRIANGLE.location,
    )

    private val newPrio: Map<Pre, Vec3> = mapOf(
        Pre.SHOP     to Place.TRIANGLE.location,  // swapped
        Pre.X        to Place.X.location,
        Pre.X_CANNON to Place.X_CANNON.location,
        Pre.EQUALS   to Place.EQUALS.location,
        Pre.SLASH    to Place.SLASH.location,
        Pre.TRIANGLE to Place.SHOP.location,      // swapped
    )

    /**
     * Returns the supply Vec3 to throw at given the player is currently in [spot],
     * or null if no supply is assigned (e.g. NONE or SQUARE with nothing missing).
     */
    fun getSupplyForSpot(spot: Pre): Vec3? {
        if (spot == Pre.SQUARE) {
            return MissingSupplies.missing.firstOrNull()?.location
        }
        return if (useNewPrio) newPrio[spot] else normalPrio[spot]
    }
}
