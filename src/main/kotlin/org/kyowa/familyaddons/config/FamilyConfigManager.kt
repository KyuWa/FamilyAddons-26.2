package org.kyowa.familyaddons.config

import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.notenoughupdates.moulconfig.common.IMinecraft
import io.github.notenoughupdates.moulconfig.gui.GuiElement
import io.github.notenoughupdates.moulconfig.gui.MoulConfigEditor
import io.github.notenoughupdates.moulconfig.processor.BuiltinMoulConfigGuis
import io.github.notenoughupdates.moulconfig.processor.ConfigProcessorDriver
import io.github.notenoughupdates.moulconfig.processor.MoulConfigProcessor
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object FamilyConfigManager {

    /** Button fields (Runnable) are UI only: never written, and ignored if an old file has them. */
    private val skipRunnables = object : ExclusionStrategy {
        override fun shouldSkipField(f: FieldAttributes): Boolean = Runnable::class.java.isAssignableFrom(f.declaredClass)
        override fun shouldSkipClass(clazz: Class<*>): Boolean = Runnable::class.java.isAssignableFrom(clazz)
    }

    private val gson = GsonBuilder()
        .excludeFieldsWithoutExposeAnnotation()
        .setExclusionStrategies(skipRunnables)
        .setPrettyPrinting()
        .create()

    private val configFile get() = File(net.minecraft.client.Minecraft.getInstance().gameDirectory, "config/familyaddons/config.json")

    private var _config: FamilyConfig = FamilyConfig()
    val config: FamilyConfig get() = _config

    private lateinit var processor: MoulConfigProcessor<FamilyConfig>
    private lateinit var driver: ConfigProcessorDriver
    private lateinit var editor: MoulConfigEditor<FamilyConfig>
    private var editorInitialized = false

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    fun load() {
        configFile.parentFile.mkdirs()
        loadConfig()

        processor = MoulConfigProcessor(_config)
        BuiltinMoulConfigGuis.addProcessors(processor)
        driver = ConfigProcessorDriver(processor)
        driver.processConfig(_config)
        // Public build: the Dev category stays in the code and the json, but is
        // dropped from the editor before it is ever built. Dev jar keeps it.
        if (!org.kyowa.familyaddons.util.BuildFlavor.isDev) {
            // MoulConfig keys categories by Field.toString() ("public ...DevConfig ...FamilyConfig.dev"),
            // so match on the declaring field, not on a bare "dev".
            val removed = processor.allCategories.entries.removeIf { (key, cat) ->
                key.substringAfterLast('.') == "dev" ||
                    (cat as? io.github.notenoughupdates.moulconfig.processor.ProcessedCategoryImpl)?.reflectField?.name == "dev"
            }
            org.kyowa.familyaddons.FamilyAddons.LOGGER.info("Config: public build, Dev category ${if (removed) "hidden" else "NOT FOUND"}")
        }

        scheduler.scheduleAtFixedRate({ save() }, 60, 60, TimeUnit.SECONDS)
    }

    private fun loadConfig() {
        if (!configFile.exists()) {
            _config = FamilyConfig()
            save()
            return
        }
        val root: JsonElement = try {
            FileReader(configFile).use { fr -> JsonParser.parseReader(fr) }
        } catch (e: Exception) {
            // Unreadable file: keep it for the user, start from defaults.
            org.kyowa.familyaddons.FamilyAddons.LOGGER.error("Config: could not parse config.json, backing it up and using defaults", e)
            backupBroken()
            _config = FamilyConfig()
            return
        }
        try {
            migrateLegacyCategories(root)
            _config = gson.fromJson(root, FamilyConfig::class.java) ?: FamilyConfig()
        } catch (e: Exception) {
            // One bad category must not wipe the rest: load category by category and
            // keep every one that reads cleanly. The original file is kept as a backup.
            org.kyowa.familyaddons.FamilyAddons.LOGGER.error("Config: full load failed, loading category by category", e)
            backupBroken()
            _config = loadLenient(root)
        }
    }

    private fun backupBroken() {
        runCatching {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
            configFile.copyTo(File(configFile.parentFile, "config.json.broken-$stamp"), overwrite = true)
        }
    }

    private fun loadLenient(root: JsonElement): FamilyConfig {
        val cfg = FamilyConfig()
        val obj = root as? JsonObject ?: return cfg
        for (f in FamilyConfig::class.java.declaredFields) {
            if (!f.isAnnotationPresent(com.google.gson.annotations.Expose::class.java)) continue
            val el = obj.get(f.name) ?: continue
            try {
                f.isAccessible = true
                gson.fromJson<Any>(el, f.genericType)?.let { f.set(cfg, it) }
            } catch (e: Exception) {
                org.kyowa.familyaddons.FamilyAddons.LOGGER.warn("Config: category '${f.name}' unreadable, using defaults for it: ${e.message}")
            }
        }
        return cfg
    }

    /**
     * Old configs had separate "soloKuudra" and "hidden" categories, and the
     * gorilla timer lived under "utilities". Everything Kuudra now lives in
     * the single "kuudra" category — copy legacy values across on load.
     * hidden.pearlTimer collided with soloKuudra.pearlTimer, so it becomes
     * kuudra.pearlWaypointTimer.
     */
    private fun migrateLegacyCategories(root: JsonElement) {
        val obj = root as? JsonObject ?: return
        val kuudra = obj.getAsJsonObject("kuudra") ?: JsonObject().also { obj.add("kuudra", it) }

        (obj.remove("soloKuudra") as? JsonObject)?.let { legacy ->
            for ((k, v) in legacy.entrySet()) if (!kuudra.has(k)) kuudra.add(k, v)
        }
        (obj.remove("hidden") as? JsonObject)?.let { legacy ->
            for ((k, v) in legacy.entrySet()) {
                val key = if (k == "pearlTimer") "pearlWaypointTimer" else k
                if (!kuudra.has(key)) kuudra.add(key, v)
            }
        }
        (obj.get("utilities") as? JsonObject)?.let { util ->
            for (k in listOf("gorillaTacticsTimer", "gorillaHudX", "gorillaHudY", "gorillaHudScale")) {
                val v = util.remove(k)
                if (v != null && !kuudra.has(k)) kuudra.add(k, v)
            }
        }

        // Translator languages were dropdown indices before becoming text
        // boxes; turn a stored number into the name it meant.
        (obj.get("translator") as? JsonObject)?.let { tr ->
            for (k in listOf("targetLanguage", "outgoingLanguage")) {
                val v = tr.get(k) ?: continue
                if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) tr.addProperty(k, TranslatorConfig.nameOfIndex(v.asInt))
            }
        }

        // "Bestiary" category merged into "Highlight/BE" (the highlight
        // object). Renames avoid clashes with existing highlight keys.
        (obj.remove("bestiary") as? JsonObject)?.let { legacy ->
            val highlight = obj.getAsJsonObject("highlight")
                ?: JsonObject().also { obj.add("highlight", it) }
            val renames = mapOf(
                "enabled" to "bestiaryHudEnabled",
                "hudX" to "bestiaryHudX",
                "hudY" to "bestiaryHudY",
                "hudScale" to "bestiaryHudScale",
            )
            for ((k, v) in legacy.entrySet()) {
                val key = renames[k] ?: k
                if (!highlight.has(key)) highlight.add(key, v)
            }
        }
    }

    fun save() {
        try {
            configFile.parentFile.mkdirs()
            FileWriter(configFile).use { fw -> fw.write(gson.toJson(_config)) }
            // Rescan highlights when config changes
            net.minecraft.client.Minecraft.getInstance().execute {
                org.kyowa.familyaddons.features.EntityHighlight.rescan()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getEditor(): MoulConfigEditor<FamilyConfig> {
        if (!editorInitialized) {
            editor = MoulConfigEditor(processor)
            editorInitialized = true
        }
        return editor
    }

    fun openGui() {
        IMinecraft.getInstance().openWrappedScreen(getEditor())
    }
}
