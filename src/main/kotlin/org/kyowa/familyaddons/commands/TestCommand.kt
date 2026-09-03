package org.kyowa.familyaddons.commands

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.features.BestiaryZoneHighlight
import org.kyowa.familyaddons.features.NpcLocations
import org.kyowa.familyaddons.features.Parkour
import org.kyowa.familyaddons.features.PartyRepCheck
import org.kyowa.familyaddons.features.Waypoints
import org.kyowa.familyaddons.party.PartyTracker

object TestCommand {

    var openGuiNextTick = false
    var openConfigNextTick = false

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->

            dispatcher.register(
                literal("fa")
                    // /fa — open config
                    .executes {
                        openConfigNextTick = true
                        1
                    }

                    // /fa gui
                    .then(literal("gui").executes {
                        openGuiNextTick = true
                        1
                    })

                    // /fa party
                    .then(literal("party").executes { ctx ->
                        val p = ctx.source.player
                        val members = PartyTracker.members
                        if (members.isEmpty()) {
                            p.sendSystemMessage(Component.literal("§6[FA] §7No cached party members."))
                        } else {
                            p.sendSystemMessage(Component.literal("§6[FA] §eCached party members:"))
                            members.forEach { (name, rank) ->
                                val rankStr = if (rank.isNotEmpty()) "§7[§f$rank§7] " else ""
                                val leaderMark = if (PartyTracker.isLeader(name)) " §6(leader)" else ""
                                p.sendSystemMessage(Component.literal("  $rankStr§f$name$leaderMark"))
                            }
                        }
                        1
                    })

                    // /fa checkrep <name>
                    .then(literal("checkrep")
                        .then(argument("name", StringArgumentType.word())
                            .executes { ctx ->
                                PartyRepCheck.fetchRep(StringArgumentType.getString(ctx, "name"))
                                1
                            }))

                    // /fa waypoint clear | list | delete <index>
                    .then(literal("waypoint")
                        .then(literal("clear").executes { ctx ->
                            val p = ctx.source.player
                            val island = Waypoints.getCurrentIsland()
                            if (island == null) {
                                p.sendSystemMessage(Component.literal("§c[FA] Can't detect island."))
                            } else {
                                Waypoints.clearWaypoints(island)
                                p.sendSystemMessage(Component.literal("§a[FA] Cleared all waypoints on §e$island§a."))
                            }
                            1
                        })
                        .then(literal("list").executes { ctx ->
                            val p = ctx.source.player
                            val island = Waypoints.getCurrentIsland()
                            if (island == null) { p.sendSystemMessage(Component.literal("§c[FA] Can't detect island.")); return@executes 1 }
                            val wps = Waypoints.getWaypoints(island)
                            if (wps.isEmpty()) { p.sendSystemMessage(Component.literal("§7No waypoints on §e$island§7.")); return@executes 1 }
                            p.sendSystemMessage(Component.literal("§6Waypoints on §e$island§6:"))
                            wps.forEachIndexed { i, wp -> p.sendSystemMessage(Component.literal("  §7[$i] §f${wp.label} §8@ §b${wp.x}, ${wp.y}, ${wp.z}")) }
                            1
                        })
                        .then(literal("delete")
                            .then(argument("index", IntegerArgumentType.integer(0))
                                .executes { ctx ->
                                    val p = ctx.source.player
                                    val island = Waypoints.getCurrentIsland()
                                    if (island == null) { p.sendSystemMessage(Component.literal("§c[FA] Can't detect island.")); return@executes 1 }
                                    val idx = IntegerArgumentType.getInteger(ctx, "index")
                                    // removeWaypoint is the correct function name in Waypoints.kt
                                    if (Waypoints.removeWaypoint(island, idx)) {
                                        p.sendSystemMessage(Component.literal("§a[FA] Deleted waypoint §e$idx§a."))
                                    } else {
                                        p.sendSystemMessage(Component.literal("§c[FA] No waypoint at index $idx."))
                                    }
                                    1
                                })))

                    // /fa npc <name>
                    .then(literal("npc")
                        .then(argument("name", StringArgumentType.greedyString())
                            .executes { ctx ->
                                val p = ctx.source.player
                                val query = StringArgumentType.getString(ctx, "name")
                                val results = NpcLocations.findNpc(query)
                                if (results.isEmpty()) {
                                    p.sendSystemMessage(Component.literal("§c[FA] No NPC found matching '§e$query§c'."))
                                } else {
                                    results.forEach { npc ->
                                        p.sendSystemMessage(Component.literal("§6[FA] §e${npc.name} §7is in §b${npc.location} §7at §f${npc.x.toInt()}, ${npc.y.toInt()}, ${npc.z.toInt()}"))
                                        NpcLocations.activeWaypoints.add(NpcLocations.ActiveNpcWaypoint(npc.name, npc.x, npc.y, npc.z))
                                    }
                                }
                                1
                            }))

                    // /fa bestiarydump — toggle capture mode: while on, every /bestiary
                    // page you open is scanned and logged automatically
                    .then(literal("bestiarydump").executes {
                        BestiaryZoneHighlight.toggleCapture()
                        1
                    })

                    // /fa bestiaryids [filter] — dump Hypixel bestiary kill ids for custom_bestiary.json
                    .then(literal("bestiaryids")
                        .executes {
                            BestiaryZoneHighlight.dumpKillIds("")
                            1
                        }
                        .then(argument("filter", StringArgumentType.greedyString())
                            .executes { ctx ->
                                BestiaryZoneHighlight.dumpKillIds(StringArgumentType.getString(ctx, "filter"))
                                1
                            }))

                    // /fa critterdump — log nearby entities + sparkle data for tuning detection
                    .then(literal("critterdump").executes {
                        org.kyowa.familyaddons.features.SparklingCritterHighlight.dumpNearby()
                        1
                    })

                    // /fa npcclear
                    .then(literal("npcclear").executes { ctx ->
                        NpcLocations.activeWaypoints.clear()
                        ctx.source.player.sendSystemMessage(Component.literal("§6[FA] §7Cleared all NPC waypoints."))
                        1
                    })

                    // /fa parkour ...
                    .then(literal("parkour")
                        .then(literal("start")
                            .executes {
                                Parkour.start(FamilyConfigManager.config.parkour.activeParkour); 1
                            }
                            .then(argument("name", StringArgumentType.word())
                                .executes { ctx ->
                                    Parkour.start(StringArgumentType.getString(ctx, "name")); 1
                                }))
                        .then(literal("stop").executes { Parkour.stop(); 1 })
                        .then(literal("select")
                            .then(argument("name", StringArgumentType.word())
                                .executes { ctx ->
                                    Parkour.selectParkour(StringArgumentType.getString(ctx, "name")); 1
                                }))
                        .then(literal("list").executes { Parkour.listParkours(); 1 })
                        // Dev-only commands below — guarded at runtime
                        .then(literal("add").executes {
                            if (!FamilyConfigManager.config.parkour.developerMode) {
                                Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§6[FA] §cDeveloper mode required. Enable it in §e/fa §c→ Parkour."))
                            } else {
                                val p = Minecraft.getInstance().player ?: return@executes 1
                                // addCheckpoint is the correct function name in Parkour.kt
                                Parkour.addCheckpoint(p.x, p.y + p.eyeHeight, p.z, p.yRot, p.xRot)
                            }
                            1
                        })
                        .then(literal("remove").executes {
                            if (!FamilyConfigManager.config.parkour.developerMode) {
                                Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§6[FA] §cDeveloper mode required."))
                            } else {
                                // removeLast is the correct function name in Parkour.kt
                                Parkour.removeLast()
                            }
                            1
                        })
                        .then(literal("edit")
                            .executes {
                                if (!FamilyConfigManager.config.parkour.developerMode) {
                                    Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§6[FA] §cDeveloper mode required."))
                                } else { Parkour.edit() }
                                1
                            }
                            .then(argument("name", StringArgumentType.word())
                                .executes { ctx ->
                                    if (!FamilyConfigManager.config.parkour.developerMode) {
                                        Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§6[FA] §cDeveloper mode required."))
                                    } else { Parkour.edit(StringArgumentType.getString(ctx, "name")) }
                                    1
                                }))
                        .then(literal("delete")
                            .then(argument("name", StringArgumentType.word())
                                .executes { ctx ->
                                    // deleteParkour is the correct function name in Parkour.kt
                                    Parkour.deleteParkour(StringArgumentType.getString(ctx, "name")); 1
                                }))
                        .then(literal("clear").executes {
                            if (!FamilyConfigManager.config.parkour.developerMode) {
                                Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§6[FA] §cDeveloper mode required."))
                            } else { Parkour.clearAll() }
                            1
                        })
                        .then(literal("resetbest").executes { Parkour.resetBest(); 1 })
                        .then(literal("listcps").executes { Parkour.listCheckpoints(); 1 }))

            )
        }
    }

    fun getFormattedName(ign: String): String {
        return try {
            val tabList = Minecraft.getInstance().connection?.onlinePlayers ?: return "§e$ign"
            for (entry in tabList) {
                val entryName = entry.profile.name ?: continue
                if (!entryName.equals(ign, ignoreCase = true)) continue
                val display = entry.tabListDisplayName?.string ?: continue
                if (display.contains(ign)) return display.substringBefore(ign) + "§e$ign"
            }
            "§e$ign"
        } catch (e: Exception) { "§e$ign" }
    }
}