plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

version = "0.8.1-poc"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-cors:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    
    // Ktor Client
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    
    // Kotlinx Serialization JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.6")
    
    // SQLite JDBC
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    
    // Test
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("hifumi.kiyomizu.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// ui.html uses @UI_ASSET_VERSION@ on static URLs so CDN/browser caches bust on release.
tasks.processResources {
    filesMatching("ui.html") {
        filter(
            org.apache.tools.ant.filters.ReplaceTokens::class,
            "tokens" to mapOf("UI_ASSET_VERSION" to project.version.toString()),
        )
    }
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles a self-contained executable jar."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })
}
