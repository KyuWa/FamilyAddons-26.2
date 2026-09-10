package org.kyowa.familyaddons.features

import com.google.gson.JsonObject
import org.kyowa.familyaddons.util.FaChat
import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.KeyFetcher
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.util.HypixelLocation

object BestiaryZoneHighlight {

    // Order must stay index-aligned with the BestiaryConfig dropdown. New
    // zones go at the END so saved config indices keep meaning the same zone.
    // "Galatea" was renamed "Moonglade Marsh" in-game/repo (same index/key).
    val ZONES = listOf(
        "None", "Island", "Hub", "The Farming Lands", "The Garden", "Spider's Den",
        "The End", "Crimson Isle", "Deep Caverns", "Dwarven Mines", "Crystal Hollows",
        "The Park", "Moonglade Marsh", "Spooky Festival", "The Catacombs", "Fishing",
        "Mythological Creatures", "Jerry", "Kuudra",
        "Torrhus Canyon", "Lotus Atoll", "Critter Safari"
    )

    private val ZONE_TO_NEU_KEY = mapOf(
        "Island" to "dynamic", "Hub" to "hub", "The Farming Lands" to "farming_1",
        "The Garden" to "garden", "Spider's Den" to "combat_1", "The End" to "combat_3",
        "Crimson Isle" to "crimson_isle", "Deep Caverns" to "mining_2",
        "Dwarven Mines" to "mining_3", "Crystal Hollows" to "crystal_hollows",
        "The Park" to "foraging_1", "Moonglade Marsh" to "foraging_2",
        "Spooky Festival" to "spooky_festival", "The Catacombs" to "catacombs",
        "Fishing" to "fishing", "Mythological Creatures" to "mythological_creatures",
        "Jerry" to "jerry", "Kuudra" to "kuudra",
        // The repo nests fishing/safari sub-zones; loadRepo flattens them into
        // their top-level key, so "fishing" and "safari" cover all sub-pages.
        "Torrhus Canyon" to "foraging_3", "Lotus Atoll" to "lotus_atoll",
        "Critter Safari" to "safari"
    )

    @Volatile var allZoneMobNames: Set<String> = emptySet()
        private set

    @Volatile var activeMobNames: Set<String> = emptySet()
        private set

    private val httpClient = HttpClient.newHttpClient()
    private var tickCounter = 0

    /**
     * How to recognise a mob that has NO nametag (Hypixel renders some Torrhus
     * critters as plain scaled vanilla mobs). [type] is the entity registry
     * path ("bee"), width bounds are on the entity's bounding box.
     */
    /**
     * How to recognise a mob with no nametag: entity type, optional width
     * window, and an optional allow-list of variant keys (see [variantKey]),
     * because several Torrhus critters are the same vanilla mob in different
     * colours: the "Hideon..." shulkers, the tropical-fish species, axolotls.
     * Variant entries are "/"-separated and a segment of "*" matches anything,
     * e.g. "* / yellow / *" (without spaces) = any yellow-bodied tropical fish.
     */
    data class EntityRule(
        val type: String,
        val minWidth: Float = 0f,
        val maxWidth: Float = Float.MAX_VALUE,
        val variants: Set<String>? = null,
    )

    /**
     * Variant key of a vanilla mob that Hypixel re-skins by colour:
     *  shulker       -> shell colour               ("yellow")
     *  tropical_fish -> pattern/body/pattern colour ("kob/yellow/white")
     *  axolotl       -> variant                     ("lucy")
     * null for everything else. Printed by /fa entitydump as `variant=`.
     */
    fun variantKey(entity: net.minecraft.world.entity.Entity): String? = when (entity) {
        is net.minecraft.world.entity.monster.Shulker -> entity.color?.getName() ?: "none"
        is net.minecraft.world.entity.animal.fish.TropicalFish ->
            "${entity.pattern.getSerializedName()}/${entity.baseColor.getName()}/${entity.patternColor.getName()}"
        is net.minecraft.world.entity.animal.axolotl.Axolotl -> entity.variant.getName()
        is net.minecraft.world.entity.animal.parrot.Parrot -> entity.variant.getSerializedName()
        else -> null
    }

    private fun variantMatches(key: String, pattern: String): Boolean {
        val k = key.split("/"); val q = pattern.split("/")
        if (k.size != q.size) return false
        for (i in k.indices) if (q[i] != "*" && !q[i].equals(k[i], ignoreCase = true)) return false
        return true
    }

    private val ALL_DYE_COLORS: Set<String> = net.minecraft.world.item.DyeColor.values().map { it.getName() }.toSet()

    private data class MobEntry(
        val displayName: String,
        val mobIds: List<String>,
        val maxKills: Long,
        val entityRule: EntityRule? = null,
    )

    // Built-in nameless-mob rules, keyed by lowercase display name. Custom
    // file entries with entityType/minWidth/maxWidth override these.
    private val BUILTIN_ENTITY_RULES = mapOf(
        // Torrhus Canyon (from critter dumps 2026-09-07): both render as bare
        // vanilla mobs with no nametag at all.
        "beeheemoth" to EntityRule("bee", minWidth = 0.9f),   // giant scaled bee (vanilla bee is 0.7 wide)
        "drybark"    to EntityRule("creaking"),                // the walking dry tree
        // Entity dump 2026-09-08: plain shulkers (1.0 wide), no nametag. The
        // Hideon* critters differ only by shell colour; Hideonleaf is the green
        // one, so Hideonsun is any other colour until both are dumped exactly.
        "hideonsun"  to EntityRule("shulker", variants = ALL_DYE_COLORS - setOf("lime", "green")),
        "hideonleaf" to EntityRule("shulker", variants = setOf("lime", "green")),
        // Entity dump 2026-09-08 + screenshot: Solar is the yellow-bodied tropical fish.
        "solar"      to EntityRule("tropical_fish", variants = setOf("*/yellow/*")),
        "ember"      to EntityRule("tropical_fish", variants = setOf("*/orange/*")),
        "timil"      to EntityRule("tropical_fish", variants = setOf("*/pink/white", "*/white/pink")),
        // 2026-09-08: every axolotl on the island is a Sepialot; Dustybit is the frog.
        "sepialot"   to EntityRule("axolotl"),
        "dustybit"   to EntityRule("frog"),
        // Entity dump 2026-09-09: the Blue Jay is a bare parrot (0.5 wide) with an
        // invisible "/!\" armour stand above it; blue is the only parrot colour seen.
        "blue jay"   to EntityRule("parrot", variants = setOf("blue")),
        // Entity dump 2026-09-09: Duplico hides as a block — an INVISIBLE silverfish
        // (0.4 wide) under an item display + 1.1 interaction box at the same spot.
        // EntityHighlight draws a full block box for it since the mob never renders.
        "duplico"    to EntityRule("silverfish"),
        // Entity dump 2026-09-10 (Torrhus Canyon): Pangolin is a plain armadillo (0.7 wide).
        "pangolin"   to EntityRule("armadillo"),
    )

    /** Entity rules for every mob in the selected zone, keyed by display name. */
    @Volatile private var zoneEntityRules: Map<String, EntityRule> = emptyMap()

    private fun cleanName(raw: String) = raw.replace(Regex("§[0-9a-fk-or]"), "").trim()

    // ── Zone-scoped persistence ────────────────────────────────────────
    // maxedMobs / bestiaryCaps entries are keyed "<neuKey>/<Mob Name>". The
    // same family name exists in several zones with different caps and
    // different progress (Bat: Island 50 vs Catacombs 1000, Enderman: Island
    // 50 vs The End 3000), so a bare name is ambiguous. Legacy bare-name
    // entries are migrated once per session in [migrateLegacyKeys].

    private fun scoped(zoneKey: String, name: String) = "$zoneKey/$name"

    /** NEU key of the zone selected in the config, or null for None/unknown. */
    private fun currentZoneKey(): String? {
        val idx = resolvedZoneIndex()
        if (idx <= 0 || idx >= ZONES.size) return null
        return ZONE_TO_NEU_KEY[ZONES[idx]]
    }

    // ── Auto zone ──────────────────────────────────────────────────────
    // Dropdown index 0 = "Auto": the zone follows the area you stand in,
    // from the Hypixel Mod API when it has reported, else the tab list.
    // Polled every 20 ticks; -1 = area unknown or not a bestiary zone.
    @Volatile private var autoZoneIndex: Int = -1
    private var autoTicker = 0

    /** The zone in effect: the picked one, or the area-derived one under Auto. */
    fun resolvedZoneIndex(): Int {
        val sel = FamilyConfigManager.config.highlight.bestiaryZone
        return if (sel != 0) sel else autoZoneIndex
    }

    /** Human-readable auto state for debug output. */
    fun autoZoneName(): String = autoZoneIndex.takeIf { it > 0 }?.let { ZONES[it] } ?: "none"

    private fun zoneIndexForArea(area: String?, mode: String?): Int {
        val m = mode?.lowercase()
        if (m == "dungeon") return ZONES.indexOf("The Catacombs")
        if (m == "kuudra") return ZONES.indexOf("Kuudra")
        val a = area?.lowercase()?.trim() ?: return -1
        val name = when {
            a.contains("private island") || a == "your island" -> "Island"
            a.contains("dungeon hub") -> "Hub"
            a.contains("catacombs") || a == "dungeon" -> "The Catacombs"
            a.contains("hub") -> "Hub"
            a.contains("farming") -> "The Farming Lands"
            a.contains("garden") -> "The Garden"
            a.contains("spider") -> "Spider's Den"
            a == "the end" || a.endsWith(" end") -> "The End"
            a.contains("crimson") -> "Crimson Isle"
            a.contains("deep caverns") -> "Deep Caverns"
            a.contains("dwarven") -> "Dwarven Mines"
            a.contains("crystal hollows") -> "Crystal Hollows"
            a == "the park" || a == "park" -> "The Park"
            a.contains("galatea") || a.contains("moonglade") -> "Moonglade Marsh"
            a.contains("jerry") -> "Jerry"
            a.contains("kuudra") -> "Kuudra"
            a.contains("torrhus") -> "Torrhus Canyon"
            a.contains("lotus") -> "Lotus Atoll"
            a.contains("safari") -> "Critter Safari"
            else -> return -1
        }
        return ZONES.indexOf(name)
    }

    private fun pollAutoZone() {
        val next = zoneIndexForArea(HypixelLocation.areaName(), HypixelLocation.mode)
        if (next != autoZoneIndex) {
            autoZoneIndex = next
            FamilyAddons.LOGGER.info("BestiaryZoneHighlight: auto zone -> ${autoZoneName()} (area '${HypixelLocation.areaName()}', ${HypixelLocation.source()})")
        }
    }

    /** Names persisted as maxed for [zoneKey]. */
    private fun persistedMaxedFor(zoneKey: String): Set<String> {
        val prefix = "$zoneKey/"
        return FamilyConfigManager.config.highlight.maxedMobs
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toSet()
    }

    /**
     * Cap override chain: the user's own bestiary pages (learned, always the
     * freshest) → the snapshot bundled in the jar (bestiary_caps.json, read
     * from KyoWaa's pages, so fresh installs get corrections like Beeheemoth
     * without opening anything) → null, meaning "use the repo/custom value".
     */
    private fun learnedCap(zoneKey: String, name: String): Long? {
        val key = scoped(zoneKey, name)
        return FamilyConfigManager.config.highlight.bestiaryCaps[key] ?: bundledCaps[key]
    }

    private val bundledCaps: Map<String, Long> by lazy {
        try {
            val stream = BestiaryZoneHighlight::class.java.getResourceAsStream("/bestiary_caps.json")
                ?: return@lazy emptyMap()
            val root = JsonParser.parseString(stream.reader().readText()).asJsonObject
            val caps = root.getAsJsonObject("caps") ?: return@lazy emptyMap()
            caps.entrySet().associate { it.key to it.value.asLong }
                .also { FamilyAddons.LOGGER.info("BestiaryZoneHighlight: bundled caps loaded — ${it.size} entries") }
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("BestiaryZoneHighlight: bundled caps failed to load: ${e.message}")
            emptyMap()
        }
    }

    // Bestiary GUI page titles ("Bestiary ➜ Your Island", "(1/2) Bestiary ➜
    // Moonglade Marsh", "Fishing ➜ Lava", "Critter Safari ➜ Icy Biome") →
    // NEU zone key. Matched by prefix: Hypixel truncates long titles
    // ("Mythological Creatur").
    private val PAGE_TITLE_TO_KEY = listOf(
        "Your Island" to "dynamic", "Hub" to "hub", "The Farming Islands" to "farming_1",
        "The Garden" to "garden", "Spider's Den" to "combat_1", "The End" to "combat_3",
        "Crimson Isle" to "crimson_isle", "Deep Caverns" to "mining_2",
        "Dwarven Mines" to "mining_3", "Crystal Hollows" to "crystal_hollows",
        "The Park" to "foraging_1", "Moonglade Marsh" to "foraging_2",
        "Spooky Festival" to "spooky_festival", "The Catacombs" to "catacombs",
        "Mythological Creatur" to "mythological_creatures", "Jerry" to "jerry",
        "Kuudra" to "kuudra", "Torrhus Canyon" to "foraging_3", "Lotus Atoll" to "lotus_atoll",
    )

    /** Zone key for an open bestiary page title, or null if it is not a zone page. */
    private fun pageZoneKey(title: String): String? {
        val t = title.replace(Regex("""^\(\d+/\d+\)\s*"""), "").trim()
        if (t.startsWith("Fishing ➜")) return "fishing"
        if (t.startsWith("Critter Safari ➜")) return "safari"
        if (!t.startsWith("Bestiary ➜")) return null
        val sub = t.substringAfter("➜").trim()
        if (sub.isEmpty()) return null
        return PAGE_TITLE_TO_KEY.firstOrNull { sub.startsWith(it.first) }?.second
    }

    private var legacyMigrated = false

    /**
     * One-time upgrade of bare-name entries to zone-scoped keys. A name that
     * exists in exactly one zone is re-keyed; an ambiguous one (Bat, Enderman,
     * ...) or a non-mob (skill names the old tab-list parser picked up) is
     * dropped — the API/GUI passes re-add real ones within a refresh.
     */
    private fun migrateLegacyKeys() {
        if (legacyMigrated) return
        legacyMigrated = true
        val cfg = FamilyConfigManager.config.highlight
        val bareMaxed = cfg.maxedMobs.filter { !it.contains('/') }
        val bareCaps = cfg.bestiaryCaps.keys.filter { !it.contains('/') }
        if (bareMaxed.isEmpty() && bareCaps.isEmpty()) return

        val zonesByName = mutableMapOf<String, MutableSet<String>>()
        for ((zone, mobs) in repoData.entries + customData.entries) {
            for (m in mobs) {
                val n = cleanName(m.displayName)
                val mapped = NAME_REMAPS[n.lowercase()] ?: n
                zonesByName.getOrPut(mapped.lowercase()) { mutableSetOf() }.add(zone)
            }
        }
        fun target(name: String): String? {
            val zones = zonesByName[name.lowercase()] ?: return null
            return if (zones.size == 1) scoped(zones.first(), name) else null
        }

        var moved = 0; var dropped = 0
        for (name in bareMaxed) {
            cfg.maxedMobs.remove(name)
            val t = target(name)
            if (t != null) { cfg.maxedMobs.add(t); moved++ } else dropped++
        }
        for (name in bareCaps) {
            val cap = cfg.bestiaryCaps.remove(name) ?: continue
            val t = target(name)
            if (t != null) { cfg.bestiaryCaps[t] = cap; moved++ } else dropped++
        }
        FamilyConfigManager.save()
        FamilyAddons.LOGGER.info("BestiaryZoneHighlight: migrated legacy bestiary keys — $moved re-keyed by zone, $dropped ambiguous/unknown dropped")
    }

    /**
     * True if [entity] is a nameless mob that one of the ACTIVE zone mobs is
     * known to render as (see [EntityRule]). Called from EntityHighlight.
     */
    fun matchesNameless(entity: net.minecraft.world.entity.Entity): Boolean {
        val rules = zoneEntityRules
        if (rules.isEmpty()) return false
        val active = activeMobNames
        if (active.isEmpty()) return false
        val type = net.minecraft.world.entity.EntityType.getKey(entity.type).path
        val width = entity.bbWidth
        for ((name, rule) in rules) {
            if (name !in active) continue
            if (rule.type != type) continue
            if (width < rule.minWidth || width > rule.maxWidth) continue
            val variants = rule.variants
            if (variants != null) {
                val key = variantKey(entity) ?: continue
                if (variants.none { variantMatches(key, it) }) continue
            }
            return true
        }
        return false
    }

    private fun parseEntityRule(obj: JsonObject): EntityRule? {
        val type = obj.get("entityType")?.asString?.trim()?.lowercase()?.removePrefix("minecraft:") ?: return null
        if (type.isEmpty()) return null
        val variants = (obj.getAsJsonArray("variants") ?: obj.getAsJsonArray("shulkerColors"))
            ?.map { it.asString.lowercase() }?.toSet()?.takeIf { it.isNotEmpty() }
        return EntityRule(
            type,
            obj.get("minWidth")?.asFloat ?: 0f,
            obj.get("maxWidth")?.asFloat ?: Float.MAX_VALUE,
            variants,
        )
    }
    private var repoData: Map<String, List<MobEntry>> = emptyMap()
    private var repoLoaded = false
    private var neuBrackets: Map<Int, List<Long>> = emptyMap()

    // Local override file merged over the NEU repo — lets us add mobs Hypixel
    // released before the NEU repo catches up (or fix wrong entries).
    private var customData: Map<String, List<MobEntry>> = emptyMap()
    private var customLastModified = 0L

    private val NAME_REMAPS = mapOf("sneaky creeper" to "Creeper")

    // Track previous config to detect changes
    private var lastZoneIndex: Int = -1
    private var lastZoneHighlightEnabled: Boolean = false
    private var lastHideMaxed: Boolean = true

    /** Maxed mobs are always detected/persisted; whether they are hidden from
     *  the highlight set is the user's choice (Hide Maxed Mobs toggle). */
    private fun applyMaxFilter(all: Set<String>, maxed: Set<String>): Set<String> =
        if (FamilyConfigManager.config.highlight.hideMaxedMobs) all - maxed else all

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            val cfg = FamilyConfigManager.config.highlight

            if (cfg.bestiaryZone == 0 && cfg.zoneHighlightEnabled && ++autoTicker >= 20) { autoTicker = 0; pollAutoZone() }
            val zone = resolvedZoneIndex()

            val zoneChanged = zone != lastZoneIndex
            val enabledChanged = cfg.zoneHighlightEnabled != lastZoneHighlightEnabled
            val hideMaxedChanged = cfg.hideMaxedMobs != lastHideMaxed
            lastZoneIndex = zone
            lastZoneHighlightEnabled = cfg.zoneHighlightEnabled
            lastHideMaxed = cfg.hideMaxedMobs

            if (!cfg.zoneHighlightEnabled) {
                if (activeMobNames.isNotEmpty()) { activeMobNames = emptySet(); allZoneMobNames = emptySet() }
                return@register
            }
            if (zone <= 0) {
                // Auto with no recognised area (or a zone we do not track): nothing to highlight.
                if (activeMobNames.isNotEmpty()) { activeMobNames = emptySet(); allZoneMobNames = emptySet() }
                return@register
            }

            // Refresh immediately when zone or a toggle changes
            if (zoneChanged || enabledChanged || hideMaxedChanged) {
                tickCounter = 0
                FamilyAddons.LOGGER.info("BestiaryZoneHighlight: config changed — refreshing immediately")
                refresh()
                return@register
            }

            // Otherwise periodic refresh every 30s
            if (tickCounter++ % 600 != 0) return@register
            refresh()
        }

        ClientTickEvents.END_CLIENT_TICK.register { client -> captureTick(client) }
    }

    fun refresh() {
        CompletableFuture.runAsync {
            try {
                if (!repoLoaded) loadRepo()
                loadCustomIfChanged()

                val cfg = FamilyConfigManager.config.highlight
                val zoneIndex = resolvedZoneIndex()
                if (zoneIndex <= 0 || zoneIndex >= ZONES.size) { activeMobNames = emptySet(); return@runAsync }
                val zoneName = ZONES[zoneIndex]
                val neuKey = ZONE_TO_NEU_KEY[zoneName] ?: run {
                    FamilyAddons.LOGGER.warn("BestiaryZoneHighlight: no key mapped for '$zoneName'")
                    activeMobNames = emptySet()
                    return@runAsync
                }

                val zoneMobs = mergedZone(neuKey)
                if (zoneMobs.isEmpty()) {
                    FamilyAddons.LOGGER.warn("BestiaryZoneHighlight: zone '$neuKey' has no mobs in repo")
                    activeMobNames = emptySet()
                    return@runAsync
                }

                val fullSet = mutableSetOf<String>()
                val rules = mutableMapOf<String, EntityRule>()
                for (mob in zoneMobs) {
                    val cleanName = cleanName(mob.displayName)
                    val mapped = NAME_REMAPS[cleanName.lowercase()] ?: cleanName
                    fullSet.add(mapped)
                    (mob.entityRule ?: BUILTIN_ENTITY_RULES[cleanName.lowercase()])?.let { rules[mapped] = it }
                }
                allZoneMobNames = fullSet
                zoneEntityRules = rules
                FamilyAddons.LOGGER.info("BestiaryZoneHighlight: $zoneName zone loaded — ${fullSet.size} mobs: $fullSet")

                migrateLegacyKeys()

                // Apply persisted maxed mobs immediately
                val persistedMaxed = persistedMaxedFor(neuKey)
                run {
                    val filtered = applyMaxFilter(fullSet, persistedMaxed)
                    if (filtered != activeMobNames) {
                        activeMobNames = filtered
                        Minecraft.getInstance().execute { EntityHighlight.rescan() }
                        FamilyAddons.LOGGER.info("BestiaryZoneHighlight: persisted MAX applied — ${filtered.size} active")
                    }
                }

                checkMaxFromTablist()

                run {
                    val player = Minecraft.getInstance().player
                    if (player != null) {
                        val uuid = player.gameProfile.id.toString().replace("-", "")
                        val data = KeyFetcher.fetchProfiles(uuid)
                        if (data?.get("success")?.asBoolean == true) {
                            val profiles = data.getAsJsonArray("profiles")
                            val profile = profiles?.map { it.asJsonObject }
                                ?.firstOrNull { it.get("selected")?.asBoolean == true }
                                ?: profiles?.lastOrNull()?.asJsonObject
                            val member = profile?.getAsJsonObject("members")?.getAsJsonObject(uuid)
                            val killsObj = member?.getAsJsonObject("bestiary")?.getAsJsonObject("kills")
                            if (killsObj != null) {
                                FamilyAddons.LOGGER.info("BestiaryZoneHighlight: API killsObj keys sample: ${killsObj.keySet().take(5)}")
                                val apiMaxed = mutableSetOf<String>()
                                val provablyNotMaxed = mutableSetOf<String>()
                                for (mob in zoneMobs) {
                                    val cleanName = cleanName(mob.displayName)
                                    val mappedName = NAME_REMAPS[cleanName.lowercase()] ?: cleanName
                                    val total = mob.mobIds.sumOf { id -> killsObj.get(id)?.asLong ?: 0L }
                                    val learned = learnedCap(neuKey, cleanName)
                                    val cap = learned ?: mob.maxKills
                                    FamilyAddons.LOGGER.info("BestiaryZoneHighlight: mob '$cleanName' ids=${mob.mobIds} total=$total max=$cap${if (learned != null) " (learned)" else ""}")
                                    if (total >= cap) apiMaxed.add(mappedName)
                                    // A cap read from the real bestiary page beats any earlier
                                    // guess — if the API kills are below it, an old "maxed"
                                    // entry (e.g. from a wrong repo cap) must be dropped again.
                                    else if (learned != null) provablyNotMaxed.add(mappedName)
                                }
                                val persisted = persistedMaxedFor(neuKey)
                                val newApiMaxed = apiMaxed - persisted
                                val staleMaxed = provablyNotMaxed.filter { it in persisted }
                                if (newApiMaxed.isNotEmpty() || staleMaxed.isNotEmpty()) {
                                    newApiMaxed.forEach { cfg.maxedMobs.add(scoped(neuKey, it)) }
                                    staleMaxed.forEach { cfg.maxedMobs.remove(scoped(neuKey, it)) }
                                    FamilyConfigManager.save()
                                    if (newApiMaxed.isNotEmpty()) FamilyAddons.LOGGER.info("BestiaryZoneHighlight: persisted API maxed mobs: $newApiMaxed")
                                    if (staleMaxed.isNotEmpty()) FamilyAddons.LOGGER.info("BestiaryZoneHighlight: un-maxed (learned cap not reached): $staleMaxed")
                                }
                                val allMaxed = apiMaxed + persistedMaxedFor(neuKey)
                                val combined = applyMaxFilter(allZoneMobNames, allMaxed)
                                if (combined != activeMobNames) {
                                    activeMobNames = combined
                                    Minecraft.getInstance().execute { EntityHighlight.rescan() }
                                    FamilyAddons.LOGGER.info("BestiaryZoneHighlight: API MAX check → ${combined.size} active: $combined")
                                    if (apiMaxed.isNotEmpty()) FamilyAddons.LOGGER.info("BestiaryZoneHighlight: API maxed: $apiMaxed")
                                }
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("BestiaryZoneHighlight error: ${e.message}")
            }
        }
    }

    fun checkMaxFromTablist() {
        if (!FamilyConfigManager.config.highlight.zoneHighlightEnabled) return
        if (allZoneMobNames.isEmpty()) return

        val cfg = FamilyConfigManager.config.highlight
        val zoneKey = currentZoneKey() ?: return
        val maxed = readMaxedMobsFromTablist()

        // The tab list shows the island you are standing on, which need not
        // be the selected zone — only persist names that belong to the zone.
        val persisted = persistedMaxedFor(zoneKey)
        val newMaxed = (maxed - persisted).filter { it in allZoneMobNames }
        if (newMaxed.isNotEmpty()) {
            newMaxed.forEach { cfg.maxedMobs.add(scoped(zoneKey, it)) }
            FamilyConfigManager.save()
            FamilyAddons.LOGGER.info("BestiaryZoneHighlight: persisted new maxed mobs: $newMaxed")
        }

        val allMaxed = maxed + persistedMaxedFor(zoneKey)
        val filtered = applyMaxFilter(allZoneMobNames, allMaxed)
        if (filtered != activeMobNames) {
            activeMobNames = filtered
            Minecraft.getInstance().execute { EntityHighlight.rescan() }
            FamilyAddons.LOGGER.info("BestiaryZoneHighlight: MAX check → ${filtered.size} active: $filtered")
        }
    }

    private fun readMaxedMobsFromTablist(): Set<String> {
        val tabList = Minecraft.getInstance().connection?.onlinePlayers ?: return emptySet()
        val maxed = mutableSetOf<String>()
        val pattern = Regex("""^\s+(.+?)\s+(\d+):\s+MAX\s*$""", RegexOption.IGNORE_CASE)
        for (entry in tabList) {
            val raw = entry.tabListDisplayName?.string ?: continue
            val clean = raw.replace(COLOR_CODE_REGEX, "")
            val match = pattern.matchEntire(clean) ?: continue
            val mobNameRaw = match.groupValues[1].trim()
            if (mobNameRaw.isNotBlank()) maxed.add(NAME_REMAPS[mobNameRaw.lowercase()] ?: mobNameRaw)
        }
        return maxed
    }

    /** NEU repo entries with custom-file entries merged on top (same name = replace). */
    private fun mergedZone(neuKey: String): List<MobEntry> {
        val base = repoData[neuKey].orEmpty()
        val custom = customData[neuKey].orEmpty()
        if (custom.isEmpty()) return base
        val byName = LinkedHashMap<String, MobEntry>()
        base.forEach { byName[it.displayName.lowercase()] = it }
        custom.forEach { byName[it.displayName.lowercase()] = it }
        return byName.values.toList()
    }

    private fun customFile() =
        File(Minecraft.getInstance().gameDirectory, "config/familyaddons/custom_bestiary.json")

    /**
     * Loads config/familyaddons/custom_bestiary.json. Re-read whenever the
     * file's mtime changes, so edits are picked up on the next refresh (≤30s)
     * without restarting. Creates a documented template on first run.
     *
     * Format: top-level keys are NEU zone keys (see ZONE_TO_NEU_KEY); keys
     * starting with "_" are ignored (used for docs). Each zone has a "mobs"
     * array of { name, mobs?, maxKills? }:
     *  - name: exact in-game mob name (no level/health decorations)
     *  - mobs: Hypixel API bestiary kill ids — only needed for API max detection
     *  - maxKills: kills for MAX — omitted = never maxed via API (tab-list MAX
     *    detection still works and needs neither field)
     */
    private fun loadCustomIfChanged() {
        val file = customFile()
        if (!file.exists()) {
            writeCustomTemplate(file)
            return
        }
        val mtime = file.lastModified()
        if (mtime == customLastModified) return
        customLastModified = mtime
        try {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val result = mutableMapOf<String, List<MobEntry>>()
            for ((zoneKey, zoneVal) in root.entrySet()) {
                if (zoneKey.startsWith("_")) continue
                val arr = (zoneVal as? JsonObject)?.getAsJsonArray("mobs") ?: continue
                val entries = mutableListOf<MobEntry>()
                arr.forEach { el ->
                    val obj = el.asJsonObject
                    val name = obj.get("name")?.asString?.trim() ?: return@forEach
                    if (name.isEmpty()) return@forEach
                    val ids = obj.getAsJsonArray("mobs")?.map { it.asString }
                        ?: listOf(name.lowercase().replace(" ", "_"))
                    val maxKills = obj.get("maxKills")?.asLong
                        ?: obj.get("bracket")?.asInt?.let { neuBrackets[it]?.lastOrNull() }
                        ?: Long.MAX_VALUE
                    entries.add(MobEntry(name, ids, maxKills, parseEntityRule(obj)))
                }
                if (entries.isNotEmpty()) result[zoneKey] = entries
            }
            customData = result
            FamilyAddons.LOGGER.info(
                "BestiaryZoneHighlight: custom bestiary loaded — " +
                result.entries.joinToString { "${it.key}: ${it.value.map { m -> m.displayName }}" }
                    .ifEmpty { "no entries" }
            )
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("BestiaryZoneHighlight: custom_bestiary.json parse failed: ${e.message}")
        }
    }

    private fun writeCustomTemplate(file: File) {
        try {
            file.parentFile.mkdirs()
            file.writeText(
                """
                {
                  "_readme": [
                    "Add bestiary mobs that are missing from the NEU repo (or override wrong ones).",
                    "Top-level keys are zone keys: dynamic (Private Island), hub, farming_1, garden,",
                    "combat_1 (Spider's Den), combat_3 (The End), crimson_isle, mining_2 (Deep Caverns),",
                    "mining_3 (Dwarven Mines), crystal_hollows, foraging_1 (The Park), foraging_2 (Galatea),",
                    "spooky_festival, catacombs, fishing, mythological_creatures, jerry, kuudra.",
                    "Keys starting with _ are ignored. Each zone holds a 'mobs' array; per mob:",
                    "  name     = exact in-game mob name without level/health decorations (required)",
                    "  mobs     = Hypixel API bestiary kill ids (optional; only needed so the API can detect MAX)",
                    "  maxKills = kills needed for MAX (optional; omit it and only tab-list MAX detection applies)",
                    "  entityType/minWidth/maxWidth = for mobs WITHOUT a nametag: vanilla entity id (e.g. bee) and",
                    "             an optional bounding-box width range, so the highlight can find them by shape.",
                    "Caps are also learned automatically from the bestiary menu whenever you open a zone page,",
                    "and a learned cap always wins over the repo/custom value.",
                    "A custom mob with the same name as a NEU repo mob replaces the repo entry.",
                    "Changes are picked up automatically within ~30 seconds while the game runs.",
                    "Copy the shape below into a real zone key (e.g. foraging_2) to use it."
                  ],
                  "_example": {
                    "foraging_2": {
                      "mobs": [
                        { "name": "Some New Mob", "mobs": ["some_new_mob_100"], "maxKills": 100000 }
                      ]
                    }
                  }
                }
                """.trimIndent()
            )
            FamilyAddons.LOGGER.info("BestiaryZoneHighlight: wrote custom_bestiary.json template")
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("BestiaryZoneHighlight: couldn't write template: ${e.message}")
        }
    }

    private fun loadRepo() {
        try {
            val json = getRaw("https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/bestiary.json") ?: return
            val root = JsonParser.parseString(json).asJsonObject
            val result = mutableMapOf<String, List<MobEntry>>()
            val brackets = mutableMapOf<Int, List<Long>>()
            root.getAsJsonObject("brackets")?.let { bracketsObj ->
                for ((k, v) in bracketsObj.entrySet()) {
                    val num = k.toIntOrNull() ?: continue
                    brackets[num] = v.asJsonArray.map { it.asLong }
                }
            }
            neuBrackets = brackets
            for ((zoneKey, zoneVal) in root.entrySet()) {
                if (zoneKey == "brackets") continue
                val entries = mutableListOf<MobEntry>()
                fun parseMobsArray(obj: com.google.gson.JsonObject) {
                    obj.getAsJsonArray("mobs")?.forEach { mobEl ->
                        val mobObj = mobEl.asJsonObject
                        val name = mobObj.get("name")?.asString ?: return@forEach
                        val mobIds = mobObj.getAsJsonArray("mobs")?.map { it.asString }
                            ?: listOf(name.lowercase().replace(" ", "_"))
                        // "cap" is the kill count for MAX (e.g. Bat: cap 50 = the "50/50"
                        // shown in-game). The bracket's last tier is only a fallback —
                        // most families max well before it (Beeheemoth: cap 25 in a
                        // bracket ending at 10,000), so using the bracket end made the
                        // API path never report MAX on zones without a tab-list section.
                        val bracket = mobObj.get("bracket")?.asInt ?: 1
                        val tierList = brackets[bracket] ?: listOf(250L)
                        val maxKills = mobObj.get("cap")?.asLong?.takeIf { it > 0 } ?: tierList.last()
                        entries.add(MobEntry(name, mobIds, maxKills))
                    }
                }
                try {
                    val zoneObj = zoneVal.asJsonObject
                    if (zoneObj.has("mobs")) {
                        parseMobsArray(zoneObj)
                    } else {
                        for ((_, subVal) in zoneObj.entrySet()) {
                            try {
                                val subObj = subVal.asJsonObject
                                if (subObj.has("mobs")) parseMobsArray(subObj)
                                else for ((_, subSubVal) in subObj.entrySet()) {
                                    try { parseMobsArray(subSubVal.asJsonObject) } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
                if (entries.isNotEmpty()) result[zoneKey] = entries
            }
            repoData = result
            repoLoaded = true
            FamilyAddons.LOGGER.info("BestiaryZoneHighlight: repo loaded — ${result.size} zones")
            for ((zone, mobs) in result) {
                FamilyAddons.LOGGER.info("BestiaryZoneHighlight: zone '$zone' mobs: ${mobs.map { it.displayName }}")
            }
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("BestiaryZoneHighlight: repo load failed: ${e.message}")
        }
    }

    // ── Bestiary page capture ─────────────────────────────────
    // Commands can't be typed while a container is open, so /fa bestiarydump
    // toggles capture mode instead: while on, every container page the player
    // opens is scanned shortly after its items arrive and dumped if it looks
    // like a bestiary page (entries with Kills/Deaths lore).
    private var captureMode = false
    private var lastSeenContainerId = -1
    private var lastDumpedContainerId = -1
    private var lastSlotSig = 0
    private var stableTicks = 0
    private var pagesDumped = 0
    private var sessionNewMobs = 0

    fun toggleCapture() {
        captureMode = !captureMode
        if (captureMode) {
            lastDumpedContainerId = -1
            pagesDumped = 0
            sessionNewMobs = 0
            chat("§aBestiary capture ON§7 — open §e/bestiary§7 and click through the zone pages. Wait for the §a✔ done§7 message before switching pages. Run §e/fa bestiarydump§7 again to stop.")
        } else {
            chat("§cBestiary capture OFF§7 — §f$pagesDumped§7 pages dumped, §e$sessionNewMobs§7 new mobs found. Report: §fconfig/familyaddons/bestiary_dump.txt")
        }
    }

    private fun captureTick(client: Minecraft) {
        // Runs always: outside capture mode an open bestiary page is still
        // read silently to learn caps / MAX state (see learnFromEntries).
        val player = client.player ?: return
        val menu = player.containerMenu
        if (menu === player.inventoryMenu) {
            lastSeenContainerId = -1
            return
        }
        if (menu.containerId != lastSeenContainerId) {
            lastSeenContainerId = menu.containerId
            lastSlotSig = 0
            stableTicks = 0
            return
        }
        if (menu.containerId == lastDumpedContainerId) return

        // Hypixel fills container slots over several ticks — don't snapshot
        // until the set of items has been unchanged for 10 consecutive ticks,
        // so a half-loaded page is never dumped.
        var sig = 0
        var filled = 0
        for (slot in menu.slots) {
            if (slot.container === player.inventory) continue
            val stack = slot.item
            if (stack.isEmpty) continue
            filled++
            sig = sig * 31 + slot.index
            sig = sig * 31 + stack.hoverName.string.hashCode()
        }
        if (filled == 0 || sig != lastSlotSig) {
            lastSlotSig = sig
            stableTicks = 0
            return
        }
        if (++stableTicks >= 10) {
            lastDumpedContainerId = menu.containerId
            if (captureMode) dumpOpenContainer(silent = true) else learnFromOpenContainer()
        }
    }

    private data class GuiMob(val base: String, val rawName: String, val lore: List<String>, val locked: Boolean)

    private val ROMAN_TIER = Regex("""\s+[IVXLCDM]+$""")
    private val KILLS_LINE = Regex("""^\s*Kills:\s*([\d,]+)\s*$""")
    private val RATIO_LINE = Regex("""^\s*([\d,]+)\s*/\s*([\d,]+)\s*$""")

    private fun openScreenTitle(): String =
        Minecraft.getInstance().gui.screen()?.title?.string?.replace(COLOR_CODE_REGEX, "")?.trim() ?: "Unknown"

    /** Mob entries on the currently open container page (main thread only). */
    private fun snapshotEntries(): List<GuiMob> {
        val player = Minecraft.getInstance().player ?: return emptyList()
        val menu = player.containerMenu
        if (menu === player.inventoryMenu) return emptyList()
        val entries = mutableListOf<GuiMob>()
        for (slot in menu.slots) {
            if (slot.container === player.inventory) continue
            val stack = slot.item
            if (stack.isEmpty) continue
            val name = stack.hoverName.string.replace(COLOR_CODE_REGEX, "").trim()
            if (name.isEmpty()) continue
            val lore = stack.get(net.minecraft.core.component.DataComponents.LORE)
                ?.lines?.map { it.string.replace(COLOR_CODE_REGEX, "") } ?: emptyList()
            // Unlocked mob entries carry kill/death stats; locked families say
            // "Kill a mob belonging to this Family to unlock it". Everything
            // else (panes, nav arrows, search, milestones) has neither.
            val loreText = lore.joinToString("\n")
            val unlocked = loreText.contains("Kills", ignoreCase = true) ||
                    loreText.contains("Deaths", ignoreCase = true)
            val locked = !unlocked && (
                    loreText.contains("unlock it in your Bestiary", ignoreCase = true) ||
                    loreText.contains("haven't unlocked this Family", ignoreCase = true))
            if (!unlocked && !locked) continue
            val base = name.replace(ROMAN_TIER, "").trim()
            if (base.isEmpty() || base == "???") continue
            entries.add(GuiMob(base, name, lore, locked))
        }
        return entries
    }

    /**
     * The bestiary page is the ground truth for MAX and for the kill cap:
     *
     *     Overall Progress: 71%            Overall Progress: 100% (MAX!)
     *                        71/100                            50/50
     *
     * The second number of the ratio line under "Overall Progress" is the
     * kills needed for MAX. Persist it per mob (it overrides the NEU repo,
     * whose caps are sometimes stale) and sync the maxed set both ways: a
     * "(MAX!)" page marks the mob maxed, a page below the cap un-marks it.
     */
    private fun learnFromEntries(entries: List<GuiMob>, zoneKey: String?) {
        if (zoneKey == null) return   // not a zone page we can attribute
        val cfg = FamilyConfigManager.config.highlight
        var changed = false
        val learned = mutableListOf<String>()
        for (e in entries) {
            if (e.locked) continue
            val name = scoped(zoneKey, NAME_REMAPS[e.base.lowercase()] ?: e.base)
            val progressIdx = e.lore.indexOfFirst { it.trim().startsWith("Overall Progress") }
            if (progressIdx < 0) continue
            val progressLine = e.lore[progressIdx]
            val isMax = progressLine.contains("(MAX!)") || progressLine.contains("100%")

            // Ratio line: first non-blank line after "Overall Progress".
            val ratio = e.lore.drop(progressIdx + 1).firstOrNull { it.isNotBlank() }
                ?.let { RATIO_LINE.find(it) }
            val cap = ratio?.groupValues?.get(2)?.replace(",", "")?.toLongOrNull()?.takeIf { it > 0 }
            if (cap != null && cfg.bestiaryCaps[name] != cap) {
                cfg.bestiaryCaps[name] = cap
                learned.add("$name=$cap")
                changed = true
            }

            if (isMax) {
                if (cfg.maxedMobs.add(name)) changed = true
            } else if (cfg.maxedMobs.remove(name)) {
                FamilyAddons.LOGGER.info("BestiaryZoneHighlight: '$name' is not maxed per bestiary page — removed from maxed set")
                changed = true
            }
        }
        if (changed) {
            FamilyConfigManager.save()
            if (learned.isNotEmpty()) FamilyAddons.LOGGER.info("BestiaryZoneHighlight: learned caps from bestiary page: $learned")
            // Re-apply the max filter with the corrected data if this page is
            // the selected zone.
            val current = currentZoneKey()
            if (current == zoneKey) {
                val filtered = applyMaxFilter(allZoneMobNames, persistedMaxedFor(current))
                if (filtered != activeMobNames) activeMobNames = filtered
                Minecraft.getInstance().execute { EntityHighlight.rescan() }
            }
        }
    }

    /** Silent learn pass for any open bestiary page (runs outside capture mode). */
    private fun learnFromOpenContainer() {
        val zoneKey = pageZoneKey(openScreenTitle()) ?: return
        val entries = snapshotEntries()
        if (entries.isNotEmpty()) learnFromEntries(entries, zoneKey)
    }

    /**
     * Read the currently open container page, log every mob entry (name +
     * lore) to config/familyaddons/bestiary_dump.txt, cross-check the names
     * against the NEU repo + custom file, and try to match new mobs to
     * Hypixel API kill ids by name. Appends per page.
     */
    fun dumpOpenContainer(silent: Boolean = false) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val menu = player.containerMenu
        if (menu === player.inventoryMenu) {
            if (!silent) chat("§cNo container open. Use §e/fa bestiarydump§c to arm capture mode, then open §e/bestiary§c.")
            return
        }
        val title = openScreenTitle()

        // Snapshot on the main thread; process/fetch async.
        val entries = snapshotEntries()
        if (entries.isEmpty()) {
            // In capture mode most menus (bestiary navigation, unrelated
            // chests) legitimately have no mob entries — stay quiet.
            if (!silent) chat("§cNo bestiary mob entries found in '$title' — open a zone's mob page.")
            return
        }
        learnFromEntries(entries, pageZoneKey(title))
        chat("§7Read §f${entries.size}§7 mobs from '§e$title§7', cross-checking...")

        CompletableFuture.runAsync {
            try {
                if (!repoLoaded) loadRepo()
                loadCustomIfChanged()

                val knownNames = buildSet {
                    (repoData.values + customData.values).flatten().forEach {
                        add(it.displayName.replace(Regex("§[0-9a-fk-or]"), "").trim().lowercase())
                    }
                    NAME_REMAPS.values.forEach { add(it.lowercase()) }
                }
                val missing = entries.filter { it.base.lowercase() !in knownNames }

                // Try to find API kill ids for the missing mobs by name pattern.
                var killsObj: JsonObject? = null
                if (missing.isNotEmpty()) {
                    val uuid = player.gameProfile.id.toString().replace("-", "")
                    val data = KeyFetcher.fetchProfiles(uuid)
                    if (data?.get("success")?.asBoolean == true) {
                        val profiles = data.getAsJsonArray("profiles")
                        val profile = profiles?.map { it.asJsonObject }
                            ?.firstOrNull { it.get("selected")?.asBoolean == true }
                            ?: profiles?.lastOrNull()?.asJsonObject
                        killsObj = profile?.getAsJsonObject("members")?.getAsJsonObject(uuid)
                            ?.getAsJsonObject("bestiary")?.getAsJsonObject("kills")
                    }
                }

                val sb = StringBuilder()
                sb.append("==== Bestiary dump: $title @ ${java.time.LocalDateTime.now()} ====\n\n")
                for (e in entries) {
                    sb.append("[GUI] ${e.rawName}  (base name: ${e.base})${if (e.locked) "  [LOCKED FAMILY]" else ""}\n")
                    e.lore.forEach { if (it.isNotBlank()) sb.append("      $it\n") }
                    sb.append('\n')
                }

                // The GUI itself is a completion source: with Hypixel's
                // "Overall Progress" enabled, maxed families show
                // "Overall Progress: 100% (MAX!)" in their lore. Persist those
                // like the tab-list/API paths do — works for any mob, no data
                // needed.
                val guiMaxed = entries
                    .filter { e -> e.lore.any { it.contains("(MAX!)") || it.contains("Overall Progress: 100%") } }
                    .map { NAME_REMAPS[it.base.lowercase()] ?: it.base }
                    .toSet()
                if (guiMaxed.isNotEmpty()) {
                    // Persistence already happened in learnFromEntries (zone-scoped);
                    // this is just the report.
                    sb.append("-- Maxed (read from GUI lore) --\n")
                    guiMaxed.forEach { sb.append("MAXED: $it\n") }
                    sb.append('\n')
                }

                sb.append("-- Cross-check against NEU repo + custom file --\n")
                if (missing.isEmpty()) {
                    sb.append("All ${entries.size} mobs already known.\n")
                } else {
                    missing.forEach { sb.append("MISSING: ${it.base}${if (it.locked) " (locked — name only, no kill data available)" else ""}\n") }
                }

                val suggestions = mutableListOf<String>()
                if (missing.isNotEmpty()) {
                    sb.append("\n-- API id search for missing mobs --\n")
                    for (m in missing) {
                        val guess = m.base.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                        val matches = killsObj?.keySet()
                            ?.filter { it == guess || it.startsWith("${guess}_") }
                            ?.sorted() ?: emptyList()
                        if (matches.isEmpty()) {
                            sb.append("${m.base}: no api keys match '$guess' — either 0 kills ever, or a different internal id\n")
                            suggestions.add("""{ "name": "${m.base}", "mobs": ["$guess"], "maxKills": FILL_ME }""")
                        } else {
                            sb.append("${m.base}: ${matches.joinToString { "$it = ${killsObj?.get(it)?.asLong ?: 0} kills" }}\n")
                            val ids = matches.joinToString(", ") { "\"$it\"" }
                            suggestions.add("""{ "name": "${m.base}", "mobs": [$ids], "maxKills": FILL_ME }""")
                        }
                    }
                    sb.append("\n-- Ready-to-paste custom_bestiary.json entries (fix maxKills from the bestiary menu) --\n")
                    suggestions.forEach { sb.append("$it\n") }
                }
                sb.append("\n\n")

                val file = File(Minecraft.getInstance().gameDirectory, "config/familyaddons/bestiary_dump.txt")
                file.parentFile.mkdirs()
                file.appendText(sb.toString())

                pagesDumped++
                sessionNewMobs += missing.size
                chat("§a✔ Done dumping '§e$title§a' §7— §f${entries.size}§7 mobs, §e${missing.size} new§7 (page §f$pagesDumped§7, session new: §e$sessionNewMobs§7). §aGo to the next page!")
                if (missing.isNotEmpty()) {
                    missing.take(10).forEach { chat("  §cnew: §f${it.base}") }
                    chat("§7Paste-ready JSON entries are at the bottom of the report.")
                }
            } catch (e: Exception) {
                chat("§cDump error: ${e.message}")
                FamilyAddons.LOGGER.warn("BestiaryZoneHighlight dump error", e)
            }
        }
    }

    /**
     * /fa bestiaryids [filter] — dump the raw bestiary kill ids from the
     * Hypixel API so new mobs' ids can be found for custom_bestiary.json:
     * kill the mob once, then run this and look for the id with a low count.
     */
    fun dumpKillIds(filter: String) {
        val player = Minecraft.getInstance().player ?: return
        chat("§7Fetching bestiary kill ids...")
        CompletableFuture.runAsync {
            try {
                val uuid = player.gameProfile.id.toString().replace("-", "")
                val data = KeyFetcher.fetchProfiles(uuid)
                if (data?.get("success")?.asBoolean != true) { chat("§cAPI error."); return@runAsync }
                val profiles = data.getAsJsonArray("profiles")
                val profile = profiles?.map { it.asJsonObject }
                    ?.firstOrNull { it.get("selected")?.asBoolean == true }
                    ?: profiles?.lastOrNull()?.asJsonObject
                val killsObj = profile?.getAsJsonObject("members")?.getAsJsonObject(uuid)
                    ?.getAsJsonObject("bestiary")?.getAsJsonObject("kills")
                    ?: run { chat("§cNo bestiary data on your profile."); return@runAsync }

                val f = filter.trim().lowercase()
                val keys = killsObj.keySet()
                    .filter { f.isEmpty() || it.lowercase().contains(f) }
                    .sorted()
                if (keys.isEmpty()) {
                    chat("§cNo kill ids matching '§e$filter§c'.")
                } else {
                    chat("§eBestiary kill ids${if (f.isEmpty()) "" else " matching '§f$filter§e'"} (§f${keys.size}§e):")
                    keys.take(40).forEach { chat("  §b$it §7= §f${killsObj.get(it)?.asLong ?: 0} kills") }
                    if (keys.size > 40) chat("§7...and ${keys.size - 40} more — narrow the filter.")
                }
            } catch (e: Exception) {
                chat("§cFetch error: ${e.message}")
            }
        }
    }

    private fun chat(msg: String) {
        Minecraft.getInstance().execute {
            Minecraft.getInstance().player?.sendSystemMessage(
                FaChat.prefixed("$msg")
            )
        }
    }

    private fun get(url: String) = try {
        val req = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "FamilyAddons/1.0").GET().build()
        JsonParser.parseString(httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body()).asJsonObject
    } catch (e: Exception) { null }

    private fun getRaw(url: String) = try {
        val req = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "FamilyAddons/1.0").GET().build()
        httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body()
    } catch (e: Exception) { null }
}