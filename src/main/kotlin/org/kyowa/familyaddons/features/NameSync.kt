package org.kyowa.familyaddons.features

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.KeyFetcher
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.util.DevAccess
import org.kyowa.familyaddons.util.FaChat
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Client side of the shared name changer (worker: fa-names).
 *  - pulls everyone's approved names on join and every 5 minutes into
 *    [NameStyle]'s lookup;
 *  - submits / removes the local player's own template;
 *  - owner: lists pending requests with approve / deny buttons.
 */
object NameSync {

    private const val WORKER_URL = "https://fa-names.220395610.workers.dev"
    private const val REFRESH_TICKS = 20 * 300
    private const val STATUS_TICKS = 20 * 30

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    private var ticker = 0
    private var statusTicker = 0
    @Volatile private var inFlight = false
    /** True while our own request is waiting for KyoWaa: poll /status so the verdict shows up in chat. */
    @Volatile private var awaiting = false
    /** Timestamp of the last decision we already told the player about (persisted across restarts). */
    private val seenFile get() = File(Minecraft.getInstance().gameDirectory, "config/familyaddons/name-status.txt")
    private var lastSeenDecision: Long = -1L

    private fun auth(b: HttpRequest.Builder) = b.header(KeyFetcher.SECRET_HEADER, KeyFetcher.SECRET_TOKEN)
        .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
    private fun admin(b: HttpRequest.Builder) = b.header("X-Admin-Key", FamilyConfigManager.config.dev.presenceAdminKey.trim())
        .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> fetchAll(); checkStatus(); if (DevAccess.isDev()) pendingNudge() }
        ClientTickEvents.END_CLIENT_TICK.register {
            if (++ticker >= REFRESH_TICKS) { ticker = 0; fetchAll() }
            if (awaiting && ++statusTicker >= STATUS_TICKS) { statusTicker = 0; checkStatus() }
        }
    }

    fun fetchAll() {
        if (inFlight) return
        inFlight = true
        CompletableFuture.runAsync {
            try {
                val req = auth(HttpRequest.newBuilder(URI.create("$WORKER_URL/names"))).GET().build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200) {
                    val obj = JsonParser.parseString(resp.body()).asJsonObject
                    val map = HashMap<String, String>()
                    for ((k, v) in obj.entrySet()) if (v.isJsonPrimitive) map[k.lowercase()] = v.asString
                    NameStyle.setNames(map)
                    FamilyAddons.LOGGER.info("NameSync: ${map.size} custom names loaded")
                } else FamilyAddons.LOGGER.warn("NameSync: names HTTP ${resp.statusCode()}")
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("NameSync: fetch failed: ${e.message}")
            } finally { inFlight = false }
        }
    }

    private fun self(): Pair<String, String>? {
        val u = Minecraft.getInstance().user ?: return null
        val id = u.profileId?.toString() ?: return null
        return id to u.name
    }

    fun preview() {
        val t = FamilyConfigManager.config.nameChanger.myName.trim()
        if (t.isEmpty()) { FaChat.send("§cMy Name is empty."); return }
        NameStyle.validate(t)?.let { FaChat.send("§c$it"); return }
        val mc = Minecraft.getInstance()
        mc.execute { mc.player?.sendSystemMessage(FaChat.prefixed(Component.literal("§7Preview: ").append(NameStyle.render(t, Style.EMPTY)))) }
    }

    fun submit() {
        val t = FamilyConfigManager.config.nameChanger.myName.trim()
        if (t.isEmpty()) { FaChat.send("§cMy Name is empty."); return }
        NameStyle.validate(t)?.let { FaChat.send("§c$it"); return }
        val (uuid, name) = self() ?: return
        CompletableFuture.runAsync {
            try {
                val body = JsonObject().apply { addProperty("uuid", uuid); addProperty("username", name); addProperty("name", t) }.toString()
                val req = auth(HttpRequest.newBuilder(URI.create("$WORKER_URL/name"))).PUT(HttpRequest.BodyPublishers.ofString(body)).build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                val j = runCatching { JsonParser.parseString(resp.body()).asJsonObject }.getOrNull()
                when {
                    resp.statusCode() == 200 && j?.get("status")?.asString == "approved" -> { FaChat.send("§aYour name is live for everyone."); fetchAll() }
                    resp.statusCode() == 200 && j?.get("replaced")?.asBoolean == true -> { awaiting = true; statusTicker = 0; FaChat.send("§aUpdated your pending request with the new name. It shows for everyone once KyoWaa approves it.") }
                    resp.statusCode() == 200 -> { awaiting = true; statusTicker = 0; FaChat.send("§aSubmitted. It shows for everyone once KyoWaa approves it. You'll get a message here either way.") }
                    else -> FaChat.send("§cNot accepted: §7${j?.get("error")?.asString ?: "HTTP ${resp.statusCode()}"}")
                }
            } catch (e: Exception) { FaChat.send("§cSubmit failed: §7${e.message}") }
        }
    }

    fun remove() {
        val (uuid, _) = self() ?: return
        CompletableFuture.runAsync {
            try {
                val body = JsonObject().apply { addProperty("uuid", uuid) }.toString()
                val req = auth(HttpRequest.newBuilder(URI.create("$WORKER_URL/name"))).method("DELETE", HttpRequest.BodyPublishers.ofString(body)).build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200) { FaChat.send("§7Your custom name was removed."); fetchAll() }
                else FaChat.send("§cRemove failed: HTTP ${resp.statusCode()}")
            } catch (e: Exception) { FaChat.send("§cRemove failed: §7${e.message}") }
        }
    }

    // ── Own request status ────────────────────────────────────────────

    private fun loadSeen(): Long {
        if (lastSeenDecision < 0) lastSeenDecision = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(0L)
        return lastSeenDecision
    }

    private fun saveSeen(at: Long) {
        lastSeenDecision = at
        runCatching { seenFile.parentFile.mkdirs(); seenFile.writeText(at.toString()) }
    }

    /**
     * Asks the worker what happened to our own request. Pending: keep polling.
     * Approved / denied with a timestamp we haven't shown yet: tell the player once.
     */
    fun checkStatus() {
        val (uuid, _) = self() ?: return
        CompletableFuture.runAsync {
            try {
                val req = auth(HttpRequest.newBuilder(URI.create("$WORKER_URL/status?uuid=$uuid"))).GET().build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() != 200) return@runAsync
                val j = JsonParser.parseString(resp.body()).asJsonObject
                when (j.get("status")?.asString) {
                    "pending" -> awaiting = true
                    "set" -> {
                        awaiting = false
                        val at = j.get("at")?.asLong ?: 0L
                        if (at > loadSeen()) {
                            saveSeen(at)
                            val name = j.get("name")?.asString ?: ""
                            val mc = Minecraft.getInstance()
                            mc.execute {
                                mc.player?.sendSystemMessage(FaChat.prefixed(Component.literal("§dKyoWaa gave you a custom name! Everyone now sees you as ")
                                    .append(NameStyle.render(name, Style.EMPTY))))
                            }
                            fetchAll()
                        }
                    }
                    "revoked" -> {
                        awaiting = false
                        val at = j.get("at")?.asLong ?: 0L
                        if (at > loadSeen()) {
                            saveSeen(at)
                            FaChat.send("§cKyoWaa removed your custom name. You can submit a new one under /fa > Name Changer.")
                            fetchAll()
                        }
                    }
                    "approved", "denied" -> {
                        awaiting = false
                        val at = j.get("at")?.asLong ?: 0L
                        if (at > loadSeen()) {
                            saveSeen(at)
                            val name = j.get("name")?.asString ?: ""
                            val mc = Minecraft.getInstance()
                            if (j.get("status").asString == "approved") {
                                mc.execute {
                                    mc.player?.sendSystemMessage(FaChat.prefixed(Component.literal("§aKyoWaa approved your name! Everyone now sees you as ")
                                        .append(NameStyle.render(name, Style.EMPTY))))
                                }
                                fetchAll()
                            } else {
                                FaChat.send("§cKyoWaa denied your name request §8(${NameStyle.visible(name)})§c. Tweak it under /fa > Name Changer and submit again.")
                            }
                        }
                    }
                    else -> awaiting = false
                }
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("NameSync: status failed: ${e.message}")
            }
        }
    }

    // ── Owner review ──────────────────────────────────────────────────

    private fun pendingNudge() {
        CompletableFuture.runAsync {
            val list = pending() ?: return@runAsync
            if (list.isNotEmpty()) {
                val mc = Minecraft.getInstance()
                mc.execute {
                    mc.player?.sendSystemMessage(FaChat.prefixed(Component.literal("§e${list.size} name request(s) waiting. ")
                        .append(button("[Review]", "§a", "/fa names", "List pending names"))))
                }
            }
        }
    }

    private fun pending(): List<JsonObject>? = try {
        val req = admin(HttpRequest.newBuilder(URI.create("$WORKER_URL/pending"))).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 200) JsonParser.parseString(resp.body()).asJsonArray.map { it.asJsonObject } else null
    } catch (e: Exception) { null }

    /** `/fa names` */
    fun listPending() {
        if (!DevAccess.isDev()) return
        CompletableFuture.runAsync {
            val list = pending()
            val mc = Minecraft.getInstance()
            mc.execute {
                val p = mc.player ?: return@execute
                if (list == null) { p.sendSystemMessage(FaChat.prefixed("§cCould not reach the names worker (admin key set?)")); return@execute }
                if (list.isEmpty()) { p.sendSystemMessage(FaChat.prefixed("§7No pending name requests.")); return@execute }
                p.sendSystemMessage(FaChat.prefixed("§ePending name requests:"))
                for (r in list) {
                    val uuid = r.get("uuid").asString; val user = r.get("username").asString; val name = r.get("name").asString
                    val line = Component.literal("§f$user §7→ ").append(NameStyle.render(name, Style.EMPTY)).append(Component.literal(" §8(${NameStyle.visible(name)}) "))
                        .append(button("[Approve]", "§a", "/fa nameapprove $uuid", "Approve $user's name")).append(Component.literal(" "))
                        .append(button("[Deny]", "§c", "/fa namedeny $uuid", "Deny $user's name"))
                    p.sendSystemMessage(line)
                }
            }
        }
    }

    private fun approved(): List<JsonObject>? = try {
        val req = admin(HttpRequest.newBuilder(URI.create("$WORKER_URL/approved"))).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 200) JsonParser.parseString(resp.body()).asJsonArray.map { it.asJsonObject } else null
    } catch (e: Exception) { null }

    /** `/fa names live` — every approved name with a Revoke button. */
    fun listApproved() {
        if (!DevAccess.isDev()) return
        CompletableFuture.runAsync {
            val list = approved()
            val mc = Minecraft.getInstance()
            mc.execute {
                val p = mc.player ?: return@execute
                if (list == null) { p.sendSystemMessage(FaChat.prefixed("§cCould not reach the names worker (admin key set?)")); return@execute }
                if (list.isEmpty()) { p.sendSystemMessage(FaChat.prefixed("§7No approved names.")); return@execute }
                p.sendSystemMessage(FaChat.prefixed("§eLive custom names (${list.size}):"))
                for (r in list) {
                    val uuid = r.get("uuid").asString; val user = r.get("username").asString; val name = r.get("name").asString
                    val line = Component.literal("§f$user §7→ ").append(NameStyle.render(name, Style.EMPTY)).append(Component.literal(" §8(${NameStyle.visible(name)}) "))
                        .append(button("[Revoke]", "§c", "/fa namerevoke $uuid", "Take $user's name down"))
                    p.sendSystemMessage(line)
                }
            }
        }
    }

    /** `/fa nameset <player> <name>` — give someone a name directly, no approval step. */
    fun setName(player: String, name: String) {
        if (!DevAccess.isDev()) return
        CompletableFuture.runAsync {
            try {
                val body = JsonObject().apply { addProperty("username", player); addProperty("name", name) }.toString()
                val req = admin(HttpRequest.newBuilder(URI.create("$WORKER_URL/set"))).POST(HttpRequest.BodyPublishers.ofString(body)).build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                val j = runCatching { JsonParser.parseString(resp.body()).asJsonObject }.getOrNull()
                if (resp.statusCode() == 200 && j?.get("ok")?.asBoolean == true) {
                    val mc = Minecraft.getInstance()
                    mc.execute {
                        mc.player?.sendSystemMessage(FaChat.prefixed(Component.literal("§aSet §f${j.get("username").asString}§a's name to ")
                            .append(NameStyle.render(j.get("name").asString, Style.EMPTY))))
                    }
                    fetchAll()
                } else FaChat.send("§cSet failed: §7${j?.get("error")?.asString ?: "HTTP ${resp.statusCode()}"}")
            } catch (e: Exception) { FaChat.send("§cSet failed: §7${e.message}") }
        }
    }

    /** `/fa namerevoke <uuid>` */
    fun revoke(uuid: String) {
        if (!DevAccess.isDev()) return
        CompletableFuture.runAsync {
            try {
                val body = JsonObject().apply { addProperty("uuid", uuid) }.toString()
                val req = admin(HttpRequest.newBuilder(URI.create("$WORKER_URL/revoke"))).POST(HttpRequest.BodyPublishers.ofString(body)).build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                val j = runCatching { JsonParser.parseString(resp.body()).asJsonObject }.getOrNull()
                if (resp.statusCode() == 200 && j?.get("ok")?.asBoolean == true) {
                    FaChat.send("§cRevoked §f${j.get("username").asString}§c's name.")
                    fetchAll()
                } else FaChat.send("§cRevoke failed: §7${j?.get("error")?.asString ?: "HTTP ${resp.statusCode()}"}")
            } catch (e: Exception) { FaChat.send("§cRevoke failed: §7${e.message}") }
        }
    }

    /** `/fa nameapprove <uuid>` / `/fa namedeny <uuid>` */
    fun review(uuid: String, approve: Boolean) {
        if (!DevAccess.isDev()) return
        CompletableFuture.runAsync {
            try {
                val body = JsonObject().apply { addProperty("uuid", uuid); addProperty("approve", approve) }.toString()
                val req = admin(HttpRequest.newBuilder(URI.create("$WORKER_URL/review"))).POST(HttpRequest.BodyPublishers.ofString(body)).build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                val j = runCatching { JsonParser.parseString(resp.body()).asJsonObject }.getOrNull()
                if (resp.statusCode() == 200 && j?.get("ok")?.asBoolean == true) {
                    FaChat.send(if (approve) "§aApproved §f${j.get("username").asString}" else "§cDenied §f${j.get("username").asString}")
                    fetchAll()
                } else FaChat.send("§cReview failed: §7${j?.get("error")?.asString ?: "HTTP ${resp.statusCode()}"}")
            } catch (e: Exception) { FaChat.send("§cReview failed: §7${e.message}") }
        }
    }

    fun help() {
        val mc = Minecraft.getInstance()
        val lines = listOf(
            "§d§lName Changer syntax §7(max 24 visible characters)",
            "§7No codes needed: /fa > Name Changer > Easy Builder (colour wheels + style), then Build and Submit.",
            "§7Colours: §f&a &b &c... §7formats: §f&l §7bold §f&n §7underline §f&o §7italic §f&r §7reset",
            "§7Hex colour: §f<#ff8800>text",
            "§7Still gradient: §f<gradient:#ff0000:#0000ff>text</gradient>",
            "§7Moving band (KyoWaa style): §f<wave:#4B147D:#C86EFF>text</wave>",
            "§7Moving rainbow: §f<rainbow>text</rainbow>",
            "§7More than two colours: §f<wave:#55FFFF:#5555FF:#AA00AA>text</wave> §7(gradient and rainbow too)",
            "§7Mix them: §f&l<wave:#00ffff:#ff00ff>Kyo</wave>&r&7Waa",
            "§7Set it under §f/fa §7> Name Changer, hit §fPreview§7, then §fSubmit§7.",
        )
        mc.execute { val p = mc.player ?: return@execute; lines.forEach { p.sendSystemMessage(Component.literal(it)) } }
    }

    private fun button(label: String, color: String, command: String, hover: String) =
        Component.literal("$color§l§n$label").withStyle { s: Style ->
            s.withClickEvent(ClickEvent.RunCommand(command)).withHoverEvent(HoverEvent.ShowText(Component.literal("§7$hover")))
        }
}
