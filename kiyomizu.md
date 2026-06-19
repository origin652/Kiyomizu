# Kiyomizu - Advanced LLM Cache Proxy & Gateway

Kiyomizu is a lightweight, local caching proxy designed to sit between your AI client (e.g., Cherry Studio, LibreChat) and upstream API providers (OpenRouter, Anthropic, OpenAI, DeepSeek, Google, Vercel Gateway, and more). It dynamically modifies requests to optimize prompt-cache utilization—such as injecting Anthropic prompt-caching breakpoints, stripping reasoning/thinking blocks from chat history to stabilize prefixes, and enforcing specific providers.

---

## Features

- **Multi-Preset Support**: Quickly switch upstream configurations for OpenRouter, Anthropic, OpenAI, DeepSeek, Vercel Gateway, or custom endpoints.
- **Anthropic Prompt Caching**: Inject up to 4 explicit content-block cache breakpoints evenly spaced across your stable message history.
- **Gemini Cache Support**: Automatically applies cache control to Gemini requests (targeting AI Studio or Google Vertex).
- **History Sanitization**: Strips assistant `thinking` and `reasoning_content` blocks from older chat history. This keeps the prompt prefix stable, maximizing cache hits and saving tokens.
- **Web UI Configurator**: A clean, responsive configuration panel served directly by the proxy (designed with a premium, water-inspired glassmorphic theme).
- **Kotlin/Ktor Core**: Rebuilt as a high-performance JVM-based proxy engine.

---

## Starting the Gateway

### Build and Run with Gradle
Ensure you have JDK 17 or higher installed, then execute:

```sh
./gradlew run
```

### Config Environment Variables
You can customize Kiyomizu's behavior via environment variables at startup:

| Variable | Default | Description |
|---|---|---|
| `HOST` | `127.0.0.1` | Set to `0.0.0.0` to expose Kiyomizu to your Local Area Network (LAN). |
| `PORT` | `8787` | Local port for the proxy server. |
| `STRIP_THINKING` | `1` | Set to `0` to keep reasoning/thinking blocks in history. |
| `MODEL_FILTER` | `anthropic,claude` | Patch models containing these terms (for Claude logic). |
| `GEMINI_MODEL_FILTER` | `google,gemini` | Patch models containing these terms (for Gemini logic). |
| `SEND_TOP_LEVEL_CACHE_CONTROL` | `0` | Set to `1` to include top-level cache_control. |
| `DYNAMIC_TAIL_MESSAGES` | `1` | Number of messages at the tail of history excluded from the cache window. |

---

## Configuration Web UI

Once Kiyomizu is running, open your browser and navigate to:
```
http://127.0.0.1:8787/
```
From this panel, you can:
1. Select an **Upstream Preset** (e.g. OpenRouter).
2. Override the **Upstream URL**.
3. Toggle between **Claude** and **Gemini** providers.
4. Customize caching parameters (TTL, Breakpoints count, Strategy).
5. Instantly save settings without restarting the server.

---

## Integrating with Clients (e.g., Cherry Studio)

### For OpenRouter Presets
Add a custom OpenAI-compatible provider in Cherry Studio with these settings:

- **API Base URL**: `http://127.0.0.1:8787/v1` (or `http://127.0.0.1:8787/api/v1`)
- **API Key**: *Your OpenRouter API Key*
- **Model ID**: `anthropic/claude-3.7-sonnet` (or any other desired model)

### For Anthropic Direct Presets
- **API Base URL**: `http://127.0.0.1:8787/v1`
- **API Key**: *Your Anthropic API Key*

---

## Caching Strategy Details

- **Stable-Prefix**: Places cache breakpoints evenly distributed across the stable part of the history (all messages excluding the last `DYNAMIC_TAIL_MESSAGES`). This ensures that context remains cached even as new messages are added.
- **Last**: Places a single cache breakpoint on the very last stable message.
- **Thinking Blocks Removal**: Assistant outputs that include `<thinking>` tags or `reasoning_content` blocks are stripped from older messages in the request payload. Keeping reasoning out of history maintains a stable cache signature.

---

## Verification & Monitoring

In OpenRouter's activity logs or billing panel, you should see active prompt cache hits:
- `native_tokens_cached > 0`
- `response_cache_source_id = null`

Kiyomizu also logs requests to stdout in a clean, structured JSON format showing:
- `patched`: Whether the request was modified.
- `removed_thinking_blocks`: Count of reasoning blocks stripped.
- `cache_breakpoint_indexes`: The message indexes where cache breakpoints were injected.
- `explicit_cache_blocks`: The total count of cached blocks.
