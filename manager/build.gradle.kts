plugins {
    application
}

base {
    archivesName.set("baritonebots-manager")
}

dependencies {
    implementation(project(":common"))
    implementation("com.google.code.gson:gson:${property("gson_version")}")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.github.krekerdm.baritonebots.manager.ManagerMain")
}

// The manager installs the bot mod into every bot instance, so it carries the built mod jar inside.
val botModJar = project(":bot-mod").tasks.named<Jar>("jar")
tasks.processResources {
    dependsOn(botModJar)
    from(botModJar.flatMap { it.archiveFile }) {
        into("botmod")
        rename { "baritonebots-botmod.jar" }
    }
}

// Single runnable jar: java -jar baritonebots-manager-<version>.jar
tasks.jar {
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Title" to "BaritoneBots manager",
            "Implementation-Version" to project.version.toString(),
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
    }
    from(rootProject.file("LICENSE"))
}
