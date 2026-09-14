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

/** Hypixel rarity, used for the missing list's order and colour (common first). */
enum class Rarity(val color: String) {
    COMMON("§f"), UNCOMMON("§a"), RARE("§9"), EPIC("§5"), LEGENDARY("§6")
}

data class SafariCritter(val name: String, val biome: SafariBiome, val rarity: Rarity = Rarity.COMMON)

object SafariCritters {

    val ALL: List<SafariCritter> = listOf(
        // Forest (9)
        SafariCritter("Foxtrot", SafariBiome.FOREST, Rarity.COMMON),
        SafariCritter("Bluebird", SafariBiome.FOREST, Rarity.UNCOMMON),
        SafariCritter("Honeybug", SafariBiome.FOREST, Rarity.UNCOMMON),
        SafariCritter("Treefrog", SafariBiome.FOREST, Rarity.UNCOMMON),
        SafariCritter("Woodchucker", SafariBiome.FOREST, Rarity.UNCOMMON),
        SafariCritter("Fluffling", SafariBiome.FOREST, Rarity.RARE),
        SafariCritter("Hideonfloor", SafariBiome.FOREST, Rarity.RARE),
        SafariCritter("Parakeet", SafariBiome.FOREST, Rarity.RARE),
        SafariCritter("Macaw", SafariBiome.FOREST, Rarity.LEGENDARY),
        // Cavern (9)
        SafariCritter("Cavernfish", SafariBiome.CAVERN, Rarity.COMMON),
        SafariCritter("Flitter", SafariBiome.CAVERN, Rarity.COMMON),
        SafariCritter("Shyworm", SafariBiome.CAVERN, Rarity.COMMON),
        SafariCritter("Driftling", SafariBiome.CAVERN, Rarity.UNCOMMON),
        SafariCritter("Chuckwalla", SafariBiome.CAVERN, Rarity.RARE),
        SafariCritter("Rockmite", SafariBiome.CAVERN, Rarity.RARE),
        SafariCritter("Scrappy", SafariBiome.CAVERN, Rarity.RARE),
        SafariCritter("Snoozle", SafariBiome.CAVERN, Rarity.RARE),
        SafariCritter("Gemzie", SafariBiome.CAVERN, Rarity.EPIC),
        // Icy (9)
        SafariCritter("Strongarm", SafariBiome.ICY, Rarity.COMMON),
        SafariCritter("Tepid", SafariBiome.ICY, Rarity.COMMON),
        SafariCritter("Polaris", SafariBiome.ICY, Rarity.UNCOMMON),
        SafariCritter("Shuddersquid", SafariBiome.ICY, Rarity.UNCOMMON),
        SafariCritter("Billygoat", SafariBiome.ICY, Rarity.RARE),
        SafariCritter("Mantis Shrimp", SafariBiome.ICY, Rarity.RARE),
        SafariCritter("Nozzlenose", SafariBiome.ICY, Rarity.RARE),
        SafariCritter("Troodon", SafariBiome.ICY, Rarity.RARE),
        SafariCritter("Wumpa", SafariBiome.ICY, Rarity.LEGENDARY),
        // Haunted (10)
        SafariCritter("Areita", SafariBiome.HAUNTED, Rarity.UNCOMMON),
        SafariCritter("Bloodbat", SafariBiome.HAUNTED, Rarity.UNCOMMON),
        SafariCritter("Duplico", SafariBiome.HAUNTED, Rarity.UNCOMMON),
        SafariCritter("Gazer", SafariBiome.HAUNTED, Rarity.UNCOMMON),
        SafariCritter("Litterbug", SafariBiome.HAUNTED, Rarity.UNCOMMON),
        SafariCritter("Solsnatcher", SafariBiome.HAUNTED, Rarity.UNCOMMON),
        SafariCritter("Gimmiegold", SafariBiome.HAUNTED, Rarity.RARE),
        SafariCritter("Hideonwall", SafariBiome.HAUNTED, Rarity.RARE),
        SafariCritter("Hideyho", SafariBiome.HAUNTED, Rarity.RARE),
        SafariCritter("Doomspiral", SafariBiome.HAUNTED, Rarity.LEGENDARY),
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
