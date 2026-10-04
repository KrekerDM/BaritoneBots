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

include("common", "bot-mod", "manager", "companion-plugin")
