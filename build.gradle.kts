allprojects {
    group = property("project_group") as String
    version = property("project_version") as String
}

subprojects {
    repositories {
        mavenCentral()
    }

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_25
            targetCompatibility = JavaVersion.VERSION_25
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(25)
            options.encoding = "UTF-8"
            options.compilerArgs.add("-Xlint:deprecation")
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
