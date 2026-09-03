package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.*

/**
 * All Kuudra features in one category. Merged from the old
 * "Solo Kuudra" (Gorilla Tactics, Pearl Timer) and
 * "Kuudra Crate & Pearl" (Crate Hitbox, Pearl Waypoints) categories.
 * Every feature lives in its own labeled accordion.
 */
class KuudraConfig {

    // ── Auto Requeue accordion (id=7) ─────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Auto Requeue", desc = "")
    @ConfigEditorAccordion(id = 7)
    var autoRequeueAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 7)
    @ConfigOption(name = "Auto Requeue", desc = "Auto requeue for Kuudra after each run.")
    @ConfigEditorBoolean
    var autoRequeue = false

    @Expose @JvmField
    @ConfigAccordionId(id = 7)
    @ConfigOption(name = "Requeue Basic", desc = "Auto requeue for Basic tier")
    @ConfigEditorBoolean
    var requeueBasic = false

    @Expose @JvmField
    @ConfigAccordionId(id = 7)
    @ConfigOption(name = "Requeue Hot", desc = "Auto requeue for Hot tier")
    @ConfigEditorBoolean
    var requeueHot = false

    @Expose @JvmField
    @ConfigAccordionId(id = 7)
    @ConfigOption(name = "Requeue Burning", desc = "Auto requeue for Burning tier")
    @ConfigEditorBoolean
    var requeueBurning = false

    @Expose @JvmField
    @ConfigAccordionId(id = 7)
    @ConfigOption(name = "Requeue Fiery", desc = "Auto requeue for Fiery tier")
    @ConfigEditorBoolean
    var requeueFiery = false

    @Expose @JvmField
    @ConfigAccordionId(id = 7)
    @ConfigOption(name = "Requeue Infernal", desc = "Auto requeue for Infernal tier")
    @ConfigEditorBoolean
    var requeueInfernal = false

    @Expose @JvmField
    @ConfigAccordionId(id = 7)
    @ConfigOption(name = "Check Party Size", desc = "Cancel requeue if party has fewer than 4 players.")
    @ConfigEditorBoolean
    var checkPartySize = false

    @Expose @JvmField
    @ConfigAccordionId(id = 7)
    @ConfigOption(name = "Requeue Delay", desc = "Seconds to wait before requeuing after Kuudra ends.")
    @ConfigEditorSlider(minValue = 0f, maxValue = 10f, minStep = 1f)
    var requeueDelaySecs = 0f

    // ── DT Title accordion (id=1) ─────────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "DT Title", desc = "")
    @ConfigEditorAccordion(id = 1)
    var dtTitleAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Enable DT Title", desc = "Show a fading centered title when someone requests DT in party chat.")
    @ConfigEditorBoolean
    var dtTitle = false

    // -1 = auto-center
    @Expose @JvmField var dtTitleHudX = -1
    @Expose @JvmField var dtTitleHudY = -1
    @Expose @JvmField var dtTitleScale = "2.0"

    // ── Key Tracker accordion (id=2) ──────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Key Tracker", desc = "")
    @ConfigEditorAccordion(id = 2)
    var keyTrackerAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 2)
    @ConfigOption(name = "Enable Key Tracker", desc = "Show key material counts in Mage/Barbarian shop.")
    @ConfigEditorBoolean
    var keyTracker = false

    @Expose @JvmField var keyTrackerHudX = 10
    @Expose @JvmField var keyTrackerHudY = 10

    // ── Pile Waypoints accordion (id=3) ───────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Pile Waypoints", desc = "")
    @ConfigEditorAccordion(id = 3)
    var pileWaypointsAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 3)
    @ConfigOption(name = "Enable Pile Waypoints", desc = "Show beacon beams over Kuudra supply piles. Hides occupied piles automatically.")
    @ConfigEditorBoolean
    var pileWaypointsEnabled = false

    @Expose @JvmField
    @ConfigAccordionId(id = 3)
    @ConfigOption(name = "Pile Beam Color", desc = "Color of the pile beacon beams.")
    @ConfigEditorColour
    var pileWaypointColor = "0:153:80:255:80"

    // ── Supply Waypoints accordion (id=4) ─────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Supply Waypoints", desc = "")
    @ConfigEditorAccordion(id = 4)
    var supplyWaypointsAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 4)
    @ConfigOption(name = "Enable Supply Waypoints", desc = "Show beacon beams over Kuudra supply crates being carried by giants.")
    @ConfigEditorBoolean
    var supplyWaypointsEnabled = false

    @Expose @JvmField
    @ConfigAccordionId(id = 4)
    @ConfigOption(name = "Supply Beam Color", desc = "Color of the supply crate beacon beams.")
    @ConfigEditorColour
    var supplyWaypointColor = "0:153:255:200:80"

    // ── Direction accordion (id=5) ────────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Direction", desc = "")
    @ConfigEditorAccordion(id = 5)
    var directionAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 5)
    @ConfigOption(name = "Enable Direction", desc = "Show which way Kuudra peeks during phase 4: RIGHT / FRONT / LEFT / BACK.")
    @ConfigEditorBoolean
    var directionEnabled = false

    // -1 = auto-center
    @Expose @JvmField var directionHudX = -1
    @Expose @JvmField var directionHudY = -1
    @Expose @JvmField var directionScale = "2.0"

    // ── Stun Waypoint accordion (id=6) ────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Stun Waypoint", desc = "")
    @ConfigEditorAccordion(id = 6)
    var stunWaypointAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 6)
    @ConfigOption(name = "Enable Stun Waypoint", desc = "Show a wireframe box on the chosen stun pod after buying Human Cannonball. Offset-relative until you enter the belly.")
    @ConfigEditorBoolean
    var stunWaypointEnabled = false

    @Expose @JvmField
    @ConfigAccordionId(id = 6)
    @ConfigOption(name = "Pick A Pod", desc = "Which pod to mark for the stun.")
    @ConfigEditorDropdown(values = ["Close Left", "Close Right", "Far Middle"])
    var stunPod = 0

    @Expose @JvmField
    @ConfigAccordionId(id = 6)
    @ConfigOption(name = "Waypoint Color", desc = "Color of the stun pod wireframe box.")
    @ConfigEditorColour
    var stunWaypointColor = "0:255:85:255:255"

    // ── Gorilla Tactics Timer accordion (id=50) ───────────────
    @Expose @JvmField
    @ConfigOption(name = "Gorilla Tactics Timer", desc = "")
    @ConfigEditorAccordion(id = 50)
    var gorillaAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 50)
    @ConfigOption(name = "Enable", desc = "Show a 3-second countdown when the Tactical Insertion ability is used.")
    @ConfigEditorBoolean
    var gorillaTacticsTimer = false

    @Expose @JvmField
    @ConfigAccordionId(id = 50)
    @ConfigOption(name = "Display Unit", desc = "Seconds: 3.00s → 0.00s. Ticks: 60t → 0t (1 tick = 0.05s).")
    @ConfigEditorDropdown(values = ["Seconds", "Ticks"])
    var gorillaDisplayUnit = 0

    @Expose @JvmField var gorillaHudX = -1
    @Expose @JvmField var gorillaHudY = -1
    @Expose @JvmField var gorillaHudScale = "1.5"

    // ── Pearl Timer accordion (id=51) ─────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Pearl Timer", desc = "")
    @ConfigEditorAccordion(id = 51)
    var pearlAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 51)
    @ConfigOption(name = "Enable", desc = "Show a countdown for each thrown ender pearl until it lands. Supports multiple pearls in flight.")
    @ConfigEditorBoolean
    var pearlTimer = false

    @Expose @JvmField
    @ConfigAccordionId(id = 51)
    @ConfigOption(name = "Display Unit", desc = "Seconds: 1.20s → 0.00s. Ticks: 24t → 0t (1 tick = 0.05s).")
    @ConfigEditorDropdown(values = ["Seconds", "Ticks"])
    var pearlDisplayUnit = 0

    @Expose @JvmField var pearlTimerHudX = -1
    @Expose @JvmField var pearlTimerHudY = -1
    @Expose @JvmField var pearlTimerHudScale = "1.0"

    // ── Crate Hitbox accordion (id=10) ────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Crate Hitbox", desc = "")
    @ConfigEditorAccordion(id = 10)
    var crateHitboxAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "Enable Crate Hitbox", desc = "Highlight Kuudra supply crates and the drag radius.")
    @ConfigEditorBoolean
    var crateWaypointsEnabled = false

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "Show Crate Hitbox", desc = "Draw a wireframe outline around the crate's interaction zombie.")
    @ConfigEditorBoolean
    var showCrateHitbox = true

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "Crate Hitbox Color", desc = "Wireframe color for the crate hitbox.")
    @ConfigEditorColour
    var crateHitboxColor = "0:255:255:255:0"

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "Crate Reach Color Change", desc = "Switch crate hitbox color when you're within reach distance.")
    @ConfigEditorBoolean
    var crateHitboxReachColorChange = true

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "Crate In-Reach Color", desc = "Color when crate is in reach.")
    @ConfigEditorColour
    var crateHitboxInReachColor = "0:255:0:255:0"

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "Show Drag Hitbox", desc = "Draw a circle on the ground showing the drag radius.")
    @ConfigEditorBoolean
    var showDragHitbox = true

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "Drag Hitbox Color", desc = "Color for the drag radius circle.")
    @ConfigEditorColour
    var dragHitboxColor = "0:255:255:150:0"

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "Drag In-Range Color Change", desc = "Switch drag color when your fishing bobber is in range.")
    @ConfigEditorBoolean
    var dragHitboxInRangeColorChange = true

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "Drag In-Range Color", desc = "Color when fishing bobber is in drag range.")
    @ConfigEditorColour
    var dragHitboxInRangeColor = "0:255:0:255:0"

    // ── Pearl Waypoints accordion (id=11) ─────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Pearl Waypoints", desc = "")
    @ConfigEditorAccordion(id = 11)
    var pearlWaypointsAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Enable Pearl Waypoints", desc = "Show dynamic pearl-throw aim points for supplies during Kuudra.")
    @ConfigEditorBoolean
    var pearlWaypointsEnabled = false

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Talisman Tier", desc = "Pearl Talisman tier — affects how long you can carry a chest before it slips.")
    @ConfigEditorDropdown(values = ["No Tali", "T1", "T2", "T3"])
    var pearlTalismanTier = 3

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Waypoint Shape", desc = "Shape of the main aim-point waypoint.")
    @ConfigEditorDropdown(values = ["AABB", "AABB Outline", "Square", "Circle"])
    var pearlShape = 1

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Waypoint Color", desc = "Color of the main aim-point waypoint.")
    @ConfigEditorColour
    var pearlColor = "0:255:80:200:255"

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Waypoint Size", desc = "Size of the main aim-point waypoint.")
    @ConfigEditorSlider(minValue = 0.1f, maxValue = 3.0f, minStep = 0.05f)
    var pearlSize = 0.1f

    // ── Timer ────────────────────────────────
    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Show Timer", desc = "Show pearl flight time near the waypoint.")
    @ConfigEditorBoolean
    var pearlWaypointTimer = true

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Timer Delay", desc = "Extra delay (ms) added to the displayed timer to compensate for input lag.")
    @ConfigEditorSlider(minValue = 0f, maxValue = 500f, minStep = 10f)
    var pearlTimerDelay = 0f

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Timer Scale", desc = "Component scale of the flight-time label.")
    @ConfigEditorSlider(minValue = 0.5f, maxValue = 4.0f, minStep = 0.1f)
    var pearlTimerScale = 3.0f

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Timer Position", desc = "Position of the timer relative to the waypoint.")
    @ConfigEditorDropdown(values = ["Above", "Below", "Center"])
    var pearlTimerPos = 0

    // ── NOW sound ─────────────────────────────
    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Now Sound", desc = "Play a ping the moment the throw window opens during a chest grab.")
    @ConfigEditorBoolean
    var pearlNowSound = true

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Now Sound Volume", desc = "Volume of the throw-window ping. 0 = silent.")
    @ConfigEditorSlider(minValue = 0f, maxValue = 2.0f, minStep = 0.1f)
    var pearlNowSoundVolume = 1.0f

    // ── Double Pearls ────────────────────────────────
    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Double Pearls", desc = "Render a secondary aim point for chained pearls (when one is mid-air).")
    @ConfigEditorBoolean
    var pearlDPearls = false

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Hide on Missing", desc = "Hide a double-pearl waypoint when its supply has been called as missing.")
    @ConfigEditorBoolean
    var pearlHideOnMissing = true

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Double Pearl Size", desc = "Size of the double-pearl aim point.")
    @ConfigEditorSlider(minValue = 0.1f, maxValue = 3.0f, minStep = 0.05f)
    var pearlDPearlSize = 0.1f

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Double Pearl Timer", desc = "Show flight time for double-pearl waypoints.")
    @ConfigEditorBoolean
    var pearlDPearlTimer = true

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Double Pearl Land Delay", desc = "Subtract this many ms from the displayed time so it shows time-until-pearl-can-be-thrown.")
    @ConfigEditorSlider(minValue = 0f, maxValue = 1000f, minStep = 10f)
    var pearlDPearlLandDelay = 0f

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Double Pearl Timer Size", desc = "Component scale for the double-pearl timer.")
    @ConfigEditorSlider(minValue = 0.5f, maxValue = 4.0f, minStep = 0.1f)
    var pearlDPearlTimerSize = 0.8f

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Double Pearl Color", desc = "Color of the double-pearl aim point.")
    @ConfigEditorColour
    var pearlDPearlColor = "0:255:255:200:80"

    // ── Sky markers ────────────────────────────────
    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Sky Markers", desc = "Render high-arc sky waypoints for distant supplies.")
    @ConfigEditorBoolean
    var pearlSkyPearls = false

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Sky Marker Size", desc = "Size of the sky marker.")
    @ConfigEditorSlider(minValue = 0.1f, maxValue = 5.0f, minStep = 0.1f)
    var pearlSkySize = 1.0f

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Sky Marker Color", desc = "Color of sky markers.")
    @ConfigEditorColour
    var pearlSkyColor = "0:200:255:80:255"

    // ── Per-spot Y offsets ────────────────────────────────
    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Per-Spot Y Offsets", desc = "Enable per-spot vertical offsets to fine-tune aim height.")
    @ConfigEditorBoolean
    var pearlOffsetsEnabled = false

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Shop Offset", desc = "Y offset for Shop waypoint.")
    @ConfigEditorSlider(minValue = -2f, maxValue = 2f, minStep = 0.05f)
    var pearlShopOff = 0f

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "X Offset", desc = "Y offset for X waypoint.")
    @ConfigEditorSlider(minValue = -2f, maxValue = 2f, minStep = 0.05f)
    var pearlXOff = 0f

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "X-Cannon Offset", desc = "Y offset for X-Cannon waypoint.")
    @ConfigEditorSlider(minValue = -2f, maxValue = 2f, minStep = 0.05f)
    var pearlXCannonOff = 0f

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Equals Offset", desc = "Y offset for Equals waypoint.")
    @ConfigEditorSlider(minValue = -2f, maxValue = 2f, minStep = 0.05f)
    var pearlEqualsOff = 0f

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Slash Offset", desc = "Y offset for Slash waypoint.")
    @ConfigEditorSlider(minValue = -2f, maxValue = 2f, minStep = 0.05f)
    var pearlSlashOff = 0f

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Triangle Offset", desc = "Y offset for Triangle waypoint.")
    @ConfigEditorSlider(minValue = -2f, maxValue = 2f, minStep = 0.05f)
    var pearlTriangleOff = 0f

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Square Offset", desc = "Y offset for Square waypoint.")
    @ConfigEditorSlider(minValue = -2f, maxValue = 2f, minStep = 0.05f)
    var pearlSquareOff = 0f

    @Expose @JvmField
    @ConfigAccordionId(id = 11)
    @ConfigOption(name = "Use New Priority", desc = "Use the alternate routing where Shop and Triangle swap targets.")
    @ConfigEditorBoolean
    var pearlNewPrio = false
}
