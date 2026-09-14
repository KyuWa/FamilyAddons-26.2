package org.kyowa.familyaddons.features.safari

/**
 * The Critter Safari roster: 37 species across four biomes.
 *
 * The species list, their biomes and the chat wordings [SafariTracker] matches were
 * taken from Critter Safari Tracker by Rok (https://github.com/MrCloudy2/critterMod),
 * which is MIT licensed. Copyright (c) Rok. Nothing else of that mod is used here.
 */
enum class SafariBiome(val displayName: String, val color: String) {
    FOREST("Forest", "§a"),
    CAVERN("Cavern", "§6"),
    ICY("Icy", "§b"),
    HAUNTED("Haunted", "§5");

    /** One-letter tag for the compact per-player line. */
    val tag: String get() = displayName.take(1)

    /** How the biome reads on the scoreboard / tab area line, e.g. "Icy Biome". */
    val areaName: String get() = "$displayName Biome"

    companion object {
        fun fromAreaName(area: String?): SafariBiome? {
            if (area == null) return null
            return entries.firstOrNull { area.contains(it.areaName, ignoreCase = true) }
        }
    }
}

data class SafariCritter(val name: String, val biome: SafariBiome)

object SafariCritters {

    val ALL: List<SafariCritter> = listOf(
        // Forest (9)
        SafariCritter("Foxtrot", SafariBiome.FOREST),
        SafariCritter("Bluebird", SafariBiome.FOREST),
        SafariCritter("Honeybug", SafariBiome.FOREST),
        SafariCritter("Treefrog", SafariBiome.FOREST),
        SafariCritter("Woodchucker", SafariBiome.FOREST),
        SafariCritter("Fluffling", SafariBiome.FOREST),
        SafariCritter("Hideonfloor", SafariBiome.FOREST),
        SafariCritter("Parakeet", SafariBiome.FOREST),
        SafariCritter("Macaw", SafariBiome.FOREST),
        // Cavern (9)
        SafariCritter("Cavernfish", SafariBiome.CAVERN),
        SafariCritter("Flitter", SafariBiome.CAVERN),
        SafariCritter("Shyworm", SafariBiome.CAVERN),
        SafariCritter("Driftling", SafariBiome.CAVERN),
        SafariCritter("Chuckwalla", SafariBiome.CAVERN),
        SafariCritter("Rockmite", SafariBiome.CAVERN),
        SafariCritter("Scrappy", SafariBiome.CAVERN),
        SafariCritter("Snoozle", SafariBiome.CAVERN),
        SafariCritter("Gemzie", SafariBiome.CAVERN),
        // Icy (9)
        SafariCritter("Strongarm", SafariBiome.ICY),
        SafariCritter("Tepid", SafariBiome.ICY),
        SafariCritter("Polaris", SafariBiome.ICY),
        SafariCritter("Shuddersquid", SafariBiome.ICY),
        SafariCritter("Billygoat", SafariBiome.ICY),
        SafariCritter("Mantis Shrimp", SafariBiome.ICY),
        SafariCritter("Nozzlenose", SafariBiome.ICY),
        SafariCritter("Troodon", SafariBiome.ICY),
        SafariCritter("Wumpa", SafariBiome.ICY),
        // Haunted (10)
        SafariCritter("Areita", SafariBiome.HAUNTED),
        SafariCritter("Bloodbat", SafariBiome.HAUNTED),
        SafariCritter("Duplico", SafariBiome.HAUNTED),
        SafariCritter("Gazer", SafariBiome.HAUNTED),
        SafariCritter("Litterbug", SafariBiome.HAUNTED),
        SafariCritter("Solsnatcher", SafariBiome.HAUNTED),
        SafariCritter("Gimmiegold", SafariBiome.HAUNTED),
        SafariCritter("Hideonwall", SafariBiome.HAUNTED),
        SafariCritter("Hideyho", SafariBiome.HAUNTED),
        SafariCritter("Doomspiral", SafariBiome.HAUNTED),
    )

    val TOTAL: Int = ALL.size

    private val BY_BIOME: Map<SafariBiome, List<SafariCritter>> = ALL.groupBy { it.biome }

    /**
     * Longest name first: "Mantis Shrimp" must be tried before any species whose name
     * is a prefix of it, or a shorter name would win the substring match.
     */
    private val BY_LENGTH_DESC: List<SafariCritter> = ALL.sortedByDescending { it.name.length }

    fun inBiome(biome: SafariBiome): List<SafariCritter> = BY_BIOME[biome] ?: emptyList()

    fun totalIn(biome: SafariBiome): Int = inBiome(biome).size

    /**
     * The species named somewhere in a chat line, or null.
     *
     * Matching by roster lookup rather than a full-sentence regex means a wording we
     * have not seen still resolves, as long as the species name appears verbatim.
     */
    fun findIn(line: String): SafariCritter? = BY_LENGTH_DESC.firstOrNull { line.contains(it.name) }
}
