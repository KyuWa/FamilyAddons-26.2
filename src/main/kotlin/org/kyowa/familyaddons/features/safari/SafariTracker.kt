package org.kyowa.familyaddons.features.safari

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.util.FaChat
import org.kyowa.familyaddons.util.HypixelLocation

/**
 * Tracks which Critter Safari species each player has caught this run, per biome.
 *
 * Your own catches come from `CAPTURE!`; a partymate's come from the `LOOT SHARE!`
 * line that names them, which is the only thing the client is ever told about someone
 * else's catch. That means the party view is only as complete as loot share: a
 * partymate outside your loot-share range is invisible, and so is anything caught
 * before you joined. The counts are of unique species, so a second Gemzie adds
 * nothing.
 *
 * Chat wordings and the species roster come from Critter Safari Tracker by Rok
 * (MIT), see [SafariCritters].
 */
object SafariTracker {

    /** player name -> the species they have caught this run. Insertion ordered. */
    private val caught = LinkedHashMap<String, MutableSet<String>>()

    /** "player|BIOME" keys already announced, so a biome is only called once. */
    private val announcedPlayer = HashSet<String>()
    private val announcedParty = HashSet<SafariBiome>()

    /** Every catch, duplicates included, per player. */
    private val totals = LinkedHashMap<String, Int>()

    /** Biome of each player's most recent catch, used to break ties for their biome. */
    private val lastBiome = HashMap<String, SafariBiome>()

    private var runStartMs = 0L

    // ── chat shapes ───────────────────────────────────────────────────────

    /** "...from <player> catching a Gemzie!" / "...from <player> finding Hideyho!" */
    private val LOOT_SHARE_CATCHER = Regex("""from\s+(\w{1,16})\s+(?:catching|finding)\b""")

    /** "[MVP+] Name entered Critter Safari!" — the run boundary. */
    private val ENTERED = Regex("""^(?:\[[^\]]+]\s*)?(\w{1,16}) entered Critter Safari!$""")

    /**
     * Lines a player typed rather than the game sent. Anything a player can type, a
     * player can quote, so none of the matching below may run on these.
     */
    private val PLAYER_SAID = Regex(
        """^(?:Party|Guild|Officer|Co-op|Team|Friend) >.*""" +
            """|^(?:From|To) .*""" +
            """|^(?:\[(?!NPC]|MOB])[^\]]+]\s*)?\w{1,16}: .*"""
    )

    fun register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            if (!overlay) {
                runCatching { onChat(message.string.replace(COLOR_CODE_REGEX, "").trim()) }
            }
            true
        }
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("familyaddons", "safari_hud"),
            HudElement { ctx, _ -> runCatching { renderHud(ctx) } }
        )
    }

    // ── parsing ───────────────────────────────────────────────────────────

    private fun onChat(plain: String) {
        if (!FamilyConfigManager.config.safari.enabled) return
        if (plain.isEmpty() || PLAYER_SAID.matches(plain)) return

        ENTERED.find(plain)?.let { m ->
            if (m.groupValues[1].equals(selfName(), ignoreCase = true)) reset(announce = true)
            return
        }

        if (plain.startsWith("CAPTURE!")) {
            val critter = SafariCritters.findIn(plain) ?: return
            record(selfName(), critter)
            return
        }

        if (plain.startsWith("LOOT SHARE!")) {
            val critter = SafariCritters.findIn(plain) ?: return
            val catcher = LOOT_SHARE_CATCHER.find(plain)?.groupValues?.get(1) ?: return
            record(catcher, critter)
        }
    }

    private fun selfName(): String = Minecraft.getInstance().player?.name?.string ?: "You"

    // ── state ─────────────────────────────────────────────────────────────

    private fun record(player: String, critter: SafariCritter) {
        if (player.isEmpty()) return
        if (runStartMs == 0L) runStartMs = System.currentTimeMillis()
        // Counted before the unique check, so duplicates still add to the total.
        totals[player] = (totals[player] ?: 0) + 1
        val set = caught.getOrPut(player) { LinkedHashSet() }
        lastBiome[player] = critter.biome
        // Unique species only: catching a second Gemzie is not progress.
        if (!set.add(critter.name)) return
        checkPlayerBiome(player, critter.biome)
        checkPartyBiome(critter.biome)
    }

    private fun checkPlayerBiome(player: String, biome: SafariBiome) {
        val mine = caught[player] ?: return
        if (SafariCritters.inBiome(biome).any { it.name !in mine }) return
        if (!announcedPlayer.add("$player|${biome.name}")) return
        if (!FamilyConfigManager.config.safari.announcePlayerBiome) return
        announce("§b$player §afinished ${biome.color}${biome.displayName}§a!")
    }

    private fun checkPartyBiome(biome: SafariBiome) {
        val everyone = caught.values.flatten().toSet()
        if (SafariCritters.inBiome(biome).any { it.name !in everyone }) return
        if (!announcedParty.add(biome)) return
        if (!FamilyConfigManager.config.safari.announcePartyBiome) return
        announce("${biome.color}${biome.displayName} §adone! §7(party)")
    }

    /**
     * Local chat always; party chat only when the user turned it on, since that posts
     * on their account.
     */
    private fun announce(message: String) {
        FaChat.send(message)
        if (!FamilyConfigManager.config.safari.announceToParty) return
        val plain = message.replace(COLOR_CODE_REGEX, "")
        Minecraft.getInstance().player?.connection?.sendChat("/pc $plain")
    }

    /**
     * Files the run that just ended into the stored history. Runs with no catches are
     * not counted, so walking in and straight back out does not inflate the total.
     */
    private fun archiveRun() {
        val catches = totals.values.sum()
        if (catches == 0) return
        val cfg = FamilyConfigManager.config.safari
        cfg.runsDone += 1
        cfg.lifetimeCatches += catches
        val unique = partyTotal()
        if (unique > cfg.bestUnique) cfg.bestUnique = unique
        FamilyConfigManager.save()
    }

    fun reset(announce: Boolean = false) {
        archiveRun()
        HideyhoQuickAccept.newRun()
        caught.clear()
        totals.clear()
        lastBiome.clear()
        announcedPlayer.clear()
        announcedParty.clear()
        runStartMs = 0L
        if (announce) FaChat.send("§7Critter Safari tracker reset for a new run.")
    }

    // ── read-out, also used by /fa safari ──────────────────────────────────

    private fun countIn(player: String, biome: SafariBiome): Int =
        caught[player]?.count { name -> SafariCritters.inBiome(biome).any { it.name == name } } ?: 0

    private fun partyCountIn(biome: SafariBiome): Int {
        val everyone = caught.values.flatten().toSet()
        return SafariCritters.inBiome(biome).count { it.name in everyone }
    }

    private fun partyTotal(): Int = caught.values.flatten().toSet().size

    /** Every catch this run, duplicates included, across everyone. */
    private fun partyCaught(): Int = totals.values.sum()

    private fun showTotals(): Boolean = FamilyConfigManager.config.safari.showTotals

    /** "13 unique, 31 caught" or just "13", depending on the toggle. */
    private fun tally(unique: Int, total: Int): String =
        if (showTotals()) "$unique unique, $total caught" else "$unique"

    /** One line for party chat: the party's progress, no colour codes. */
    fun partySummary(): String {
        if (caught.isEmpty()) return "Critter Safari: nothing caught yet this run."
        val biomes = SafariBiome.entries.joinToString(" | ") { b ->
            "${b.displayName} ${partyCountIn(b)}/${SafariCritters.totalIn(b)}"
        }
        val head = if (showTotals()) "${partyTotal()}/${SafariCritters.TOTAL} unique, ${partyCaught()} caught"
                   else "${partyTotal()}/${SafariCritters.TOTAL} unique"
        return "Critter Safari: $head | $biomes"
    }

    /** One line for party chat: how many runs and what they came to. */
    fun historySummary(): String {
        val cfg = FamilyConfigManager.config.safari
        if (cfg.runsDone == 0) return "Critter Safari: no finished runs recorded yet."
        val avg = cfg.lifetimeCatches.toDouble() / cfg.runsDone
        return "Critter Safari: %d runs, %d caught in total, %.1f a run, best dex %d/%d"
            .format(cfg.runsDone, cfg.lifetimeCatches, avg, cfg.bestUnique, SafariCritters.TOTAL)
    }

    /** `/fa safari history` — the stored run history, in your own chat. */
    fun printHistory() {
        val cfg = FamilyConfigManager.config.safari
        if (cfg.runsDone == 0) {
            FaChat.send("§7No finished Critter Safari runs recorded yet.")
            return
        }
        val avg = cfg.lifetimeCatches.toDouble() / cfg.runsDone
        FaChat.send("§6§lCritter Safari history")
        FaChat.send("  §7Runs: §f${cfg.runsDone}")
        FaChat.send("  §7Caught in total: §f${cfg.lifetimeCatches} §8(§f%.1f§7 a run§8)".format(avg))
        FaChat.send("  §7Best dex in one run: §f${cfg.bestUnique}§7/${SafariCritters.TOTAL}")
    }

    /** `/fa safari share` — post the current run to party chat. */
    fun shareToParty() {
        Minecraft.getInstance().player?.connection?.sendChat("/pc " + partySummary())
        FaChat.send("§7Posted the run to party chat.")
    }

    /**
     * The biome a player is working: where they have caught the most. A party splits one
     * biome each, so this is the number that matters; ties go to their most recent catch.
     */
    private fun mainBiome(player: String): SafariBiome? {
        val counts = SafariBiome.entries.associateWith { countIn(player, it) }
        val best = counts.values.max()
        if (best == 0) return null
        val tied = SafariBiome.entries.filter { counts[it] == best }
        return tied.firstOrNull { it == lastBiome[player] } ?: tied.first()
    }

    /** "KyoWaa  Cavern 9/9 ✔  (13)" — their biome, its progress, and their run total. */
    private fun playerLine(player: String): String {
        val unique = caught[player]?.size ?: 0
        val biome = mainBiome(player) ?: return "§b$player §8- §7nothing yet"
        val done = countIn(player, biome)
        val of = SafariCritters.totalIn(biome)
        val tick = if (done >= of) " §a✔" else ""
        return "§b$player §8- ${biome.color}${biome.displayName} §f$done§7/$of$tick §8(${tally(unique, totals[player] ?: 0)})"
    }

    /** `/fa safari` — the same numbers as the HUD, printed into chat. */
    fun printSummary() {
        if (caught.isEmpty()) {
            FaChat.send("§7No Critter Safari catches tracked yet this run.")
            return
        }
        FaChat.send("§6§lCritter Safari §8| §f${partyTotal()}§7/${SafariCritters.TOTAL} §7unique" +
            (if (showTotals()) " §8| §f${partyCaught()} §7caught" else ""))
        for (biome in SafariBiome.entries) {
            val done = partyCountIn(biome)
            val total = SafariCritters.totalIn(biome)
            val tick = if (done >= total) " §a✔" else ""
            FaChat.send("  ${biome.color}${biome.displayName.padEnd(8)} §f$done§7/$total$tick")
        }
        for (player in caught.keys) FaChat.send("  " + playerLine(player))
    }

    // ── HUD ────────────────────────────────────────────────────────────────

    private fun inSafari(): Boolean {
        val area = HypixelLocation.areaName() ?: return true // unknown: do not hide
        return area.contains("Safari", ignoreCase = true) || SafariBiome.fromAreaName(area) != null
    }

    private fun renderHud(ctx: GuiGraphicsExtractor) {
        val cfg = FamilyConfigManager.config.safari
        if (!cfg.enabled || !cfg.trackerHud) return
        if (caught.isEmpty()) return
        if (cfg.onlyInSafari && !inSafari()) return

        val client = Minecraft.getInstance()
        val tr = client.font
        val m = ctx.pose()
        m.pushMatrix()
        m.translate(cfg.hudX.toFloat(), cfg.hudY.toFloat())
        val scale = cfg.hudScale.toFloatOrNull() ?: 1f
        m.scale(scale, scale)

        var y = 3
        ctx.text(tr, "§6§lCritter Safari §8| §f${partyTotal()}§7/${SafariCritters.TOTAL}" +
            (if (showTotals()) " §8| §f${partyCaught()} §7caught" else ""), 4, y, -1, true)
        y += 12

        for (biome in SafariBiome.entries) {
            val done = partyCountIn(biome)
            val total = SafariCritters.totalIn(biome)
            val tick = if (done >= total) " §a✔" else ""
            ctx.text(tr, "${biome.color}${biome.displayName.padEnd(8)} §f$done§7/$total$tick", 4, y, -1, true)
            y += 10
        }

        if (cfg.perPlayerLines && caught.isNotEmpty()) {
            y += 3
            for (player in caught.keys) {
                ctx.text(tr, playerLine(player), 4, y, -1, true)
                y += 10
            }
        }

        m.popMatrix()
    }

    /** Preview lines for the HUD editor, so the box has a sensible size before a run. */
    fun renderPreview(ctx: GuiGraphicsExtractor) {
        val tr = Minecraft.getInstance().font
        var y = 3
        ctx.text(tr, "§6§lCritter Safari §8| §f24§7/37", 4, y, -1, true); y += 12
        for (biome in SafariBiome.entries) {
            ctx.text(tr, "${biome.color}${biome.displayName.padEnd(8)} §f5§7/9", 4, y, -1, true)
            y += 10
        }
    }
}
