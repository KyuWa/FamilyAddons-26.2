package org.kyowa.familyaddons.features

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

class HudEditorScreen : Screen(Component.literal("FA HUD Editor")) {

    data class HudElement(
        val id: String,
        val label: String,
        var x: Int,
        var y: Int,
        var w: Int,
        var h: Int,
        var scale: Float = 1f,
        var dragging: Boolean = false,
        var dragOffX: Double = 0.0,
        var dragOffY: Double = 0.0,
        val canScale: Boolean = false,
        val onSave: (HudElement) -> Unit,
        val renderContent: (context: GuiGraphicsExtractor, elem: HudElement) -> Unit
    ) {
        val screenW get() = (w * scale).toInt()
        val screenH get() = (h * scale).toInt()
    }

    private val elements = mutableListOf<HudElement>()
    private var activeElement: HudElement? = null

    override fun init() {
        elements.clear()
        val client = Minecraft.getInstance()
        val sw = client.window.guiScaledWidth
        val sh = client.window.guiScaledHeight
        val tr = client.font

        // Parkour Timer
        elements.add(HudElement(
            id = "parkourTimer", label = "Parkour Timer",
            x = Parkour.hudX, y = Parkour.hudY,
            w = 160, h = 12,
            scale = Parkour.hudScale,
            canScale = true,
            onSave = { elem ->
                Parkour.hudX = elem.x; Parkour.hudY = elem.y
                Parkour.hudScale = elem.scale; Parkour.save()
            },
            renderContent = { ctx, _ ->
                ctx.text(tr, "§f0:12.345  §7CP §f2§7/§f5", 0, 0, -1, true)
            }
        ))

        // Parkour Arrow
        elements.add(HudElement(
            id = "parkourArrow", label = "Parkour Arrow",
            x = Parkour.arrowHudX, y = Parkour.arrowHudY,
            w = 40, h = 22,
            scale = Parkour.arrowHudScale,
            canScale = true,
            onSave = { elem ->
                Parkour.arrowHudX = elem.x; Parkour.arrowHudY = elem.y
                Parkour.arrowHudScale = elem.scale; Parkour.save()
            },
            renderContent = { ctx, _ ->
                ctx.text(tr, "§e↑", 0, 0, -1, true)
                ctx.text(tr, "§f45m", 0, 12, -1, true)
            }
        ))

        // Parkour Checkpoint Notif
        elements.add(HudElement(
            id = "parkourCp", label = "Parkour Checkpoint",
            x = Parkour.cpHudX, y = Parkour.cpHudY,
            w = 160, h = 12,
            scale = Parkour.cpHudScale,
            canScale = true,
            onSave = { elem ->
                Parkour.cpHudX = elem.x; Parkour.cpHudY = elem.y
                Parkour.cpHudScale = elem.scale; Parkour.save()
            },
            renderContent = { ctx, _ ->
                ctx.text(tr, "§e§lCheckpoint 2/5", 0, 0, -1, true)
            }
        ))

        // Key Tracker
        val ktW = 120
        val ktH = InfernalKeyTracker.getLineCount() * 10 + 4
        elements.add(HudElement(
            id = "keyTracker", label = "Key Tracker",
            x = FamilyConfigManager.config.kuudra.keyTrackerHudX,
            y = FamilyConfigManager.config.kuudra.keyTrackerHudY,
            w = ktW, h = ktH,
            canScale = false,
            onSave = { elem ->
                FamilyConfigManager.config.kuudra.keyTrackerHudX = elem.x
                FamilyConfigManager.config.kuudra.keyTrackerHudY = elem.y
            },
            renderContent = { ctx, _ -> InfernalKeyTracker.renderLines(ctx) }
        ))

        // Kuudra DT Title
        val dtScale = DtTitle.getScale()
        val dtPlain = DtTitle.PREVIEW_TEXT.replace(COLOR_CODE_REGEX, "")
        val dtW = tr.width(dtPlain)
        val dtX = if (FamilyConfigManager.config.kuudra.dtTitleHudX == -1)
            ((sw - dtW * dtScale) / 2f).toInt()
        else FamilyConfigManager.config.kuudra.dtTitleHudX
        val dtY = if (FamilyConfigManager.config.kuudra.dtTitleHudY == -1)
            (sh / 2f - 20f).toInt()
        else FamilyConfigManager.config.kuudra.dtTitleHudY

        elements.add(HudElement(
            id = "dtTitle", label = "Kuudra DT Title",
            x = dtX, y = dtY,
            w = dtW, h = 10,
            scale = dtScale,
            canScale = true,
            onSave = { elem ->
                FamilyConfigManager.config.kuudra.dtTitleHudX = elem.x
                FamilyConfigManager.config.kuudra.dtTitleHudY = elem.y
                FamilyConfigManager.config.kuudra.dtTitleScale = "%.1f".format(elem.scale)
            },
            renderContent = { ctx, _ ->
                ctx.text(tr, DtTitle.PREVIEW_TEXT, 0, 0, 0xFFFFFFFF.toInt(), true)
            }
        ))

        // Dungeon DT Title
        val dunScale = DungeonDtTitle.getScale()
        val dunPlain = DungeonDtTitle.PREVIEW_TEXT.replace(COLOR_CODE_REGEX, "")
        val dunW = tr.width(dunPlain)
        val dunX = if (FamilyConfigManager.config.dungeons.dungeonDtTitleHudX == -1)
            ((sw - dunW * dunScale) / 2f).toInt()
        else FamilyConfigManager.config.dungeons.dungeonDtTitleHudX
        val dunY = if (FamilyConfigManager.config.dungeons.dungeonDtTitleHudY == -1)
            (sh / 2f - 40f).toInt()
        else FamilyConfigManager.config.dungeons.dungeonDtTitleHudY

        elements.add(HudElement(
            id = "dungeonDtTitle", label = "Dungeon DT Title",
            x = dunX, y = dunY,
            w = dunW, h = 10,
            scale = dunScale,
            canScale = true,
            onSave = { elem ->
                FamilyConfigManager.config.dungeons.dungeonDtTitleHudX = elem.x
                FamilyConfigManager.config.dungeons.dungeonDtTitleHudY = elem.y
                FamilyConfigManager.config.dungeons.dungeonDtTitleScale = "%.1f".format(elem.scale)
            },
            renderContent = { ctx, _ ->
                ctx.text(tr, DungeonDtTitle.PREVIEW_TEXT, 0, 0, 0xFFFFFFFF.toInt(), true)
            }
        ))

        // Bestiary HUD
        val bCfg = FamilyConfigManager.config.highlight
        val mobName = if (bCfg.mobName.isNotBlank()) bCfg.mobName else "Zombie"
        elements.add(HudElement(
            id = "bestiary", label = "Bestiary HUD",
            x = bCfg.bestiaryHudX, y = bCfg.bestiaryHudY,
            w = BestiaryTracker.HUD_W, h = BestiaryTracker.hudH(),
            scale = bCfg.bestiaryHudScale,
            canScale = true,
            onSave = { elem ->
                FamilyConfigManager.config.highlight.bestiaryHudX = elem.x
                FamilyConfigManager.config.highlight.bestiaryHudY = elem.y
                FamilyConfigManager.config.highlight.bestiaryHudScale = elem.scale
            },
            renderContent = { ctx, _ ->
                val isSession = FamilyConfigManager.config.highlight.displayMode == 1
                var ry = 3
                ctx.text(tr, "§6§l$mobName Bestiary", 4, ry, -1, true); ry += 12
                ctx.text(tr, "§eKills: §f${"%,d".format(BestiaryTracker.kills)}", 4, ry, -1, true); ry += 10
                ctx.text(tr, "§eBestiary Kills: §f${BestiaryTracker.bestiaryProgress}", 4, ry, -1, true); ry += 10
                if (isSession) ctx.text(tr, "§eUptime: §f0m 0s", 4, ry, -1, true)
            }
        ))

        // Gorilla Tactics Timer
        val gtScale = GorillaTactics.getScale()
        val gtPlain = GorillaTactics.PREVIEW_TEXT.replace(COLOR_CODE_REGEX, "")
        val gtW = tr.width(gtPlain)
        val gtX = if (FamilyConfigManager.config.kuudra.gorillaHudX == -1)
            ((sw - gtW * gtScale) / 2f).toInt()
        else FamilyConfigManager.config.kuudra.gorillaHudX
        val gtY = if (FamilyConfigManager.config.kuudra.gorillaHudY == -1)
            (sh / 2f + 40f).toInt()
        else FamilyConfigManager.config.kuudra.gorillaHudY

        elements.add(HudElement(
            id = "gorillaTactics", label = "Gorilla Tactics Timer",
            x = gtX, y = gtY,
            w = gtW + 4, h = 10,
            scale = gtScale,
            canScale = true,
            onSave = { elem ->
                FamilyConfigManager.config.kuudra.gorillaHudX = elem.x
                FamilyConfigManager.config.kuudra.gorillaHudY = elem.y
                FamilyConfigManager.config.kuudra.gorillaHudScale = "%.1f".format(elem.scale)
            },
            renderContent = { ctx, _ ->
                ctx.text(tr, Component.literal(GorillaTactics.PREVIEW_TEXT), 0, 0, 0xFFFFFFFF.toInt(), true)
            }
        ))

        // Kuudra Direction
        val dirScale = KuudraDirection.getScale()
        val dirW = tr.width(KuudraDirection.PREVIEW_TEXT)
        val dirX = if (FamilyConfigManager.config.kuudra.directionHudX == -1)
            ((sw - dirW * dirScale) / 2f).toInt()
        else FamilyConfigManager.config.kuudra.directionHudX
        val dirY = if (FamilyConfigManager.config.kuudra.directionHudY == -1)
            (sh * 0.41f).toInt()
        else FamilyConfigManager.config.kuudra.directionHudY

        elements.add(HudElement(
            id = "kuudraDirection", label = "Kuudra Direction",
            x = dirX, y = dirY,
            w = dirW + 2, h = 10,
            scale = dirScale,
            canScale = true,
            onSave = { elem ->
                FamilyConfigManager.config.kuudra.directionHudX = elem.x
                FamilyConfigManager.config.kuudra.directionHudY = elem.y
                FamilyConfigManager.config.kuudra.directionScale = "%.1f".format(elem.scale)
            },
            renderContent = { ctx, _ ->
                ctx.text(tr, Component.literal(KuudraDirection.PREVIEW_TEXT), 0, 0, 0xFF00AA00.toInt(), true)
            }
        ))

        // Pickobulus Timer
        val pbScale = PickaxeAbility.getScale()
        val pbPlain = PickaxeAbility.PREVIEW_TEXT.replace(COLOR_CODE_REGEX, "")
        val pbW = tr.width(pbPlain)
        val pbX = if (FamilyConfigManager.config.mining.pickobulusHudX == -1)
            ((sw - pbW * pbScale) / 2f).toInt()
        else FamilyConfigManager.config.mining.pickobulusHudX
        val pbY = if (FamilyConfigManager.config.mining.pickobulusHudY == -1)
            (sh / 2f + 40f).toInt()
        else FamilyConfigManager.config.mining.pickobulusHudY

        elements.add(HudElement(
            id = "pickobulusTimer", label = "Pickobulus Timer",
            x = pbX, y = pbY,
            w = pbW + 4, h = 10,
            scale = pbScale,
            canScale = true,
            onSave = { elem ->
                FamilyConfigManager.config.mining.pickobulusHudX = elem.x
                FamilyConfigManager.config.mining.pickobulusHudY = elem.y
                FamilyConfigManager.config.mining.pickobulusHudScale = "%.1f".format(elem.scale)
            },
            renderContent = { ctx, _ ->
                ctx.text(tr, Component.literal(PickaxeAbility.PREVIEW_TEXT), 0, 0, 0xFFFFFFFF.toInt(), true)
            }
        ))
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0x88000000.toInt())
        val tr = Minecraft.getInstance().font
        val hint = "§7Drag to move  |  §eScroll §7to scale  |  §eEsc §7to save"
        val hintW = tr.width(hint.replace(COLOR_CODE_REGEX, ""))
        context.text(tr, hint, (width - hintW) / 2, 8, -1, true)

        for (elem in elements) {
            if (elem.dragging) {
                elem.x = (mouseX - elem.dragOffX).toInt()
                elem.y = (mouseY - elem.dragOffY).toInt()
            }
            val sw = elem.screenW; val sh = elem.screenH
            val isActive = elem == activeElement
            val matrices = context.pose()
            matrices.pushMatrix()
            matrices.translate(elem.x.toFloat(), elem.y.toFloat())
            matrices.scale(elem.scale, elem.scale)
            elem.renderContent(context, elem)
            matrices.popMatrix()

            val border = if (isActive) 0xFFFFFF00.toInt() else 0xFFFFFFFF.toInt()
            context.fill(elem.x - 1, elem.y - 1, elem.x + sw + 1, elem.y, border)
            context.fill(elem.x - 1, elem.y + sh, elem.x + sw + 1, elem.y + sh + 1, border)
            context.fill(elem.x - 1, elem.y, elem.x, elem.y + sh, border)
            context.fill(elem.x + sw, elem.y, elem.x + sw + 1, elem.y + sh, border)
            if (elem.canScale) {
                context.text(tr, "§7${"%.1f".format(elem.scale)}x", elem.x, elem.y + sh + 3, -1, true)
            }
        }
        super.extractRenderState(context, mouseX, mouseY, delta)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val mx = mouseX.toInt(); val my = mouseY.toInt()
        val elem = elements.lastOrNull { e ->
            mx >= e.x && mx <= e.x + e.screenW && my >= e.y && my <= e.y + e.screenH
        } ?: activeElement ?: return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        if (!elem.canScale) return true
        val delta = if (verticalAmount > 0) 0.1f else -0.1f
        elem.scale = "%.1f".format((elem.scale + delta).coerceIn(0.5f, 5f)).toFloat()
        return true
    }

    fun onMousePress(mouseX: Double, mouseY: Double) {
        val mx = mouseX.toInt(); val my = mouseY.toInt()
        for (elem in elements.reversed()) {
            if (mx >= elem.x && mx <= elem.x + elem.screenW &&
                my >= elem.y && my <= elem.y + elem.screenH) {
                elem.dragging = true
                elem.dragOffX = mouseX - elem.x
                elem.dragOffY = mouseY - elem.y
                activeElement = elem
                return
            }
        }
        activeElement = null
    }

    fun onMouseRelease() { elements.forEach { it.dragging = false } }

    override fun onClose() {
        elements.forEach { it.onSave(it) }
        FamilyConfigManager.save()
        super.onClose()
    }

    override fun isPauseScreen() = false
}