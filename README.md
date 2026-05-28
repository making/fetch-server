# fetch-server

An MCP (Model Context Protocol) server that exposes a single `fetch` tool for
retrieving web resources over HTTP.

- `fetch` — fetch one or more URLs in a single call. By default each HTML body
  is converted to Markdown; set `markdown` to `false` to receive the raw
  response body instead. URLs are fetched concurrently and the results are
  returned together, one entry per URL.

Use this server when an MCP client (Claude Desktop, custom agents, etc.) needs
to read web pages or call HTTP APIs as part of its workflow. The server speaks
the MCP Streamable HTTP transport on `POST /mcp`.

Built with Spring Boot 4 + Spring AI 2.0. Supports GraalVM native image.

## Requirements

- JDK 25 (GraalVM CE 25 if you want to build a native image)
- Maven Wrapper bundled in the repository (`./mvnw`)

## Build & Run

### JVM

```sh
./mvnw spring-boot:run
```

### Native image

```sh
./mvnw -DskipTests native:compile
./target/fetch-server
```

The server listens on port `8090` by default (override with `PORT`).

## Tool Parameters

| name             | type                   | required | default |
|------------------|------------------------|----------|---------|
| `urls`           | string array           | yes      | —       |
| `markdown`       | boolean                | no       | `true`  |
| `headers`        | object (string→string) | no       | none    |
| `timeoutSeconds` | integer                | no       | `30`    |
| `maxBytes`       | integer                | no       | `1 MB`  |

`headers`, `timeoutSeconds`, and `maxBytes` apply to every URL in the call.

## Response

The tool returns a `results` array with one entry per requested URL, in the same
order as `urls`:

| field         | type    | description                                                      |
|---------------|---------|------------------------------------------------------------------|
| `url`         | string  | the requested URL                                                |
| `status`      | integer | HTTP status code, or `0` when the request failed                 |
| `contentType` | string  | response `Content-Type` (empty when absent or on failure)        |
| `title`       | string  | HTML document title; `null` when `markdown` is `false`           |
| `content`     | string  | Markdown when `markdown` is `true`, otherwise the raw body       |
| `truncated`   | boolean | `true` when the body exceeded `maxBytes`                         |
| `error`       | string  | failure message when this URL could not be fetched, else `null`  |

A failure of one URL does not abort the others; only that entry carries an
`error`.

## Testing with curl

The MCP Streamable HTTP transport requires a handshake before any tool call:

1. `POST /mcp` with method `initialize`. The response carries an
   `Mcp-Session-Id` header that must be sent on every subsequent request.
2. `POST /mcp` with method `notifications/initialized` to confirm the session.
3. `POST /mcp` with method `tools/list` or `tools/call`.

The examples below assume the server is running on `http://localhost:8090`.

### 1. Initialize the session

```sh
curl -i -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "capabilities": {},
      "clientInfo": {"name": "curl", "version": "1"}
    }
  }'
```

Look for the response header `Mcp-Session-Id: <uuid>` and reuse the UUID below.

```sh
SESSION_ID=<paste-uuid-here>
```

### 2. Confirm initialization

```sh
curl -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc": "2.0", "method": "notifications/initialized"}'
```

This returns HTTP 202 with no body.

### 3. List the available tools

```sh
curl -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc": "2.0", "id": 2, "method": "tools/list"}'
```

### 4. Call `fetch` (Markdown by default)

```sh
curl -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "fetch",
      "arguments": {"urls": ["https://example.com"]}
    }
  }'
```

### 5. Fetch multiple URLs and return the raw body

Pass several URLs in `urls`, and set `markdown` to `false` to skip the Markdown
conversion. The response `results` array preserves the input order.

```sh
curl -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
      "name": "fetch",
      "arguments": {
        "urls": ["https://example.com", "https://example.org"],
        "markdown": false,
        "maxBytes": 200
      }
    }
  }'
```

### Custom headers and timeout

```sh
curl -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{
    "jsonrpc": "2.0",
    "id": 6,
    "method": "tools/call",
    "params": {
      "name": "fetch",
      "arguments": {
        "urls": ["https://httpbin.org/headers"],
        "headers": {"User-Agent": "fetch-server/0.0.1", "X-Trace-Id": "demo"},
        "timeoutSeconds": 5
      }
    }
  }'
```

### One-shot script

The full handshake plus a tool call as a single script:

```sh
#!/usr/bin/env bash
set -euo pipefail
BASE=http://localhost:8090/mcp

INIT=$(curl -s -i -X POST "$BASE" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}')
SESSION_ID=$(printf '%s' "$INIT" | awk -F': ' 'tolower($1)=="mcp-session-id"{print $2}' | tr -d '\r\n')

curl -s -o /dev/null -X POST "$BASE" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

curl -s -X POST "$BASE" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"fetch","arguments":{"urls":["https://example.com"]}}}'
```

## Tests

```sh
./mvnw test
```

The unit tests spin up an in-process `com.sun.net.httpserver.HttpServer` and
exercise the tools end-to-end, including header propagation, timeout,
truncation, and charset decoding.
