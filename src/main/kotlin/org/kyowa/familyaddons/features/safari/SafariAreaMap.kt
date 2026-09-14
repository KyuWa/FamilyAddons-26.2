package org.kyowa.familyaddons.features.safari

import org.kyowa.familyaddons.FamilyAddons

/**
 * Maps a position on the Safari island to its biome.
 *
 * Neither the scoreboard nor the tab list names the biome you stand in, so it has
 * to come from where you are. The biomes are not convex (Forest and Haunted
 * interleave, the caves fold over each other), so nearest-centre is wrong for a few
 * percent of the map. SkyHanni's island path graph solves that with a graph walk;
 * critterMod ran that walk offline once over the 1,327 graph nodes and collapsed it
 * to one `x y z biome` row per node. At runtime this is a nearest-node lookup.
 *
 * `safari_areas.txt` and this approach come from Critter Safari Tracker by Rok
 * (https://github.com/MrCloudy2/critterMod, MIT). Codes: 1 Forest, 2 Cavern,
 * 3 Icy, 4 Haunted, 0 the hub / entrance (no biome).
 */
object SafariAreaMap {

    private const val MAX_NODE_DISTANCE = 40.0

    private var xs = IntArray(0)
    private var ys = IntArray(0)
    private var zs = IntArray(0)
    private var codes = ByteArray(0)
    private var loaded = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        try {
            val text = SafariAreaMap::class.java.getResourceAsStream("/safari_areas.txt")
                ?.bufferedReader()?.readText() ?: run {
                FamilyAddons.LOGGER.warn("SafariAreaMap: safari_areas.txt missing from the jar")
                return
            }
            val rows = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.split(Regex("\\s+")) }.filter { it.size >= 4 }.toList()
            xs = IntArray(rows.size) { rows[it][0].toInt() }
            ys = IntArray(rows.size) { rows[it][1].toInt() }
            zs = IntArray(rows.size) { rows[it][2].toInt() }
            codes = ByteArray(rows.size) { rows[it][3].toByte() }
            FamilyAddons.LOGGER.info("SafariAreaMap: ${rows.size} nodes loaded")
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("SafariAreaMap: failed to load: ${e.message}")
        }
    }

    /** The biome at a position, or null off the map / at the hub. */
    fun biomeAt(x: Double, y: Double, z: Double): SafariBiome? {
        load()
        if (xs.isEmpty()) return null
        var best = -1
        var bestSq = Double.MAX_VALUE
        for (i in xs.indices) {
            val dx = x - xs[i]; val dy = y - ys[i]; val dz = z - zs[i]
            val d = dx * dx + dy * dy + dz * dz
            if (d < bestSq) { bestSq = d; best = i }
        }
        if (best < 0 || bestSq > MAX_NODE_DISTANCE * MAX_NODE_DISTANCE) return null
        return when (codes[best].toInt()) {
            1 -> SafariBiome.FOREST
            2 -> SafariBiome.CAVERN
            3 -> SafariBiome.ICY
            4 -> SafariBiome.HAUNTED
            else -> null
        }
    }
}
