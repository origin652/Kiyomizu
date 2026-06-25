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
// Use the git short SHA so the URL changes whenever the working tree is committed; falls
// back to the project version when not in a git repo. This avoids serving stale CSS/JS
// from browser/CDN caches after a deploy — a fixed version string would let old assets
// persist indefinitely.
val uiAssetVersion: String = try {
    val sha = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()
    if (sha.isNotEmpty()) sha else project.version.toString()
} catch (e: Exception) {
    project.version.toString()
}
tasks.processResources {
    // Track the SHA as an up-to-date input so a new commit re-runs the filter even when
    // ui.html itself is unchanged (the @UI_ASSET_VERSION@ placeholder is constant).
    inputs.property("uiAssetVersion", uiAssetVersion)
    filesMatching("ui.html") {
        filter(
            org.apache.tools.ant.filters.ReplaceTokens::class,
            "tokens" to mapOf("UI_ASSET_VERSION" to uiAssetVersion),
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
