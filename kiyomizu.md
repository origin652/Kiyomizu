# OpenRouter Claude Cache Gateway

This local gateway lets Cherry Studio keep using an OpenAI-compatible endpoint while forcing OpenRouter Claude requests into a cache-friendly shape.

## Start

```sh
node openrouter-cache-proxy.mjs
```

Optional settings:

```sh
HOST=0.0.0.0 PORT=8787 CACHE_TTL=1h CACHE_MODE=explicit CACHE_STRATEGY=stable-prefix CACHE_BREAKPOINTS=4 DYNAMIC_TAIL_MESSAGES=1 STRIP_THINKING=1 FORCE_PROVIDER=anthropic MODEL_FILTER=anthropic,claude node openrouter-cache-proxy.mjs
```

Default `HOST` is `127.0.0.1`, which only accepts local connections. Use `HOST=0.0.0.0` to expose the gateway to your LAN.

Only models whose names contain one of the comma-separated `MODEL_FILTER` terms are patched. By default, that means Claude/OpenRouter Anthropic model names are patched and all other models are forwarded unchanged.

## Defaults

```text
CACHE_MODE=explicit
SEND_TOP_LEVEL_CACHE_CONTROL=0
CACHE_STRATEGY=stable-prefix
CACHE_BREAKPOINTS=4
DYNAMIC_TAIL_MESSAGES=1
STRIP_THINKING=1
CACHE_TTL=1h
```

The gateway keeps the newest message out of the explicit cache breakpoint range and places up to 4 cache points across the stable prefix before it. This gives Anthropic multiple fallback points instead of betting the whole context on one breakpoint.

It also strips thinking/reasoning blocks from historical messages by default. Cherry Studio may send only the previous thinking record in thinking mode, which makes the prompt prefix unstable and expensive. Visible assistant text is preserved.

## Cherry Studio

Use these provider settings:

```text
API Base URL: http://127.0.0.1:8787/api/v1
API Key: your OpenRouter API key
Model: anthropic/claude-4.6-sonnet-20260217
```

For another device on your LAN, start with `HOST=0.0.0.0` and use:

```text
API Base URL: http://YOUR_MAC_LAN_IP:8787/api/v1
```

The proxy forwards requests to:

```text
https://openrouter.ai/api/v1/...
```

It also exposes model-list aliases for clients that expect OpenAI-style paths:

```text
GET /v1/models -> https://openrouter.ai/api/v1/models
GET /v1/model  -> https://openrouter.ai/api/v1/models
```

The returned model list is normalized to OpenAI shape:

```json
{
  "object": "list",
  "data": [
    {
      "id": "anthropic/claude-sonnet-4.6",
      "object": "model",
      "created": 0,
      "owned_by": "anthropic"
    }
  ]
}
```

and injects provider routing:

```json
{
  "provider": {
    "only": ["anthropic"]
  }
}
```

Provider choices are translated to OpenRouter slugs before sending: `anthropic`,
`amazon-bedrock`, `google-vertex`, and `google-ai-studio`.

In explicit mode, top-level `cache_control` is omitted by default. The gateway uses only block-level cache controls so the request stays within Claude's 4-block cache limit. Set `SEND_TOP_LEVEL_CACHE_CONTROL=1` only for automatic-cache experiments.

For non-Claude models, the proxy does not inject `cache_control`, does not force `provider.only`, and does not send the Anthropic beta header.

By default it rewrites each cache breakpoint message text into an explicit content block:

```json
{
  "role": "user",
  "content": [
    {
      "type": "text",
      "text": "...",
      "cache_control": {
        "type": "ephemeral",
        "ttl": "1h"
      }
    }
  ]
}
```

Set `CACHE_MODE=automatic` to disable this rewrite and only send the top-level `cache_control`.

Set `CACHE_STRATEGY=last` to restore the old behavior of putting the breakpoint on the last message. That is useful for tiny one-shot tests, but poor for normal chat.

Set `CACHE_BREAKPOINTS=1` to restore single-breakpoint behavior. The maximum is clamped to 4 because Claude supports up to 4 prompt cache breakpoints.

Set `STRIP_THINKING=0` if you want to forward thinking/reasoning blocks unchanged.

It also sends this header upstream:

```text
anthropic-beta: extended-cache-ttl-2025-04-11
```

## Verify

OpenRouter Activity should show native prompt cache hits:

```text
native_tokens_cached > 0
response_cache_source_id = null
```

Do not rely on `Cache write cost` alone to infer TTL. In testing, OpenRouter sometimes reported a 5-minute-looking write surcharge while the resulting cache lived longer than 5 minutes.

For logs, the gateway prints:

```text
explicit_cache_blocks
cache_breakpoint_indexes
removed_thinking_blocks
```
