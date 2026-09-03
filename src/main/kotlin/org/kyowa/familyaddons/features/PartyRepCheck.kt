package org.kyowa.familyaddons.features

import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.party.PartyTracker
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

object PartyRepCheck {

    private val httpClient = HttpClient.newHttpClient()

    private val tiers = listOf(
        12000 to "Infernal", 7000 to "Fiery", 3000 to "Burning", 1000 to "Hot", 0 to "Basic"
    )

    fun register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            if (FamilyConfigManager.config.party.repCheckEnabled) {
                val plain = message.string.trim()
                val match = Regex("""^(.+?) joined the party\.$""").find(plain)
                if (match != null) {
                    val ign = PartyTracker.cleanName(match.groupValues[1])
                    val self = Minecraft.getInstance().player?.name?.string ?: ""
                    if (ign.isNotEmpty() && !ign.equals(self, ignoreCase = true)) {
                        fetchRep(ign)
                    }
                }
            }
            true
        }
    }

    fun fetchRep(ign: String) {
        val apiKey = org.kyowa.familyaddons.KeyFetcher.getApiKey()
        if (apiKey.isNullOrBlank()) {
            chatInvalidKey()
            return
        }

        CompletableFuture.runAsync {
            try {
                val mojang = get("https://api.mojang.com/users/profiles/minecraft/$ign") ?: run {
                    chat("§cCould not resolve UUID for $ign"); return@runAsync
                }
                val uuid = mojang.get("id")?.asString ?: run {
                    chat("§cCould not resolve UUID for $ign"); return@runAsync
                }

                val data = get("https://api.hypixel.net/v2/skyblock/profiles?uuid=$uuid&key=$apiKey") ?: run {
                    chat("§cNo response from Hypixel API"); return@runAsync
                }

                if (!data.get("success").asBoolean) {
                    val cause = data.get("cause")?.asString ?: "unknown"
                    if (cause.contains("Invalid API key", ignoreCase = true) ||
                        cause.contains("invalid key", ignoreCase = true)) {
                        chatInvalidKey()
                    } else {
                        chat("§cHypixel API error for $ign: $cause")
                    }
                    return@runAsync
                }

                val profiles = data.getAsJsonArray("profiles") ?: run {
                    chat("§7$ign has no SkyBlock profiles."); return@runAsync
                }

                val profile = profiles.map { it.asJsonObject }
                    .firstOrNull { it.get("selected")?.asBoolean == true }
                    ?: profiles.lastOrNull()?.asJsonObject
                    ?: run { chat("§7No profile found for $ign"); return@runAsync }

                val member = profile.getAsJsonObject("members")
                    ?.getAsJsonObject(uuid)
                    ?: run { chat("§7No member data for $ign"); return@runAsync }

                val nether = member.getAsJsonObject("nether_island_player_data")
                val magesRep = nether?.get("mages_reputation")?.asInt ?: 0
                val barbsRep = nether?.get("barbarians_reputation")?.asInt ?: 0
                val rep = maxOf(magesRep, barbsRep)

                val tier = tiers.firstOrNull { rep >= it.first }?.second ?: "None"
                val color = when (tier) {
                    "Infernal" -> "§c"; "Fiery" -> "§6"; "Burning" -> "§e"
                    "Hot" -> "§a"; "Basic" -> "§f"; else -> "§7"
                }

                chat("§e$ign §7joined §8| §7Rep: §b${"%,d".format(rep)} §8| §7Max Kuudra: $color$tier")
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("PartyRepCheck error: ${e.message}")
            }
        }
    }

    private fun get(url: String) = try {
        val req = HttpRequest.newBuilder().uri(URI.create(url))
            .header("User-Agent", "FamilyAddons/1.0").GET().build()
        val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        JsonParser.parseString(res.body()).asJsonObject
    } catch (e: Exception) { null }



    fun saveApiKey(key: String): Boolean {
        FamilyConfigManager.config.general.hypixelApiKey = key
        FamilyConfigManager.save()
        return true
    }

    private fun chatInvalidKey() {
        Minecraft.getInstance().execute {
            val player = Minecraft.getInstance().player ?: return@execute
            val msg = Component.literal("§6[FA] §cInvalid or missing API key. ")
                .append(
                    Component.literal("§b§n[Get one here]")
                        .withStyle { it.withClickEvent(
                            ClickEvent.OpenUrl(java.net.URI("https://developer.hypixel.net/dashboard"))
                        )}
                )
            player.sendSystemMessage(msg)
        }
    }

    private fun chat(msg: String) {
        Minecraft.getInstance().execute {
            Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§6[FA] $msg"))
        }
    }
}
