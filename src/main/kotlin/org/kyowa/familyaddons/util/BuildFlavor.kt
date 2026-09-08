package org.kyowa.familyaddons.util

import org.kyowa.familyaddons.FamilyAddons

/**
 * Which of the two jars this is. Every build produces both:
 *  - FamilyAddons-<mc>.jar      public: the Dev config category is not shown
 *  - FamilyAddons-<mc>-dev.jar  dev: identical classes plus [MARKER], which unlocks it
 *
 * The code is the same in both; only the marker resource differs, so the dev
 * flag is decided once from the classpath. The auto updater re-stamps a
 * downloaded release with the marker when it runs inside a dev build, so a
 * dev install stays a dev install across updates.
 */
object BuildFlavor {

    const val MARKER = "familyaddons-dev.marker"

    val isDev: Boolean by lazy { FamilyAddons::class.java.classLoader.getResource(MARKER) != null }

    val name: String get() = if (isDev) "dev" else "public"
}
