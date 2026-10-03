plugins {
    `java-library`
}

dependencies {
    // Gson ships with both the Minecraft client and Paper; the manager bundles its own copy.
    compileOnlyApi("com.google.code.gson:gson:${property("gson_version")}")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:${property("gson_version")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
