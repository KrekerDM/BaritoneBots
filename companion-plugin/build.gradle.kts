plugins {
    java
}

base {
    archivesName.set("baritonebots-companion")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paper_api_version")}")
    implementation(project(":common"))

    // Tests cover only the Bukkit-free parts (journal codec, index, windows), so paper-api stays off the test classpath.
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:${property("gson_version")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.jar {
    from(project(":common").sourceSets.main.get().output)
    from(rootProject.file("LICENSE"))
}
