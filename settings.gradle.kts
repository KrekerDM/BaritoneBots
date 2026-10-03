pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("net.fabricmc.fabric-loom") version "1.18.2"
    }
}

rootProject.name = "BaritoneBots"

include("common", "bot-mod", "manager")
// The optional companion plugin is half-written (no main class yet) and ships in phase 2:
// include("companion-plugin")
