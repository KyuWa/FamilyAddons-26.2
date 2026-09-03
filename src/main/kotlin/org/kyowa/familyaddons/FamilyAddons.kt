package org.kyowa.familyaddons

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import org.kyowa.familyaddons.commands.ParkourCommand
import org.kyowa.familyaddons.commands.TestCommand
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.features.*
import org.kyowa.familyaddons.party.PartyTracker
import org.slf4j.LoggerFactory

val COLOR_CODE_REGEX = Regex("§.")

object FamilyAddons : ClientModInitializer {

    val LOGGER = LoggerFactory.getLogger("FamilyAddons")
    const val VERSION = "1.0.0"
    const val MC_VERSION = "26.2"

    private var hudEditorMouseWasDown = false
    private var previousScreen: Screen? = null

    override fun onInitializeClient() {
        LOGGER.info("FamilyAddons $VERSION loading...")

        AutoUpdater.register()

        FamilyConfigManager.load()
        KeyFetcher.fetchIfNeeded()

        TestCommand.register()

        // Chat
        HideMessages.register()

        // Utilities
        CmdShortcut.register()
        SignMath.register()
        ItemPrices.register()
        GfsKeybinds.register()
        GorillaTactics.register()
        ArachneTimer.register()
        CameraHelper.register()

        // Crimson Isle
        MiniBossTimer.register()

        // Solo Kuudra
        PearlTimer.register()

        // Party
        PartyTracker.register()
        PartyCommands.register()
        PartyRepCheck.register()

        // Rendering & World
        WorldRenderDispatcher.register()
        CorpseESP.register()
        Waypoints.register()
        NpcLocations.register()
        Parkour.register()
        ParkourCommand.register()
        EntityHighlight.register()
        ShulkerBoxHighlight.register()
        SparklingCritterHighlight.register()
        PickaxeAbility.register()
        WorldScanner.register()

        // Kuudra + Dungeons
        // KuudraPhase must register first — other Kuudra features read its state.
        KuudraPhase.register()
        DtTitle.register()
        AutoRequeue.register()
        InfernalKeyTracker.register()
        DungeonDtTitle.register()
        DungeonHighlight.register()
        KuudraOccupancy.register()
        PileWaypoints.register()
        SupplyWaypoints.register()
        KuudraGiants.register()
        KuudraCrateWaypoints.register()
        PearlWaypoints.register()
        KuudraDirection.register()
        KuudraStunWaypoint.register()

        // Bestiary
        BestiaryTracker.register()
        BestiaryZoneHighlight.register()

        // Player Disguise sync (multi-player Cloudflare sync)
        SharedDisguiseSync.register()

        // Dev
        DevTools.register()

        // Join event
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            EntityHighlight.rescan()
            BestiaryZoneHighlight.refresh()
            SharedDisguiseSync.fetchAllNow() // Re-fetch disguises on every server join so they load immediately
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (TestCommand.openGuiNextTick) {
                TestCommand.openGuiNextTick = false
                client.gui.setScreen(HudEditorScreen())
            }
            if (TestCommand.openConfigNextTick) {
                TestCommand.openConfigNextTick = false
                FamilyConfigManager.openGui()
            }
            val currentScreen = client.gui.screen()
            if (previousScreen != null && currentScreen == null) FamilyConfigManager.save()
            previousScreen = currentScreen
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val screen = client.gui.screen() as? HudEditorScreen ?: run {
                hudEditorMouseWasDown = false
                return@register
            }
            val mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                client.window.handle(),
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
            ) == org.lwjgl.glfw.GLFW.GLFW_PRESS
            val mx = client.mouseHandler.getScaledXPos(client.window)
            val my = client.mouseHandler.getScaledYPos(client.window)
            if (mouseDown && !hudEditorMouseWasDown) screen.onMousePress(mx, my)
            else if (!mouseDown && hudEditorMouseWasDown) screen.onMouseRelease()
            hudEditorMouseWasDown = mouseDown
        }

        LOGGER.info("FamilyAddons $VERSION loaded!")
    }
}