# CLAUDE.md

This file provides guidance to coding assistants (like Claude Code, Antigravity) when working with code in this repository.

## Project Name: Kiyomizu
Kiyomizu is a Gradle-managed Kotlin Ktor proxy designed to intercept and optimize LLM API calls (inject prompt caching, strip historical thinking blocks, enforce providers, etc.) with a web configuration UI.

## Build and Run Commands

### Run the Server
Starts the proxy on the host/port configured (default http://127.0.0.1:8787):
```sh
./gradlew run
```

### Run Tests
```sh
./gradlew test
```

### Build Project
Compiles and packages the application:
```sh
./gradlew build
```

---

## Directory & Package Structure

The package is `hifumi.kiyomizu`.

- **Main entry point**: [Main.kt](file:///Users/hifumimizuhara/Desktop/Benkyou/src/main/kotlin/hifumi/kiyomizu/Main.kt)
  - Starts Ktor Netty server.
  - Configures CORS for local/private network clients.
  - Dispatches endpoints `/`, `/ui`, `/health`, `/api/config` (GET/POST), `/v1/models`, and captures wildcard paths `{...}` for downstream proxying.
- **Configuration holder**: [Config.kt](file:///Users/hifumimizuhara/Desktop/Benkyou/src/main/kotlin/hifumi/kiyomizu/Config.kt)
  - Manages atomic configuration properties (upstream, provider, cache TTL, strategy, presets).
- **Request transformer**: [MessagePatcher.kt](file:///Users/hifumimizuhara/Desktop/Benkyou/src/main/kotlin/hifumi/kiyomizu/MessagePatcher.kt)
  - Normalizes payloads, strips `thinking` and `reasoning_content` blocks from history, and injects `cache_control` blocks based on the strategy.
- **Proxy and networking**: [ProxyService.kt](file:///Users/hifumimizuhara/Desktop/Benkyou/src/main/kotlin/hifumi/kiyomizu/ProxyService.kt)
  - Upstream request execution using Ktor Client (CIO), header purification, and structured JSON stdout logging.
- **Web UI Resource**: [ui.html](file:///Users/hifumimizuhara/Desktop/Benkyou/src/main/resources/ui.html)
  - The single-page premium glassmorphic settings panel with localization support.

---

## Code Guidelines & Standards

- **Kotlin Conventions**: Use idiomatic Kotlin 2.0. Maintain code cleanliness and use structured concurrency for asynchronous tasks.
- **Serialization**: Use `kotlinx.serialization.json` for JSON parsing and construction. Construct JsonObjects using the type-safe DSL `buildJsonObject { ... }`.
- **Ktor Networking**: Handle response stream pipe-lining carefully using Ktor's `copyTo(call.respondBytesWriter { ... })` to support SSE/streaming without buffering.
- **Error Handling**: Log failures to stderr or wrap exceptions gracefully in JSON responses to keep the proxy client connection alive.
