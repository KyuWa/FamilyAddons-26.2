package org.kyowa.familyaddons.features

import com.google.gson.JsonObject
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

    private data class MobEntry(val displayName: String, val mobIds: List<String>, val maxKills: Long)
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

            val zoneChanged = cfg.bestiaryZone != lastZoneIndex
            val enabledChanged = cfg.zoneHighlightEnabled != lastZoneHighlightEnabled
            val hideMaxedChanged = cfg.hideMaxedMobs != lastHideMaxed
            lastZoneIndex = cfg.bestiaryZone
            lastZoneHighlightEnabled = cfg.zoneHighlightEnabled
            lastHideMaxed = cfg.hideMaxedMobs

            if (!cfg.zoneHighlightEnabled) {
                if (activeMobNames.isNotEmpty()) { activeMobNames = emptySet(); allZoneMobNames = emptySet() }
                return@register
            }
            if (cfg.bestiaryZone == 0) {
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
                val zoneIndex = cfg.bestiaryZone
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
                for (mob in zoneMobs) {
                    val cleanName = mob.displayName.replace(Regex("§[0-9a-fk-or]"), "").trim()
                    fullSet.add(NAME_REMAPS[cleanName.lowercase()] ?: cleanName)
                }
                allZoneMobNames = fullSet
                FamilyAddons.LOGGER.info("BestiaryZoneHighlight: $zoneName zone loaded — ${fullSet.size} mobs: $fullSet")

                // Apply persisted maxed mobs immediately
                val persistedMaxed = cfg.maxedMobs
                run {
                    val filtered = applyMaxFilter(fullSet, persistedMaxed)
                    if (filtered != activeMobNames) {
                        activeMobNames = filtered
                        Minecraft.getInstance().execute { EntityHighlight.rescan() }
                        FamilyAddons.LOGGER.info("BestiaryZoneHighlight: persisted MAX applied — ${filtered.size} active")
                    }
                }

                checkMaxFromTablist()

                val apiKey = KeyFetcher.getApiKey()
                if (!apiKey.isNullOrBlank()) {
                    val player = Minecraft.getInstance().player
                    if (player != null) {
                        val uuid = player.gameProfile.id.toString().replace("-", "")
                        val data = get("https://api.hypixel.net/v2/skyblock/profiles?uuid=$uuid&key=$apiKey")
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
                                for (mob in zoneMobs) {
                                    val cleanName = mob.displayName.replace(Regex("§[0-9a-fk-or]"), "").trim()
                                    val mappedName = NAME_REMAPS[cleanName.lowercase()] ?: cleanName
                                    val total = mob.mobIds.sumOf { id -> killsObj.get(id)?.asLong ?: 0L }
                                    FamilyAddons.LOGGER.info("BestiaryZoneHighlight: mob '$cleanName' ids=${mob.mobIds} total=$total max=${mob.maxKills}")
                                    if (total >= mob.maxKills) apiMaxed.add(mappedName)
                                }
                                val newApiMaxed = apiMaxed - cfg.maxedMobs
                                if (newApiMaxed.isNotEmpty()) {
                                    cfg.maxedMobs.addAll(newApiMaxed)
                                    FamilyConfigManager.save()
                                    FamilyAddons.LOGGER.info("BestiaryZoneHighlight: persisted API maxed mobs: $newApiMaxed")
                                }
                                val allMaxed = apiMaxed + cfg.maxedMobs
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
        val maxed = readMaxedMobsFromTablist()

        val newMaxed = maxed - cfg.maxedMobs
        if (newMaxed.isNotEmpty()) {
            cfg.maxedMobs.addAll(newMaxed)
            FamilyConfigManager.save()
            FamilyAddons.LOGGER.info("BestiaryZoneHighlight: persisted new maxed mobs: $newMaxed")
        }

        val allMaxed = maxed + cfg.maxedMobs
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
                    entries.add(MobEntry(name, ids, maxKills))
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
                        val bracket = mobObj.get("bracket")?.asInt ?: 1
                        val tierList = brackets[bracket] ?: listOf(250L)
                        entries.add(MobEntry(name, mobIds, tierList.last()))
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
        if (!captureMode) return
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
            dumpOpenContainer(silent = true)
        }
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
        val title = mc.gui.screen()?.title?.string?.replace(COLOR_CODE_REGEX, "")?.trim() ?: "Unknown"

        // Snapshot on the main thread; process/fetch async.
        data class GuiMob(val base: String, val rawName: String, val lore: List<String>, val locked: Boolean)
        val romanTier = Regex("""\s+[IVXLCDM]+$""")
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
            val base = name.replace(romanTier, "").trim()
            if (base.isEmpty() || base == "???") continue
            entries.add(GuiMob(base, name, lore, locked))
        }
        if (entries.isEmpty()) {
            // In capture mode most menus (bestiary navigation, unrelated
            // chests) legitimately have no mob entries — stay quiet.
            if (!silent) chat("§cNo bestiary mob entries found in '$title' — open a zone's mob page.")
            return
        }
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
                val apiKey = KeyFetcher.getApiKey()
                if (!apiKey.isNullOrBlank() && missing.isNotEmpty()) {
                    val uuid = player.gameProfile.id.toString().replace("-", "")
                    val data = get("https://api.hypixel.net/v2/skyblock/profiles?uuid=$uuid&key=$apiKey")
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
                    val cfg = FamilyConfigManager.config.highlight
                    val newMaxed = guiMaxed - cfg.maxedMobs
                    if (newMaxed.isNotEmpty()) {
                        cfg.maxedMobs.addAll(newMaxed)
                        FamilyConfigManager.save()
                        Minecraft.getInstance().execute { EntityHighlight.rescan() }
                        FamilyAddons.LOGGER.info("BestiaryZoneHighlight: GUI dump maxed: $newMaxed")
                    }
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
        val apiKey = KeyFetcher.getApiKey()
        if (apiKey.isNullOrBlank()) { chat("§cNo API key set. Set it in /fa > General."); return }
        chat("§7Fetching bestiary kill ids...")
        CompletableFuture.runAsync {
            try {
                val uuid = player.gameProfile.id.toString().replace("-", "")
                val data = get("https://api.hypixel.net/v2/skyblock/profiles?uuid=$uuid&key=$apiKey")
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
                net.minecraft.network.chat.Component.literal("§6[FA] $msg")
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