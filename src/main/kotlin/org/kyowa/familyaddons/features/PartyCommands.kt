package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.party.PartyTracker
import org.kyowa.familyaddons.util.MathEval
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object PartyCommands {

    private val PARTY_MSG_REGEX = Regex("""^Party\s*[>»]\s*(?:\[[^\]]+\]\s*)?([^:]+):\s*(.+)$""")
    private val PT_REGEX = Regex("""^pt(?:\s+(\S+))?$""", RegexOption.IGNORE_CASE)
    private val INV_REGEX = Regex("""^(?:inv|invite)\s+(\S+)$""", RegexOption.IGNORE_CASE)
    private val ALLINV_REGEX = Regex("""^(?:allinv|ai|allinvite)$""", RegexOption.IGNORE_CASE)
    private val KICK_REGEX = Regex("""^(?:k|kick)\s+(\S+)$""", RegexOption.IGNORE_CASE)
    private val CALC_REGEX = Regex("""^(?:calc|c)\s+(.+)$""", RegexOption.IGNORE_CASE)

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    fun register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            if (FamilyConfigManager.config.party.commandsEnabled) {
                val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
                handlePartyMessage(plain)
            }
            true
        }
    }

    private fun selfName() = Minecraft.getInstance().player?.name?.string ?: ""

    private fun send(cmd: String) {
        Minecraft.getInstance().player?.connection?.sendCommand(cmd)
    }

    private fun handlePartyMessage(plain: String) {
        if (!plain.startsWith("Party")) return
        val match = PARTY_MSG_REGEX.find(plain) ?: return
        val sender = PartyTracker.cleanName(match.groupValues[1])
        val body = match.groupValues[2].trim().trimStart('!', '.')
        if (sender.isEmpty()) return
        if (!isWhitelisted(sender)) return
        val cfg = FamilyConfigManager.config.party

        if (cfg.ptEnabled) {
            PT_REGEX.find(body)?.let {
                val rawTarget = it.groupValues[1].takeIf { t -> t.isNotEmpty() }
                doTransfer(sender, rawTarget)
                return
            }
        }

        if (cfg.invEnabled) {
            INV_REGEX.find(body)?.let {
                send("p invite ${it.groupValues[1]}")
                return
            }
        }

        if (cfg.allinvEnabled && body.matches(ALLINV_REGEX)) {
            send("p settings allinvite")
            return
        }

        if (cfg.warpEnabled && body.equals("warp", ignoreCase = true)) {
            send("p warp")
            return
        }

        if (cfg.kickEnabled) {
            KICK_REGEX.find(body)?.let {
                doKick(it.groupValues[1])
                return
            }
        }

        if (cfg.calcEnabled) {
            CALC_REGEX.find(body)?.let {
                val expr = it.groupValues[1].trim()
                val result = MathEval.evaluate(expr) ?: return
                val formatted = if (result == result.toLong().toDouble()) result.toLong().toString()
                else "%.2f".format(result)
                send("pc $expr = $formatted")
                return
            }
        }
    }

    private fun doTransfer(sender: String, rawTarget: String?) {
        val self = selfName()
        val target = rawTarget?.let { q ->
            // Try cached members first
            PartyTracker.resolveMember(q, true, self) ?: run {
                // Fall back to /p list then resolve
                send("p list")
                scheduler.schedule({
                    val resolved = PartyTracker.resolveMember(q, true, self) ?: q
                    Minecraft.getInstance().execute { send("p transfer $resolved") }
                }, 600, TimeUnit.MILLISECONDS)
                return
            }
        } ?: sender
        send("p transfer $target")
    }

    private fun doKick(rawTarget: String) {
        val self = selfName()
        if (rawTarget.equals(self, ignoreCase = true)) return

        // Try cached members first
        val resolved = PartyTracker.resolveMember(rawTarget, false, self)
        if (resolved != null) {
            if (!resolved.equals(self, ignoreCase = true)) send("p kick $resolved")
            return
        }

        // Fall back to /p list
        send("p list")
        scheduler.schedule({
            val fallback = PartyTracker.resolveMember(rawTarget, false, self) ?: rawTarget
            if (!fallback.equals(self, ignoreCase = true)) {
                Minecraft.getInstance().execute { send("p kick $fallback") }
            }
        }, 600, TimeUnit.MILLISECONDS)
    }

    private fun isWhitelisted(name: String): Boolean {
        val self = selfName()
        if (name.equals(self, ignoreCase = true)) return true
        val whitelist = FamilyConfigManager.config.party.partyWhitelist
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (whitelist.isEmpty()) return true
        return whitelist.any { it.equals(name, ignoreCase = true) }
    }
}
