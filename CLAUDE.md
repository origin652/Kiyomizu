# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single-file local HTTP proxy (`openrouter-cache-proxy.mjs`) that sits between an OpenAI-compatible client (e.g. Cherry Studio) and the OpenRouter API. It rewrites Claude requests to inject Anthropic prompt-cache breakpoints and strips thinking/reasoning blocks from historical messages to keep the prompt prefix stable and cache-friendly.

## Running

```sh
node openrouter-cache-proxy.mjs
```

With all env vars:

```sh
HOST=0.0.0.0 PORT=8787 CACHE_TTL=1h CACHE_MODE=explicit CACHE_STRATEGY=stable-prefix CACHE_BREAKPOINTS=4 DYNAMIC_TAIL_MESSAGES=1 STRIP_THINKING=1 FORCE_PROVIDER=anthropic MODEL_FILTER=anthropic,claude node openrouter-cache-proxy.mjs
```

Self-test (no network, no server):

```sh
node openrouter-cache-proxy.mjs --self-test
```

## Key env vars and defaults

| Variable | Default | Notes |
|---|---|---|
| `HOST` | `127.0.0.1` | Use `0.0.0.0` to expose to LAN |
| `PORT` | `8787` | |
| `CACHE_MODE` | `explicit` | `automatic` disables block-level rewrite |
| `CACHE_STRATEGY` | `stable-prefix` | `last` puts one breakpoint on last message |
| `CACHE_BREAKPOINTS` | `4` | Max 4 (Anthropic limit) |
| `DYNAMIC_TAIL_MESSAGES` | `1` | Messages excluded from cache window |
| `STRIP_THINKING` | `1` | Strip thinking/reasoning blocks from history |
| `SEND_TOP_LEVEL_CACHE_CONTROL` | `0` | Top-level cache_control omitted by default |
| `CACHE_TTL` | `1h` | Requires `extended-cache-ttl-2025-04-11` beta header |
| `MODEL_FILTER` | `anthropic,claude` | Only patch models whose name contains these terms |

## Architecture

The entire proxy is a single Node.js ESM file with no dependencies. Flow:

1. `http.createServer` → `handle()` dispatches requests
2. `OPTIONS` → CORS preflight reply
3. `GET /models` (various aliases) → `handleModelList()` fetches from OpenRouter and normalizes to OpenAI shape
4. All other requests → JSON body is parsed, `patchJsonBody()` rewrites it, forwarded to OpenRouter via `fetch`

### Message patching pipeline (`patchMessages`)

1. **Normalize**: `normalizeMessage()` strips thinking blocks and any existing `cache_control` from all messages
2. **Select breakpoints**: `chooseCacheBreakpointIndexes()` picks up to `CACHE_BREAKPOINTS` evenly-spaced indexes across the stable prefix (all messages except the last `DYNAMIC_TAIL_MESSAGES`)
3. **Inject**: `patchMessageWithCacheControl()` adds `cache_control: {type: "ephemeral", ttl}` to the last text block in each selected message

Non-Claude models (filtered by `MODEL_FILTER`) bypass all patching and are forwarded unchanged without the Anthropic beta header.

### Logging

Each patched request logs JSON to stdout including `explicit_cache_blocks`, `cache_breakpoint_indexes`, and `removed_thinking_blocks`. Health check at `GET /` or `GET /health`.
