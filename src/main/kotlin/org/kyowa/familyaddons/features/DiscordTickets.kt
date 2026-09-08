package org.kyowa.familyaddons.features

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.sounds.SoundEvents
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.util.DevAccess
import org.kyowa.familyaddons.util.FaChat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Owner-only bridge to the local Discord ticket bot (bot.py).
 *
 * The bot watches the carry categories on Kuudra Gang / SkyBlockZ / Skyblock
 * Maniacs and, for every new ticket, connects to 127.0.0.1:[LISTEN_PORT] and
 * writes one JSON line: {"action":"ticket","server":…,"ign":…,"tier":…,
 * "runs":…,"channel_id":…}. This feature shows that line in chat with a
 * [CLAIM] button. Clicking it runs `/fa claim <channel_id>`, which connects
 * to the bot's claim port ([CLAIM_PORT]) and writes {"channel_id":…}; the bot
 * then issues its /claim slash command in that ticket channel. Nothing here
 * talks to Discord and nothing posts a message anywhere: one line to the
 * bot, that is all.
 *
 * Runs only for the dev UUID ([DevAccess.isDev]) with the "Discord Tickets"
 * toggle in the Dev category; for anyone else the listener never starts.
 */
object DiscordTickets {

    const val LISTEN_PORT = 25570
    const val CLAIM_PORT = 25571

    private data class Ticket(
        val server: String, val ign: String, val tier: String, val runs: String,
        val channelId: String, val serverId: String, val messageId: String,
    )

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var lastError: String? = null
    private var retryAtMs = 0L
    private val recent = ArrayDeque<Ticket>()
    private var ticketCount = 0
    private var lastTicketMs = 0L

    private fun enabled() = DevAccess.isDev() && FamilyConfigManager.config.dev.discordTickets

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register {
            val want = enabled()
            val running = thread?.isAlive == true
            if (want && !running) {
                if (System.currentTimeMillis() >= retryAtMs) start()
            } else if (!want && running) stop()
        }
    }

    private fun start() {
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                soTimeout = 1000
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), LISTEN_PORT))
            }
        } catch (e: Exception) {
            lastError = "bind ${e.message}"
            retryAtMs = System.currentTimeMillis() + 15_000 // don't hammer a taken port every tick
            FamilyAddons.LOGGER.warn("[FA Tickets] Could not bind 127.0.0.1:$LISTEN_PORT: ${e.message}")
            return
        }
        serverSocket = ss
        lastError = null
        thread = Thread({ loop(ss) }, "FA-Tickets").apply { isDaemon = true; start() }
        Thread({ findDiscordHandler() }, "FA-Tickets-Discord").apply { isDaemon = true; start() }
        FamilyAddons.LOGGER.info("[FA Tickets] Listening on 127.0.0.1:$LISTEN_PORT")
    }

    private fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        thread = null
    }

    private fun loop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val client = try { ss.accept() } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
            try {
                client.soTimeout = 5000
                client.use { c ->
                    val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.UTF_8))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        handleLine(line)
                    }
                }
            } catch (e: Exception) {
                lastError = "read ${e.message}"
            }
        }
    }

    private fun handleLine(line: String) {
        val obj = try { JsonParser.parseString(line).asJsonObject } catch (e: Exception) {
            lastError = "json ${e.message}"; return
        }
        fun str(key: String) = obj.get(key)?.takeUnless { it.isJsonNull }?.asString ?: ""
        val ticket = Ticket(str("server"), str("ign"), str("tier"), str("runs"), str("channel_id"), str("server_id"), str("message_id"))
        val mc = Minecraft.getInstance()
        when (val action = str("action")) {
            "ticket" -> mc.execute { onTicket(ticket) }
            "ticket_closed" -> mc.execute { onClosed(ticket) }
            else -> lastError = "unknown action $action"
        }
    }

    // ── main thread ────────────────────────────────────────────────────────

    private fun onTicket(t: Ticket) {
        ticketCount++
        lastTicketMs = System.currentTimeMillis()
        recent.addFirst(t)
        while (recent.size > 40) recent.removeLast()

        val mc = Minecraft.getInstance()
        val p = mc.player ?: return
        val cfg = FamilyConfigManager.config.dev

        val tierStr = "${tierColor(t.tier)}${t.tier}"
        val body: MutableComponent = Component.literal("§d${t.server} §8| $tierStr §fx${t.runs} §8| §b${t.ign} ")
        if (canClaim(t)) {
            body.append(button("[Claim]", "§a", ClickEvent.RunCommand("/fa claim ${t.channelId}"), "Send /claim to the bot and copy ${t.ign}"))
                .append(Component.literal(" "))
        }
        body.append(button("[Invite]", "§e", ClickEvent.RunCommand("/p invite ${t.ign}"), "Party invite ${t.ign}"))
        if (t.serverId.isNotEmpty() && t.messageId.isNotEmpty()) {
            body.append(Component.literal(" "))
                .append(button("[View Ticket]", "§6", ClickEvent.RunCommand("/fa ticketopen ${t.channelId}"), "Jump to the ticket in the Discord app"))
        }
        p.sendSystemMessage(FaChat.prefixed(body))

        if (cfg.discordTicketSound) p.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.6f)
        if (cfg.discordTicketTitle) DtTitle.show("§dTicket §e${t.tier} §fx${t.runs} §b${t.ign}")
    }

    private fun onClosed(t: Ticket) {
        recent.removeAll { it.channelId == t.channelId }
        Minecraft.getInstance().player?.sendSystemMessage(
            FaChat.prefixed("§7Ticket closed §8| §7${t.server} §8| ${tierColor(t.tier)}${t.tier} §8| §7${t.ign}")
        )
    }

    private fun button(label: String, color: String, click: ClickEvent, hover: String): MutableComponent =
        Component.literal("$color§l§n$label").withStyle { s: Style ->
            s.withClickEvent(click).withHoverEvent(HoverEvent.ShowText(Component.literal("§7$hover")))
        }

    /**
     * Claiming: Kuudra Gang tickets carry a Claim button (custom_id
     * "ticket:claim:<tier>") that the bot clicks; Skyblock Maniacs uses the
     * ticket bot's /claim slash command. SkyBlockZ is unverified, so no button.
     */
    private fun canClaim(t: Ticket) =
        t.server.equals("Skyblock Maniacs", ignoreCase = true) || t.server.equals("Kuudra Gang", ignoreCase = true)

    private fun tierColor(tier: String): String = when (tier.lowercase()) {
        "infernal" -> "§4"
        "fiery" -> "§c"
        "burning" -> "§6"
        "hot" -> "§e"
        "basic" -> "§a"
        else -> "§f"
    }

    /**
     * `/fa ticketopen <channelId>`: jump the Discord desktop app straight to the
     * ticket via its deep link (discord://-/channels/server/channel/message),
     * no browser and no "open link?" prompt. Falls back to the https link in
     * the default browser if the app is not installed.
     */
    fun open(channelId: String) {
        if (!enabled()) return
        val t = recent.firstOrNull { it.channelId == channelId } ?: run {
            FaChat.send("§cThat ticket is no longer in memory."); return
        }
        val path = "channels/${t.serverId}/${t.channelId}" + (if (t.messageId.isNotEmpty()) "/${t.messageId}" else "")
        Thread({
            // 0. Vesktop/Electron DevTools protocol, when the app was started with
            //    --remote-debugging-port=<Discord Debug Port>: runs Discord's own
            //    router inside the page, the one path that works on a running Vesktop.
            val viaCdp = try {
                java.util.concurrent.CompletableFuture.supplyAsync { cdpNavigate(t) }.get(3, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) { false }
            if (viaCdp) return@Thread
            // 1. In-app navigation over the client's local RPC pipe (works while the
            //    app is already running; Vesktop needs its "Rich Presence" setting on).
            val viaRpc = try {
                java.util.concurrent.CompletableFuture.supplyAsync { rpcDeepLink(t) }.get(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) { false }
            if (viaRpc) return@Thread
            // 2. discord:// deep link handed to the registered app (official client
            //    navigates; Vesktop only focuses when already running).
            // 3. The web link.
            val deep = "discord://-/$path"
            val web = "https://discord.com/$path"
            try {
                launch(deep)
            } catch (e: Exception) {
                try { launch(web) } catch (e2: Exception) { FaChat.send("§cCould not open Discord: §7${e2.message}") }
            }
        }, "FA-Tickets-Open").apply { isDaemon = true; start() }
    }

    /**
     * Chrome DevTools Protocol against a Vesktop started with
     * `--remote-debugging-port=<port>` (Dev > Discord Debug Port). Finds the
     * discord.com page target, opens its websocket, and evaluates a one-liner
     * that focuses the window and calls Discord's router via Vencord's
     * exposed webpack commons. Returns true when the page reported "ok".
     */
    private fun cdpNavigate(t: Ticket): Boolean {
        val port = FamilyConfigManager.config.dev.discordDebugPort.trim().toIntOrNull() ?: return false
        if (port <= 0 || t.serverId.isEmpty() || t.channelId.isEmpty()) return false
        val http = java.net.http.HttpClient.newHttpClient()
        val list = http.send(
            java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json")).timeout(java.time.Duration.ofSeconds(1)).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(),
        ).body()
        val target = JsonParser.parseString(list).asJsonArray.map { it.asJsonObject }
            .firstOrNull { it.get("type")?.asString == "page" && (it.get("url")?.asString ?: "").contains("discord.com") }
            ?: return false
        val ws = target.get("webSocketDebuggerUrl")?.asString ?: return false
        val path = "/channels/${t.serverId}/${t.channelId}" + (if (t.messageId.isNotEmpty()) "/${t.messageId}" else "")
        val js = "(()=>{try{if(window.VesktopNative)VesktopNative.win.focus();" +
            "Vencord.Webpack.Common.NavigationRouter.transitionTo(" + com.google.gson.Gson().toJson(path) + ");return 'ok'}" +
            "catch(e){return 'err:'+e}})()"
        val msg = JsonObject().apply {
            addProperty("id", 1); addProperty("method", "Runtime.evaluate")
            add("params", JsonObject().apply { addProperty("expression", js); addProperty("returnByValue", true) })
        }
        val reply = java.util.concurrent.CompletableFuture<String>()
        val buf = StringBuilder()
        val socket = http.newWebSocketBuilder().buildAsync(URI.create(ws), object : java.net.http.WebSocket.Listener {
            override fun onText(webSocket: java.net.http.WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*>? {
                buf.append(data)
                if (last) { reply.complete(buf.toString()); buf.setLength(0) }
                webSocket.request(1)
                return null
            }
            override fun onError(webSocket: java.net.http.WebSocket, error: Throwable) { reply.completeExceptionally(error) }
        }).get(1, java.util.concurrent.TimeUnit.SECONDS)
        socket.sendText(msg.toString(), true).get(1, java.util.concurrent.TimeUnit.SECONDS)
        val text = reply.get(2, java.util.concurrent.TimeUnit.SECONDS)
        socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done")
        val value = JsonParser.parseString(text).asJsonObject.getAsJsonObject("result")?.getAsJsonObject("result")?.get("value")?.asString
        if (value != "ok") FamilyAddons.LOGGER.warn("[FA Tickets] CDP navigate: $value")
        return value == "ok"
    }

    /**
     * Discord's local RPC server (official client) or arRPC (Vesktop) listens
     * on the named pipes discord-ipc-0..9. Frames are [op:int32 LE][len:int32
     * LE][json]. After a HANDSHAKE (op 0), a DEEP_LINK command (op 1) makes
     * the running client navigate in place: {"type":"CHANNEL","params":
     * {"guildId","channelId","messageId"}}. Returns true when acknowledged.
     */
    private fun rpcDeepLink(t: Ticket): Boolean {
        if (!System.getProperty("os.name", "").lowercase().contains("win")) return false
        if (t.serverId.isEmpty() || t.channelId.isEmpty()) return false
        for (i in 0..9) {
            val pipe = """\\.\pipe\discord-ipc-$i"""
            try {
                java.io.RandomAccessFile(pipe, "rw").use { f ->
                    fun send(op: Int, json: String) {
                        val body = json.toByteArray(Charsets.UTF_8)
                        val buf = java.nio.ByteBuffer.allocate(8 + body.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        buf.putInt(op).putInt(body.size).put(body)
                        f.write(buf.array())
                    }
                    fun recv(): String {
                        val head = ByteArray(8); f.readFully(head)
                        val len = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(4)
                        if (len < 0 || len > 1_000_000) throw IllegalStateException("bad frame length $len")
                        val body = ByteArray(len); f.readFully(body)
                        return String(body, Charsets.UTF_8)
                    }
                    send(0, """{"v":1,"client_id":"1045800378228281345"}""")
                    val ready = recv()
                    if (!ready.contains("READY")) return false
                    val params = JsonObject().apply {
                        addProperty("guildId", t.serverId)
                        addProperty("channelId", t.channelId)
                        if (t.messageId.isNotEmpty()) addProperty("messageId", t.messageId)
                    }
                    val args = JsonObject().apply { addProperty("type", "CHANNEL"); add("params", params) }
                    val cmd = JsonObject().apply {
                        addProperty("cmd", "DEEP_LINK"); add("args", args)
                        addProperty("nonce", java.util.UUID.randomUUID().toString())
                    }
                    send(1, cmd.toString())
                    val resp = recv()
                    return !resp.contains("\"evt\":\"ERROR\"")
                }
            } catch (_: Exception) {
                // pipe not open / not a Discord RPC server: try the next index
            }
        }
        return false
    }

    /**
     * Command template registered for discord:// (whatever app owns it: the
     * official client, Vesktop, ...), resolved once from the registry. Tokens
     * with "%1" replaced by the URL at launch time; null = unknown.
     */
    @Volatile private var discordHandler: List<String>? = null
    @Volatile private var discordHandlerLooked = false

    private fun findDiscordHandler(): List<String>? {
        if (discordHandlerLooked) return discordHandler
        discordHandlerLooked = true
        try {
            val proc = ProcessBuilder("reg", "query", """HKCU\Software\Classes\discord\shell\open\command""", "/ve")
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            // ... REG_SZ    "C:\...esktop.exe" "%1"    or    "C:\...\Discord.exe" --url -- "%1"
            val line = out.lines().firstOrNull { it.contains("REG_SZ") } ?: return null
            val cmdLine = line.substringAfter("REG_SZ").trim()
            val tokens = Regex(""""([^"]*)"|(\S+)""").findAll(cmdLine).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
            val exe = tokens.firstOrNull() ?: return null
            if (!exe.endsWith(".exe", ignoreCase = true) || !java.io.File(exe).exists()) return null
            discordHandler = if (tokens.any { it.contains("%1") }) tokens else tokens + "%1"
        } catch (_: Exception) {}
        return discordHandler
    }

    /** Hand a URL to the app. On Windows call the registered discord:// handler straight (skips the shell hop). */
    private fun launch(url: String) {
        val os = System.getProperty("os.name", "").lowercase()
        val cmd = when {
            os.contains("win") -> {
                val handler = if (url.startsWith("discord://")) findDiscordHandler() else null
                handler?.map { it.replace("%1", url) }
                    ?: listOf("rundll32", "url.dll,FileProtocolHandler", url)
            }
            os.contains("mac") -> listOf("open", url)
            else -> listOf("xdg-open", url)
        }
        ProcessBuilder(cmd).redirectErrorStream(true).start()
    }

    /**
     * `/fa claim <channelId>`: copies the ticket's IGN to the clipboard and
     * writes one JSON line to the bot's claim port. Nothing else.
     */
    fun claim(channelId: String) {
        if (!enabled()) return
        val t = recent.firstOrNull { it.channelId == channelId }
        if (t != null && !canClaim(t)) {
            FaChat.send("§cClaim is only wired for Kuudra Gang and Skyblock Maniacs right now.")
            return
        }
        if (t != null && t.ign.isNotEmpty()) {
            Minecraft.getInstance().keyboardHandler.setClipboard(t.ign)
        }
        Thread({
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), CLAIM_PORT), 2000)
                    val w = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                    w.write(JsonObject().apply {
                        addProperty("action", "claim"); addProperty("channel_id", channelId)
                        if (t != null) { addProperty("server", t.server); addProperty("message_id", t.messageId) }
                    }.toString() + "\n")
                    w.flush()
                }
                val who = t?.let { "${it.tier} x${it.runs} ${it.ign}" } ?: channelId
                FaChat.send("§aClaim sent §7for §b$who" + (if (t?.ign?.isNotEmpty() == true) " §8(IGN copied)" else ""))
            } catch (e: Exception) {
                FaChat.send("§cClaim failed: §7${e.message} §8(is bot.py running?)")
            }
        }, "FA-Tickets-Claim").apply { isDaemon = true; start() }
    }

    fun debugDump(): String = buildString {
        appendLine("§d[Tickets] §7enabled=${enabled()} listening=${serverSocket?.let { !it.isClosed } ?: false} port=$LISTEN_PORT claimPort=$CLAIM_PORT")
        appendLine("§7 tickets=$ticketCount lastAgo=${if (lastTicketMs == 0L) "-" else "${(System.currentTimeMillis() - lastTicketMs) / 1000}s"} lastError=${lastError ?: "-"}")
        recent.take(5).forEach { appendLine("§7  ${it.server} | ${it.tier} x${it.runs} | ${it.ign} | ${it.channelId}") }
    }
}
