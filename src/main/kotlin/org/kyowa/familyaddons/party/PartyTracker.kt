package org.kyowa.familyaddons.party

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft

object PartyTracker {

    private val RANK_REGEX = Regex("""\[[^\]]+\]\s*""")
    private val SYMBOL_REGEX = Regex("[+★✦✧☆✪✫✬✭✮✯❖◆◇◈•●▪■▶»():,]")
    private val SPACE_REGEX = Regex("""\s+""")
    private val NAME_REGEX = Regex("[A-Za-z0-9_]{3,16}")
    private val PARTY_CHAT_REGEX = Regex("""^Party\s*[>»]\s*(?:\[[^\]]+\]\s*)?([^:]+):\s*(.+)$""")
    private val JOINED_REGEX = Regex("""^(?:\[[^\]]+\]\s*)?(.+?) joined the party\.$""", RegexOption.IGNORE_CASE)
    private val LEFT_REGEX = Regex("""^(?:\[[^\]]+\]\s*)?(.+?) (?:left the party|has left the party)\.$""", RegexOption.IGNORE_CASE)
    private val KICKED_REGEX = Regex("""^(?:\[[^\]]+\]\s*)?(.+?) has been removed from the party\.$""", RegexOption.IGNORE_CASE)
    private val TRANSFER_REGEX = Regex("""^The party was transferred to (?:\[[^\]]+\]\s*)?(\S+)""", RegexOption.IGNORE_CASE)
    private val DISBANDED_REGEX = Regex("""disbanded the party|You left the party\.|The party has been disbanded""", RegexOption.IGNORE_CASE)
    private val JOINED_THEIR_PARTY_REGEX = Regex("""^You have joined (?:\[[^\]]+\]\s*)?(\S+)'s party!$""", RegexOption.IGNORE_CASE)
    private val PARTYING_WITH_REGEX = Regex("""^You'll be partying with: (.+)$""", RegexOption.IGNORE_CASE)
    /** Header of /p list: "Party Members (4)" — the one line that states the real size. */
    private val COUNT_REGEX = Regex("""^Party Members \((\d+)\)$""")
    private val PF_JOIN_REGEX = Regex("""^Party Finder > (?:\[[^\]]+\]\s*)?([A-Za-z0-9_]{3,16}) joined the (?:dungeon )?group!""", RegexOption.IGNORE_CASE)
    private val OFFLINE_REMOVED_REGEX = Regex("""^(?:Kicked (?:\[[^\]]+\]\s*)?([A-Za-z0-9_]{3,16}) because they were offline\.|(?:\[[^\]]+\]\s*)?([A-Za-z0-9_]{3,16}) was removed from (?:your|the) party because they disconnected\.?)$""", RegexOption.IGNORE_CASE)
    private val KICKED_SELF_REGEX = Regex("""^You have been kicked from the party by""", RegexOption.IGNORE_CASE)
    private val NOT_IN_PARTY_REGEX = Regex("""^You are not (?:currently )?in a party""", RegexOption.IGNORE_CASE)

    // name -> rank string (e.g. "MVP+")
    val members = mutableMapOf<String, String>()
    var leader: String? = null

    /** Size from the last /p list header; -1 once membership changed since. */
    @Volatile var listedCount: Int = -1

    /**
     * Best current party size, never below 1 (you). Uses the /p list header
     * when fresh, else the cached names plus yourself (the cache never learns
     * you from chat), and as a floor the real players standing in this world
     * that also have a tab-list entry (Hypixel NPCs have no tab entry, tab
     * info lines have no entity), which covers a cache that missed someone.
     */
    fun partySize(): Int {
        val mc = Minecraft.getInstance()
        val self = mc.player?.name?.string
        val names = members.keys.mapTo(HashSet()) { it.lowercase() }
        if (self != null) names.add(self.lowercase())
        val cached = if (listedCount > 0) listedCount else names.size
        val tab = mc.connection?.onlinePlayers?.mapTo(HashSet()) { it.profile.id } ?: emptySet()
        val inWorld = mc.level?.players()?.count { it.uuid in tab } ?: 0
        return maxOf(cached, inWorld, 1)
    }

    fun register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            handleLine(plain, message.string)
            true
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            members.clear()
            leader = null
            listedCount = -1
        }
    }

    private fun extractRank(raw: String): String {
        val match = Regex("""\[([^\]]+)\]""").find(raw)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun handleLine(plain: String, original: String) {
        // Party chat — sender is a member
        val partyChat = PARTY_CHAT_REGEX.find(plain)
        if (partyChat != null) {
            val name = cleanName(partyChat.groupValues[1])
            if (name.isNotEmpty() && !members.containsKey(name)) {
                members[name] = ""
            }
            return
        }

        // /p list responses
        COUNT_REGEX.find(plain)?.let { listedCount = it.groupValues[1].toIntOrNull() ?: -1 }
        when {
            plain.startsWith("Party Leader:") -> {
                val after = plain.removePrefix("Party Leader:").trim()
                val rank = extractRank(after)
                val name = cleanName(after)
                if (name.isNotEmpty()) {
                    leader = name
                    members[name] = rank
                }
            }
            plain.startsWith("Party Moderators:") || plain.startsWith("Party Members:") -> {
                extractNamesWithRanks(plain).forEach { (name, rank) -> members[name] = rank }
            }
        }

        // Join
        JOINED_REGEX.find(plain)?.let {
            val raw = it.groupValues[1]
            val rank = extractRank(raw)
            val name = cleanName(raw)
            if (name.isNotEmpty()) { members[name] = rank; listedCount = -1 }
        }

        // Party Finder join ("Party Finder > [MVP+] X joined the group!")
        PF_JOIN_REGEX.find(plain)?.let {
            val name = cleanName(it.groupValues[1])
            if (name.isNotEmpty()) { members[name] = ""; listedCount = -1 }
        }

        // Leave
        LEFT_REGEX.find(plain)?.let {
            val name = cleanName(it.groupValues[1])
            members.remove(name)
            listedCount = -1
            if (leader?.equals(name, ignoreCase = true) == true) leader = null
        }

        // Kicked
        KICKED_REGEX.find(plain)?.let {
            val name = cleanName(it.groupValues[1])
            members.remove(name)
            listedCount = -1
        }

        // Removed for being offline / disconnected
        OFFLINE_REMOVED_REGEX.find(plain)?.let {
            val name = cleanName(it.groupValues[1].ifEmpty { it.groupValues[2] })
            if (name.isNotEmpty()) { members.remove(name); listedCount = -1 }
        }

        // You got kicked / you are not in a party
        if (KICKED_SELF_REGEX.containsMatchIn(plain) || NOT_IN_PARTY_REGEX.containsMatchIn(plain)) {
            members.clear()
            leader = null
            listedCount = -1
        }

        // Transfer
        TRANSFER_REGEX.find(plain)?.let {
            val name = cleanName(it.groupValues[1])
            if (name.isNotEmpty()) {
                leader = name
                if (!members.containsKey(name)) members[name] = ""
            }
        }

        // Disband / you left
        if (DISBANDED_REGEX.containsMatchIn(plain)) {
            members.clear()
            leader = null
            listedCount = -1
        }

        // "You have joined X's party!"
        JOINED_THEIR_PARTY_REGEX.find(plain)?.let {
            val name = cleanName(it.groupValues[1])
            if (name.isNotEmpty()) {
                leader = name
                members[name] = ""
            }
        }

        // "You'll be partying with: [RANK] A, [RANK] B, ..."
        PARTYING_WITH_REGEX.find(plain)?.let {
            val rest = it.groupValues[1]
            // Split by comma then parse each segment
            rest.split(",").forEach { seg ->
                val rank = extractRank(seg.trim())
                val name = cleanName(seg.trim())
                if (name.isNotEmpty()) members[name] = rank
            }
        }
    }

    fun cleanName(s: String): String {
        var x = s.replace(RANK_REGEX, " ").trim()
        x = x.replace(SYMBOL_REGEX, " ")
        x = x.replace(SPACE_REGEX, " ").trim()
        val tokens = x.split(" ").filter { it.matches(NAME_REGEX) }
        return tokens.lastOrNull() ?: ""
    }

    private fun extractNamesWithRanks(line: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        // Split by ● or • separators that Hypixel uses between names
        val segments = line.split(Regex("[●•]"))
        for (seg in segments) {
            val rank = extractRank(seg)
            val name = cleanName(seg)
            if (name.isNotEmpty()) results.add(name to rank)
        }
        return results
    }

    fun isLeader(name: String): Boolean =
        leader?.equals(name, ignoreCase = true) == true

    fun resolveMember(query: String, allowSelf: Boolean, selfName: String): String? {
        val pool = if (allowSelf) members.keys.toList()
        else members.keys.filter { it.lowercase() != selfName.lowercase() }
        if (pool.isEmpty()) return null
        val q = query.lowercase()
        pool.find { it.lowercase() == q }?.let { return it }
        val tie = { arr: List<String> -> arr.sortedWith(compareBy({ it.length }, { it })).firstOrNull() }
        val starts = pool.filter { it.lowercase().startsWith(q) }
        if (starts.size == 1) return starts[0]
        if (starts.size > 1) return tie(starts)
        val inc = pool.filter { it.lowercase().contains(q) }
        if (inc.isEmpty()) return null
        return tie(inc)
    }
}
