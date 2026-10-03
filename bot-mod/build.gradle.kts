import java.net.URI

plugins {
    id("net.fabricmc.fabric-loom")
}

base {
    archivesName.set("baritonebots-botmod")
}

// Baritone is not on a public maven. Download the official API jar from the GitHub release once.
val baritoneJar = layout.buildDirectory.file("baritone/baritone-api-fabric-${property("baritone_version")}.jar")
// Read outside the task block: inside it, property() resolves against the task, not the project.
val baritoneApiUrl = property("baritone_api_url") as String
val downloadBaritone = tasks.register("downloadBaritone") {
    val url = baritoneApiUrl
    val target = baritoneJar
    outputs.file(target)
    onlyIf { !target.get().asFile.exists() }
    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
        URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    compileOnly(files(baritoneJar).builtBy(downloadBaritone))
    implementation(project(":common"))
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

// Bundle the shared protocol classes into the mod jar (no relocation needed: unique package).
tasks.jar {
    from(project(":common").sourceSets.main.get().output)
    from(rootProject.file("LICENSE")) { rename { "LICENSE_BaritoneBots" } }
}
