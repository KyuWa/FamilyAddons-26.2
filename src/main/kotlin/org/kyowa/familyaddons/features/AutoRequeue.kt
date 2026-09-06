package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.util.FaChat

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.kyowa.familyaddons.commands.TestCommand
import org.kyowa.familyaddons.config.FamilyConfigManager

object AutoRequeue {

    private val TIER_PATTERN = Regex(
        """(?:\[[^\]]+\]\s+)?(\w+)\s+entered Kuudra's Hollow, (Basic|Hot|Burning|Fiery|Infernal) Tier!""",
        RegexOption.IGNORE_CASE
    )

    // ── Kuudra state ──────────────────────────────────────────
    private var inKuudra              = false
    private var kuudraTier            = "infernal"
    private var kuudraCancelRequeue   = false
    private var kuudraDtRequester: String? = null
    private var kuudraDtAnnounceName: String? = null
    private var kuudraDtAnnounceTicks = 0
    private var kuudraDiedThisRun     = false
    private var kuudraWaiting         = false
    private var kuudraWaitTicks       = 0

    // ── Dungeon state ─────────────────────────────────────────
    private val dungeonNeedsDowntime  = java.util.Collections.newSetFromMap<String>(java.util.concurrent.ConcurrentHashMap())
    private var inDungeon             = false
    private var checkTicksRemaining   = -1
    private var dungeonRequeueTicks   = 0

    // ── Kuudra area detection ─────────────────────────────────
    // Hypixel broadcasts "X entered Kuudra's Hollow, <Tier> Tier!" on the
    // server you are LEAVING, right before "Sending to server ..." moves you
    // into the instance. That transfer goes through the configuration phase,
    // so ClientPlayConnectionEvents.JOIN fires again and resetAll() wiped
    // inKuudra a few seconds before Elle's Phase 1 line — every Kuudra
    // waypoint/beam feature stayed gated off and "KUUDRA DOWN!" was ignored.
    // So, like Odin's KuudraUtils, treat the sidebar ("⏣ Kuudra's Hollow (T2)")
    // and tab list ("Area: Kuudra") as the source of truth for being inside
    // the instance; the chat trigger is kept only as a fallback.
    private val SCOREBOARD_TIER = Regex("""Kuudra's Hollow \(T(\d)\)""")
    private const val AREA_POLL_TICKS = 10
    private var inKuudraArea = false
    private var areaTicker   = AREA_POLL_TICKS

    // ── Kuudra state queries (used by Kuudra waypoint/ESP features) ──
    // inKuudra (chat trigger) is true from run start until "KUUDRA DOWN!";
    // inKuudraArea is true while the scoreboard/tab list says we're inside
    // Kuudra's Hollow. Either one is enough for the render features.
    fun isInKuudra(): Boolean = inKuudra || inKuudraArea
    fun isInKuudraArea(): Boolean = inKuudraArea
    fun chatTriggerActive(): Boolean = inKuudra

    /** 1=Basic, 2=Hot, 3=Burning, 4=Fiery, 5=Infernal (defaults to Infernal). */
    fun kuudraTierIndex(): Int = when (kuudraTier) {
        "basic"    -> 1
        "hot"      -> 2
        "burning"  -> 3
        "fiery"    -> 4
        "infernal" -> 5
        else       -> 5
    }

    // ── Reset ─────────────────────────────────────────────────
    private fun resetAll() {
        inKuudra               = false
        kuudraTier             = "infernal"
        kuudraCancelRequeue    = false
        kuudraDtRequester      = null
        kuudraDtAnnounceName   = null
        kuudraDtAnnounceTicks  = 0
        kuudraDiedThisRun      = false
        kuudraWaiting          = false
        kuudraWaitTicks        = 0
        inKuudraArea           = false
        areaTicker             = AREA_POLL_TICKS   // re-poll on the next tick

        inDungeon              = false
        dungeonNeedsDowntime.clear()
        dungeonRequeueTicks    = 0
        checkTicksRemaining    = -1
    }

    // ── Register ──────────────────────────────────────────────
    fun register() {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> resetAll() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            resetAll()
            checkTicksRemaining = 200
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val raw = message.string
            val plain = raw.replace(COLOR_CODE_REGEX, "").trim()
            handleKuudra(raw, plain)
            handleDungeon(plain)
            true
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            DtTitle.tick()
            DungeonDtTitle.tick()
            tickKuudraArea(client)
            tickKuudra()
            tickDungeon(client)
        }
    }

    // ── Kuudra area tick ──────────────────────────────────────
    private fun tickKuudraArea(client: Minecraft) {
        if (++areaTicker < AREA_POLL_TICKS) return
        areaTicker = 0

        val nowInArea = detectKuudraArea(client)
        if (nowInArea && !inKuudraArea) {
            // Just landed in the instance: re-arm the run state that the
            // transfer's JOIN reset wiped. DT requests made during the run
            // still work because they arrive after this point.
            inKuudra          = true
            kuudraDiedThisRun = false
            kuudraWaiting     = false
            kuudraWaitTicks   = 0
        }
        inKuudraArea = nowInArea
    }

    /** Sidebar "Kuudra's Hollow (T#)" (also updates the tier) or tab "Area: Kuudra". */
    private fun detectKuudraArea(client: Minecraft): Boolean {
        if (client.level == null) return false

        for (line in DevTools.getScoreboardLines(client)) {
            val match = SCOREBOARD_TIER.find(line) ?: continue
            when (match.groupValues[1].toIntOrNull()) {
                1 -> kuudraTier = "basic"
                2 -> kuudraTier = "hot"
                3 -> kuudraTier = "burning"
                4 -> kuudraTier = "fiery"
                5 -> kuudraTier = "infernal"
            }
            return true
        }

        val tabList = client.connection?.onlinePlayers ?: return false
        return tabList.any { entry ->
            entry.tabListDisplayName?.string
                ?.replace(COLOR_CODE_REGEX, "")
                ?.trim()
                ?.startsWith("Area: Kuudra") == true
        }
    }

    // ── Kuudra tick ───────────────────────────────────────────
    private fun tickKuudra() {
        if (kuudraDtAnnounceTicks > 0) {
            kuudraDtAnnounceTicks--
            if (kuudraDtAnnounceTicks == 0) {
                kuudraDtAnnounceName?.let {
                    Minecraft.getInstance().player?.connection?.sendChat("/pc $it requested dt!")
                }
                kuudraDtAnnounceName = null
            }
        }
        if (kuudraWaiting) {
            if (kuudraWaitTicks > 0) {
                kuudraWaitTicks--
            } else {
                kuudraWaiting = false
                Minecraft.getInstance().player?.connection?.sendCommand("instancerequeue")
            }
        }
    }

    // ── Dungeon tick ──────────────────────────────────────────
    private fun tickDungeon(client: Minecraft) {
        if (checkTicksRemaining > 0) {
            checkTicksRemaining--
            if (checkTicksRemaining % 20 == 0) {
                val lines = DevTools.getScoreboardLines(client)
                if (lines.any { it.contains("The Catacombs", ignoreCase = true) }) {
                    inDungeon = true
                    checkTicksRemaining = -1
                } else if (checkTicksRemaining == 0) {
                    inDungeon = false
                }
            }
        }
        if (dungeonRequeueTicks > 0) {
            dungeonRequeueTicks--
            if (dungeonRequeueTicks == 0) {
                Minecraft.getInstance().player?.connection?.sendCommand("instancerequeue")
            }
        }
    }

    // ── Kuudra message handler ────────────────────────────────
    private fun handleKuudra(raw: String, plain: String) {
        val config = FamilyConfigManager.config.kuudra
        val player = Minecraft.getInstance().player ?: return
        val selfName = player.name.string

        val tierMatch = TIER_PATTERN.find(plain)
        if (tierMatch != null) {
            kuudraTier          = tierMatch.groupValues[2].lowercase()
            kuudraCancelRequeue = false
            kuudraDiedThisRun   = false
            inKuudra            = true
            return
        }

        val partyMatch = Regex("""^Party\s*[>»]\s*(?:\[[^\]]+\]\s*)?([A-Za-z0-9_]{3,16})\s*:\s*(.+)$""", RegexOption.IGNORE_CASE).find(plain)
        if (partyMatch != null) {
            val name = partyMatch.groupValues[1].trim()
            val msg  = partyMatch.groupValues[2].trim().lowercase()

            if (msg == "!dt" || msg == "dt" || msg.startsWith("!dt")) {
                if (inKuudra) {
                    if (config.dtTitle) DtTitle.show("${TestCommand.getFormattedName(name)} §crequested §fDT!")
                    kuudraCancelRequeue = true
                    kuudraDtRequester   = name
                    kuudraWaiting       = false
                    kuudraWaitTicks     = 0
                }
                return
            }

            if (msg == "!undt" || msg == "undt") {
                if (inKuudra) {
                    if (config.dtTitle) DtTitle.show("${TestCommand.getFormattedName(name)} §acancelled §fDT!")
                    kuudraCancelRequeue   = false
                    kuudraDtRequester     = null
                    kuudraDtAnnounceName  = null
                    kuudraDtAnnounceTicks = 0
                }
                return
            }
            return
        }

        if (!config.autoRequeue) return

        if (plain == "KUUDRA DOWN!" && !raw.contains(" >") && !raw.contains(":")) {
            if (!inKuudra) return
            inKuudra = false

            if (kuudraCancelRequeue) {
                kuudraCancelRequeue = false
                kuudraWaiting       = false
                kuudraWaitTicks     = 0
                if (kuudraDtRequester != null) {
                    kuudraDtAnnounceName  = kuudraDtRequester
                    kuudraDtAnnounceTicks = 40
                }
                kuudraDtRequester = null
                return
            }

            val tierAllowed = when (kuudraTier) {
                "basic"    -> config.requeueBasic
                "hot"      -> config.requeueHot
                "burning"  -> config.requeueBurning
                "fiery"    -> config.requeueFiery
                else       -> config.requeueInfernal
            }
            if (!tierAllowed) return

            if (kuudraDiedThisRun) {
                kuudraDiedThisRun = false
                kuudraWaiting     = true
                kuudraWaitTicks   = 40
            } else {
                player.connection.sendCommand("instancerequeue")
            }
            return
        }

        if (plain.contains("left the party", ignoreCase = true) && inKuudra) {
            kuudraCancelRequeue = true
            kuudraDtRequester   = null
            FaChat.send("§eParty member left — Kuudra requeue cancelled.")
            return
        }

        if (plain == "$selfName was FINAL KILLED by Kuudra!") {
            kuudraDiedThisRun = true
        }
    }

    // ── Dungeon message handler ───────────────────────────────
    private fun handleDungeon(plain: String) {
        val config = FamilyConfigManager.config.dungeons
        val player = Minecraft.getInstance().player ?: return

        val partyMatch = Regex("""^Party\s*[>»]\s*(?:\[[^\]]+\]\s*)?([A-Za-z0-9_]{3,16})\s*:\s*(.+)$""", RegexOption.IGNORE_CASE).find(plain)
        if (partyMatch != null) {
            val name = partyMatch.groupValues[1].trim()
            val msg  = partyMatch.groupValues[2].trim().lowercase()

            if (msg == "!r" || msg == "r" || msg == "!undt" || msg == "undt") {
                if (!dungeonNeedsDowntime.remove(name)) return
                if (dungeonNeedsDowntime.isEmpty()) {
                    dungeonRequeueTicks = (config.requeueDelaySecs * 20).toInt().coerceAtLeast(1)
                }
                return
            }

            if (msg == "!dt" || msg == "dt" || msg.startsWith("!dt")) {
                if (config.dtTitle) DungeonDtTitle.show("${TestCommand.getFormattedName(name)} §crequested §fDT!")
                dungeonNeedsDowntime.add(name)
                return
            }

            return
        }

        // Party leave — cancel dungeon requeue
        if (plain.contains("left the party", ignoreCase = true)) {
            dungeonNeedsDowntime.clear()
            dungeonRequeueTicks = 0
            return
        }

        if (!config.autoRequeue) return

        if (Regex("""^ *> EXTRA STATS <$""").matches(plain)) {
            if (!inDungeon) return
            inDungeon = false
            if (dungeonNeedsDowntime.isEmpty()) {
                dungeonRequeueTicks = (config.requeueDelaySecs * 20).toInt().coerceAtLeast(1)
            } else {
                player.connection.sendChat("/pc ${dungeonNeedsDowntime.joinToString(", ")} needs downtime")
                dungeonNeedsDowntime.clear()
            }
            return
        }
    }

    private fun chat(msg: String) {
        Minecraft.getInstance().execute {
            Minecraft.getInstance().player?.sendSystemMessage(Component.literal(msg))
        }
    }
}