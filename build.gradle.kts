import java.security.MessageDigest

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
// The version must change whenever the UI assets change, otherwise browsers/CDNs serve
// stale CSS/JS after a deploy.
//
// Preferred source: the git short SHA (changes every commit). But Docker builds COPY only
// `src/`, not `.git/`, so `git rev-parse` fails there and the previous fallback — the fixed
// project version — never changed, busting nothing. So when git is unavailable we instead
// hash the contents of ui.html + every static asset. That hash changes iff the assets
// change, which is exactly when the cache must bust. Works in Docker, in fresh checkouts,
// and anywhere .git is absent.
val uiStaticDir = layout.projectDirectory.dir("src/main/resources/static")
val uiAssetVersion: String = try {
    val sha = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()
    if (sha.isNotEmpty()) sha else contentHashVersion()
} catch (e: Exception) {
    contentHashVersion()
}

fun contentHashVersion(): String {
    val md = MessageDigest.getInstance("SHA-256")
    val files = mutableListOf<File>()
    files += file("src/main/resources/ui.html")
    uiStaticDir.asFileTree.files.forEach { files += it }
    files.filter { it.isFile }.sortedBy { it.path }.forEach { f ->
        md.update(f.path.toByteArray())
        md.update(f.readBytes())
    }
    val hex = StringBuilder()
    for (b in md.digest()) {
        hex.append(String.format("%02x", b))
    }
    return "h" + hex.take(12)
}

tasks.processResources {
    // Track the SHA as an up-to-date input so a new commit re-runs the filter even when
    // ui.html itself is unchanged (the @UI_ASSET_VERSION@ placeholder is constant). In the
    // content-hash fallback path the static asset files are inputs, so editing any of them
    // also re-runs the filter.
    inputs.property("uiAssetVersion", uiAssetVersion)
    if (!uiAssetVersion.startsWith("h")) {
        // git-SHA path: only ui.html carries the placeholder; assets are untracked inputs
        // above via the uiAssetVersion property.
    } else {
        // content-hash path: make the hashed files explicit inputs.
        inputs.files(file("src/main/resources/ui.html"), uiStaticDir.asFileTree)
    }
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
