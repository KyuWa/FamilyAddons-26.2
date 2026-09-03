package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.Blocks
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.Lightmap
import net.minecraft.client.renderer.SubmitNodeCollector
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.network.chat.Component
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.chunk.LevelChunk
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

object WorldScanner {

    data class WaypointData(
        val pos: BlockPos,
        val category: Int,
        val r: Float,
        val g: Float,
        val b: Float
    )

    private val waypoints = ConcurrentHashMap<String, WaypointData>()
    private val scannedChunks = ConcurrentHashMap.newKeySet<Long>()
    private val lavaBlocks = ConcurrentHashMap.newKeySet<Long>()
    private val waterBlocks = ConcurrentHashMap.newKeySet<Long>()

    private enum class Quarter {
        NUCLEUS, JUNGLE, PRECURSOR, GOBLIN, MITHRIL, MAGMA, ANY;
        fun test(x: Int, y: Int, z: Int): Boolean = when (this) {
            NUCLEUS   -> x in 449..576 && z in 449..576
            JUNGLE    -> x <= 576 && z <= 576
            PRECURSOR -> x > 448 && z > 448
            GOBLIN    -> x <= 576 && z > 448
            MITHRIL   -> x > 448 && z <= 576
            MAGMA     -> y < 80
            ANY       -> true
        }
    }

    private enum class Structure(
        val displayName: String,
        val blocks: List<Block?>,
        val r: Float, val g: Float, val b: Float,
        val quarter: Quarter,
        val offsetX: Int = 0, val offsetY: Int = 0, val offsetZ: Int = 0,
        val category: Int
    ) {
        KING("King",
            listOf(Blocks.WOOL.red(), Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_STAIRS),
            1f, 0.67f, 0f, Quarter.GOBLIN, 1, -1, 2, 0),
        QUEEN("Queen",
            listOf(Blocks.STONE, Blocks.ACACIA_WOOD, Blocks.ACACIA_WOOD, Blocks.ACACIA_WOOD, Blocks.ACACIA_WOOD, Blocks.CAULDRON),
            1f, 0.67f, 0f, Quarter.ANY, 0, 5, 0, 0),
        DIVAN("Divan",
            listOf(Blocks.QUARTZ_PILLAR, Blocks.QUARTZ_STAIRS, Blocks.STONE_BRICK_STAIRS, Blocks.CHISELED_STONE_BRICKS),
            0f, 1f, 0f, Quarter.MITHRIL, 0, 5, 0, 0),
        CITY("City",
            listOf(Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE,
                Blocks.COBBLESTONE_STAIRS, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE, Blocks.DARK_OAK_STAIRS),
            0f, 1f, 1f, Quarter.PRECURSOR, 24, 0, -17, 0),
        TEMPLE("Temple",
            listOf(Blocks.BEDROCK, Blocks.BEDROCK, Blocks.BEDROCK, Blocks.BEDROCK, Blocks.STONE, Blocks.CLAY,
                Blocks.CLAY, Blocks.CLAY, Blocks.OAK_LEAVES, Blocks.OAK_LEAVES, Blocks.DYED_TERRACOTTA.lime(),
                Blocks.DYED_TERRACOTTA.lime(), Blocks.DYED_TERRACOTTA.green()),
            0.67f, 0f, 1f, Quarter.ANY, -45, 47, -18, 0),
        BAL("Bal",
            listOf(Blocks.LAVA, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER,
                Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER),
            1f, 0.67f, 0f, Quarter.MAGMA, 0, 1, 0, 0),
        CORLEONE_DOCK("Corleone Dock",
            listOf(Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.FIRE, Blocks.STONE_BRICKS),
            0f, 1f, 0f, Quarter.MITHRIL, 23, 11, 17, 1),
        CORLEONE_HOLE("Corleone Hole",
            listOf(Blocks.SMOOTH_STONE_SLAB, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.POLISHED_GRANITE),
            0f, 1f, 0f, Quarter.MITHRIL, -18, -1, 29, 1),
        KEY_GUARDIAN_SPIRAL("Key Guardian Spiral",
            listOf(Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_PLANKS, Blocks.GLOWSTONE),
            0.67f, 0f, 1f, Quarter.JUNGLE, 0, 0, 0, 1),
        KEY_GUARDIAN_TOWER("Key Guardian Tower",
            listOf(Blocks.STONE, Blocks.POLISHED_GRANITE, Blocks.JUNGLE_SLAB),
            0.67f, 0f, 1f, Quarter.JUNGLE, 0, 0, 0, 1),
        XALX("Xalx",
            listOf(Blocks.STONE, Blocks.COAL_BLOCK, Blocks.FIRE, Blocks.NETHER_QUARTZ_ORE,
                Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR),
            0f, 1f, 0f, Quarter.GOBLIN, -2, 1, -2, 1),
        PETE("Pete",
            listOf(Blocks.NETHERRACK, Blocks.FIRE, Blocks.IRON_BARS,
                Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR),
            1f, 0.67f, 0f, Quarter.GOBLIN, 0, 0, 0, 1),
        ODAWA("Odawa",
            listOf(Blocks.JUNGLE_LOG, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.JUNGLE_LOG),
            0f, 1f, 0f, Quarter.JUNGLE, 0, 0, 0, 1),
        GOLDEN_DRAGON("Golden Dragon",
            listOf(Blocks.STONE, Blocks.DYED_TERRACOTTA.red(), Blocks.DYED_TERRACOTTA.red(), Blocks.DYED_TERRACOTTA.red(),
                Blocks.PLAYER_HEAD, Blocks.WOOL.red()),
            1f, 1f, 1f, Quarter.ANY, 0, -3, 5, 3)
    }

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> clearAll() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> clearAll() }

        ClientChunkEvents.CHUNK_LOAD.register { _, chunk ->
            if (!FamilyConfigManager.config.worldScanner.enabled) return@register
            if (!isInCrystalHollows()) return@register
            val key = chunkKey(chunk.pos.x, chunk.pos.z)
            if (!scannedChunks.add(key)) return@register
            Thread {
                try { scanChunk(chunk) } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()
        }
    }

    private fun scanChunk(chunk: LevelChunk) {
        val cfg = FamilyConfigManager.config.worldScanner
        val mutablePos = BlockPos.MutableBlockPos()

        for (lx in 0..15) {
            for (lz in 0..15) {
                val wx = chunk.pos.x * 16 + lx
                val wz = chunk.pos.z * 16 + lz
                for (y in 0..170) {
                    val state = getBlockState(chunk, lx, y, lz)
                    if (state.isAir) continue
                    val block = state.block
                    when (block) {
                        Blocks.WOOL.red() -> {
                            if (cfg.scanCrystals && !waypoints.containsKey("King") && Quarter.GOBLIN.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.KING.blocks)) addWaypoint(Structure.KING, wx, y, wz)
                        }
                        Blocks.STONE -> {
                            if (cfg.scanCrystals && !waypoints.containsKey("Queen") && Quarter.ANY.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.QUEEN.blocks)) addWaypoint(Structure.QUEEN, wx, y, wz)
                            if (cfg.scanMobSpots && !waypoints.containsKey("Key Guardian Tower") && Quarter.JUNGLE.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.KEY_GUARDIAN_TOWER.blocks)) addWaypoint(Structure.KEY_GUARDIAN_TOWER, wx, y, wz)
                            if (cfg.scanMobSpots && !waypoints.containsKey("Xalx") && Quarter.GOBLIN.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.XALX.blocks)) addWaypoint(Structure.XALX, wx, y, wz)
                            if (cfg.scanDragonNest && !waypoints.containsKey("Golden Dragon") && Quarter.ANY.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.GOLDEN_DRAGON.blocks)) addWaypoint(Structure.GOLDEN_DRAGON, wx, y, wz)
                        }
                        Blocks.QUARTZ_PILLAR -> {
                            if (cfg.scanCrystals && !waypoints.containsKey("Divan") && Quarter.MITHRIL.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.DIVAN.blocks)) addWaypoint(Structure.DIVAN, wx, y, wz)
                        }
                        Blocks.STONE_BRICKS -> {
                            if (cfg.scanCrystals && !waypoints.containsKey("City") && Quarter.PRECURSOR.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.CITY.blocks)) addWaypoint(Structure.CITY, wx, y, wz)
                            if (cfg.scanMobSpots && !waypoints.containsKey("Corleone Dock") && Quarter.MITHRIL.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.CORLEONE_DOCK.blocks)) addWaypoint(Structure.CORLEONE_DOCK, wx, y, wz)
                        }
                        Blocks.BEDROCK -> {
                            if (cfg.scanCrystals && !waypoints.containsKey("Temple") && Quarter.ANY.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.TEMPLE.blocks)) addWaypoint(Structure.TEMPLE, wx, y, wz)
                        }
                        Blocks.LAVA -> {
                            if (cfg.lavaEsp && getBlockState(chunk, lx, y + 1, lz).isAir)
                                lavaBlocks.add(BlockPos(wx, y, wz).asLong())
                            if (cfg.scanCrystals && !waypoints.containsKey("Bal") && Quarter.MAGMA.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.BAL.blocks)) addWaypoint(Structure.BAL, wx, y, wz)
                            if (cfg.scanWormFishing && y > 63 && !waypoints.containsKey("Worm Fishing")
                                && ((wx >= 564 && wz >= 513) || (wx >= 513 && wz >= 564))) {
                                if (getBlockState(chunk, lx, y + 1, lz).isAir) {
                                    mutablePos.set(wx, y, wz)
                                    addWaypointDirect("Worm Fishing", mutablePos, 1f, 0.67f, 0f, 4)
                                }
                            }
                        }
                        Blocks.WATER -> {
                            if (cfg.waterEsp && getBlockState(chunk, lx, y + 1, lz).isAir)
                                waterBlocks.add(BlockPos(wx, y, wz).asLong())
                        }
                        Blocks.SMOOTH_STONE_SLAB -> {
                            if (cfg.scanMobSpots && !waypoints.containsKey("Corleone Hole") && Quarter.MITHRIL.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.CORLEONE_HOLE.blocks)) addWaypoint(Structure.CORLEONE_HOLE, wx, y, wz)
                        }
                        Blocks.JUNGLE_STAIRS -> {
                            if (cfg.scanMobSpots && !waypoints.containsKey("Key Guardian Spiral") && Quarter.JUNGLE.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.KEY_GUARDIAN_SPIRAL.blocks)) addWaypoint(Structure.KEY_GUARDIAN_SPIRAL, wx, y, wz)
                        }
                        Blocks.NETHERRACK -> {
                            if (cfg.scanMobSpots && !waypoints.containsKey("Pete") && Quarter.GOBLIN.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.PETE.blocks)) addWaypoint(Structure.PETE, wx, y, wz)
                        }
                        Blocks.JUNGLE_LOG -> {
                            if (cfg.scanMobSpots && !waypoints.containsKey("Odawa") && Quarter.JUNGLE.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.ODAWA.blocks)) addWaypoint(Structure.ODAWA, wx, y, wz)
                        }
                        Blocks.STAINED_GLASS.magenta(), Blocks.STAINED_GLASS_PANE.magenta() -> {
                            if (cfg.scanFairyGrottos && !waypoints.containsKey("Fairy Grotto") && !Quarter.NUCLEUS.test(wx, y, wz)) {
                                mutablePos.set(wx, y, wz)
                                addWaypointDirect("Fairy Grotto", mutablePos, 1f, 0.41f, 1f, 2)
                            }
                        }
                    }
                }
            }
        }
    }

    fun hasWaypoints(): Boolean = waypoints.isNotEmpty() || lavaBlocks.isNotEmpty() || waterBlocks.isNotEmpty()

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, cam: Vec3) {
        val cfg = FamilyConfigManager.config.worldScanner
        if (!cfg.enabled) return
        if (!isInCrystalHollows()) return
        if (!hasWaypoints()) return

        val visible = waypoints.entries.filter { (_, data) ->
            when (data.category) {
                0 -> cfg.scanCrystals
                1 -> cfg.scanMobSpots
                2 -> cfg.scanFairyGrottos
                3 -> cfg.scanDragonNest
                4 -> cfg.scanWormFishing
                else -> false
            }
        }

        fun drawBox(pos: BlockPos, r: Float, g: Float, b: Float, alpha: Float, renderType: net.minecraft.client.renderer.rendertype.RenderType) {
            collector.submitCustomGeometry(matrices, renderType) { entry, buf ->
                val x1 = (pos.x - cam.x).toFloat()
                val y1 = (pos.y - cam.y).toFloat()
                val z1 = (pos.z - cam.z).toFloat()
                drawBoxEdges(buf, entry, x1, y1, z1, x1 + 1f, y1 + 1f, z1 + 1f, r, g, b, alpha)
            }
        }

        // Structure boxes - full alpha both passes
        for ((_, data) in visible) {
            drawBox(data.pos, data.r, data.g, data.b, 1.0f, FamilyRenderTypes.LINES)
            drawBox(data.pos, data.r, data.g, data.b, 1.0f, FamilyRenderTypes.LINES_NO_DEPTH)
        }

        // Lava ESP
        if (cfg.lavaEsp) {
            val rangeSq = cfg.espRange * cfg.espRange
            for (packed in lavaBlocks) {
                val pos = BlockPos.of(packed)
                val dx = pos.x - cam.x; val dy = pos.y - cam.y; val dz = pos.z - cam.z
                if (dx*dx + dy*dy + dz*dz <= rangeSq) {
                    drawBox(pos, 1f, 0.67f, 0f, 1.0f, FamilyRenderTypes.LINES)
                    drawBox(pos, 1f, 0.67f, 0f, 1.0f, FamilyRenderTypes.LINES_NO_DEPTH)
                }
            }
        }

        // Water ESP
        if (cfg.waterEsp) {
            val rangeSq = cfg.espRange * cfg.espRange
            for (packed in waterBlocks) {
                val pos = BlockPos.of(packed)
                val dx = pos.x - cam.x; val dy = pos.y - cam.y; val dz = pos.z - cam.z
                if (dx*dx + dy*dy + dz*dz <= rangeSq) {
                    drawBox(pos, 0f, 0.67f, 1f, 1.0f, FamilyRenderTypes.LINES)
                    drawBox(pos, 0f, 0.67f, 1f, 1.0f, FamilyRenderTypes.LINES_NO_DEPTH)
                }
            }
        }

        // Labels
        if (cfg.renderText) {
            for ((name, data) in visible) {
                val wx = data.pos.x + 0.5; val wy = data.pos.y + 2.2; val wz = data.pos.z + 0.5
                val dx = wx - cam.x; val dy = wy - cam.y; val dz = wz - cam.z
                val dist = sqrt(dx*dx + dy*dy + dz*dz)
                renderLabel(matrices, collector, cam, wx, wy, wz, "§f$name §7(${dist.toInt()}m)", dist)
            }
        }
    }

    private fun renderLabel(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        cam: Vec3,
        x: Double, y: Double, z: Double,
        text: String,
        dist: Double
    ) {
        val client = Minecraft.getInstance()
        val tr = client.font
        val scale = (dist / 10.0).coerceIn(1.0, 5.0).toFloat() * 0.025f

        matrices.pushPose()
        matrices.translate(x - cam.x, y - cam.y, z - cam.z)
        matrices.mulPose(client.gameRenderer.mainCamera().rotation())
        matrices.scale(scale, -scale, scale)
        val w = tr.width(text.replace(COLOR_CODE_REGEX, ""))
        collector.submitText(
            matrices, -w / 2f, 0f,
            Component.literal(text).visualOrderText, true,
            net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
            15728880, -1, 0, 0
        )
        matrices.popPose()
    }

    private fun addWaypoint(structure: Structure, x: Int, y: Int, z: Int) {
        if (waypoints.containsKey(structure.displayName)) return
        val targetPos = BlockPos(x + structure.offsetX, y + structure.offsetY, z + structure.offsetZ)
        waypoints[structure.displayName] = WaypointData(targetPos, structure.category, structure.r, structure.g, structure.b)
        if (FamilyConfigManager.config.worldScanner.sendCoordsInChat) {
            Minecraft.getInstance().execute {
                Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§6[FA] §a${structure.displayName} §7found at §b${targetPos.x}, ${targetPos.y}, ${targetPos.z}"))
            }
        }
    }

    private fun addWaypointDirect(name: String, pos: BlockPos, r: Float, g: Float, b: Float, category: Int) {
        if (waypoints.containsKey(name)) return
        waypoints[name] = WaypointData(pos.immutable(), category, r, g, b)
        if (FamilyConfigManager.config.worldScanner.sendCoordsInChat) {
            Minecraft.getInstance().execute {
                Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§6[FA] §a$name §7found at §b${pos.x}, ${pos.y}, ${pos.z}"))
            }
        }
    }

    private fun checkSequence(chunk: LevelChunk, lx: Int, y: Int, lz: Int, sequence: List<Block?>): Boolean {
        val mutable = BlockPos.MutableBlockPos()
        for (i in sequence.indices) {
            val expected = sequence[i] ?: continue
            val py = y + i
            if (py > 255) return false
            mutable.set(lx, py, lz)
            if (chunk.getBlockState(mutable).block != expected) return false
        }
        return true
    }

    private fun getBlockState(chunk: LevelChunk, lx: Int, y: Int, lz: Int): BlockState {
        if (y < 0 || y > 255) return Blocks.AIR.defaultBlockState()
        val sectionIndex = (y shr 4) - chunk.minSectionY
        val sections = chunk.sections
        if (sectionIndex < 0 || sectionIndex >= sections.size) return Blocks.AIR.defaultBlockState()
        val section = sections[sectionIndex] ?: return Blocks.AIR.defaultBlockState()
        if (section.hasOnlyAir()) return Blocks.AIR.defaultBlockState()
        return section.getBlockState(lx, y and 15, lz)
    }

    private fun isInCrystalHollows(): Boolean {
        return try {
            val tabList = Minecraft.getInstance().connection?.onlinePlayers ?: return false
            for (entry in tabList) {
                val name = entry.tabListDisplayName?.string?.replace(COLOR_CODE_REGEX, "")?.trim() ?: continue
                if (name.startsWith("Area:")) return name.removePrefix("Area:").trim() == "Crystal Hollows"
            }
            false
        } catch (_: Exception) { false }
    }

    private fun clearAll() {
        waypoints.clear(); scannedChunks.clear(); lavaBlocks.clear(); waterBlocks.clear()
    }

    private fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)

    private fun drawBoxEdges(
        buf: com.mojang.blaze3d.vertex.VertexConsumer,
        entry: com.mojang.blaze3d.vertex.PoseStack.Pose,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val edges = arrayOf(
            floatArrayOf(x1,y1,z1,x2,y1,z1), floatArrayOf(x2,y1,z1,x2,y1,z2),
            floatArrayOf(x2,y1,z2,x1,y1,z2), floatArrayOf(x1,y1,z2,x1,y1,z1),
            floatArrayOf(x1,y2,z1,x2,y2,z1), floatArrayOf(x2,y2,z1,x2,y2,z2),
            floatArrayOf(x2,y2,z2,x1,y2,z2), floatArrayOf(x1,y2,z2,x1,y2,z1),
            floatArrayOf(x1,y1,z1,x1,y2,z1), floatArrayOf(x2,y1,z1,x2,y2,z1),
            floatArrayOf(x2,y1,z2,x2,y2,z2), floatArrayOf(x1,y1,z2,x1,y2,z2)
        )
        for (e in edges) {
            val dx = e[3]-e[0]; val dy = e[4]-e[1]; val dz = e[5]-e[2]
            buf.addVertex(entry, e[0], e[1], e[2]).setColor(r, g, b, a).setNormal(entry, dx, dy, dz).setLineWidth(2.0f)
            buf.addVertex(entry, e[3], e[4], e[5]).setColor(r, g, b, a).setNormal(entry, dx, dy, dz).setLineWidth(2.0f)
        }
    }
}
