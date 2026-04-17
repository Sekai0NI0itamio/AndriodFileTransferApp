# RouterTunnel Manual

RouterTunnel is a macOS-only TCP proxy that forces outbound connections to use a specific physical network interface (for example, `en0`), bypassing VPN routing rules. It provides two local listeners:

- A Minecraft tunnel and control port on `127.0.0.1:25560`
- An HTTP/HTTPS proxy on `127.0.0.1:25561`

This manual focuses on how other apps can detect RouterTunnel and route web requests through it.

**Important**: RouterTunnel binds only to localhost and has no authentication. Do not expose it to untrusted networks.

## Quick Start

1. Build the binary:

```bash
cd router_tunnel
./build.sh
```

2. Run the tunnel:

```bash
./router_tunnel
```

3. Point an app at the HTTP proxy `127.0.0.1:25561` and make a request.

## CLI Reference

```bash
./router_tunnel [OPTIONS]
```

Options:

- `--port PORT` Listen port for the Minecraft tunnel and control protocol. Default `25560`.
- `--http-proxy PORT` HTTP proxy port. Default `25561`.
- `--no-http-proxy` Disable the HTTP proxy listener.
- `--interface IFACE` Force a specific interface, for example `en0`.
- `--always-route` Always install scoped default route at startup.
- `--skip-test` Skip the initial connectivity test.
- `--help` Show usage.

## How Routing Works

- RouterTunnel uses the macOS socket option `IP_BOUND_IF` to bind outbound connections to a specific interface by index.
- By default, RouterTunnel does **not** force a scoped default route at startup. It only attempts a scoped route fallback when a connectivity probe fails.
- Use `--always-route` if you explicitly want startup route installation.
- It performs an optional connectivity test by connecting to `1.1.1.1:443` through the bound interface.

If you see a connectivity failure, you may need to add a scoped route manually (see Troubleshooting).

## Ports And Protocols

### 1. Control + Minecraft Port (`127.0.0.1:25560`)

This port serves two purposes:

- Minecraft proxying (FDPClient uses this)
- A tiny control protocol for health checks and route refresh

The first byte determines the mode:

- `0x00` Health check. Returns a length-prefixed JSON status and closes.
- `0x01` Refresh. Re-detects gateway; with `--always-route`, also re-applies scoped route setup.
- Any other byte is treated as the start of a Minecraft handshake packet.

The Minecraft tunnel is not a generic TCP proxy. Use the HTTP proxy for general web traffic.

### 2. HTTP/HTTPS Proxy (`127.0.0.1:25561`)

This is the recommended integration point for other apps. It supports:

- HTTP/1.x proxy requests with absolute-form or origin-form targets
- HTTPS via `CONNECT host:port`, then raw TCP tunneling

The proxy does not decrypt TLS. It simply tunnels bytes after the `CONNECT` handshake.

## Health Check And Refresh API

Use this if your app needs to detect whether RouterTunnel is running or refresh routing without restarting it.

Request:

- Open a TCP connection to `127.0.0.1:25560`
- Send a single byte

Responses:

- Health check byte `0x00` returns:

```json
{"status":"ok","interface":"en0","ip":"192.168.50.139"}
```

- Refresh byte `0x01` returns:

```json
{"status":"refreshed","interface":"en0","ip":"192.168.50.139","gateway":"192.168.50.1"}
```

The response format is:

- 1 byte length `N` (0–254) followed by `N` bytes of UTF-8 JSON
- If the length byte is `0xFF`, the next 2 bytes are a big-endian `uint16` length, followed by that many UTF-8 JSON bytes

## HTTP/HTTPS Proxy Behavior

- The proxy reads up to 16 KB of headers and then forwards the request.
- For HTTP requests, it rewrites the request line to origin-form and preserves headers, omitting `Proxy-Connection`.
- For HTTPS, it requires `CONNECT` and then relays raw TCP.
- It supports IPv4 DNS resolution (`A` records) only.

Common HTTP error responses include `400 Bad Request`, `414 Request-URI Too Long`, `431 Request Header Fields Too Large`, and `502 Bad Gateway`.

## Integrating Other Apps

### Use Environment Variables (Most CLI Tools)

Many tools respect these variables:

```bash
export HTTP_PROXY=http://127.0.0.1:25561
export HTTPS_PROXY=http://127.0.0.1:25561
export NO_PROXY=localhost,127.0.0.1
```

Examples:

```bash
curl -x http://127.0.0.1:25561 https://example.com
curl -x http://127.0.0.1:25561 http://example.com
```

### Python Requests

```python
import requests

proxies = {
    "http": "http://127.0.0.1:25561",
    "https": "http://127.0.0.1:25561",
}

r = requests.get("https://example.com", proxies=proxies, timeout=10)
print(r.status_code)
```

### Generic HTTPS Tunneling

If your app can open a TCP connection and speak HTTP, you can tunnel any TCP service via `CONNECT`:

```
CONNECT example.com:443 HTTP/1.1
Host: example.com:443

```

If the proxy replies `HTTP/1.1 200 Connection Established`, the TCP tunnel is open and you can start TLS or any other TCP protocol.

## Troubleshooting

- If you see `Connectivity test FAILED`, the VPN likely removed the default route for the physical interface. Add a scoped route manually:

```bash
sudo route add default <gateway_ip> -ifscope <interface>
```

- To find the gateway for an interface:

```bash
route -n get default -ifscope en0
```

- If the HTTP proxy is disabled, start RouterTunnel without `--no-http-proxy`.

## Limitations And Security Notes

- macOS only (uses `IP_BOUND_IF`).
- IPv4 only. No IPv6 support.
- TCP only. No UDP.
- No authentication, access control, or encryption on the local proxy.
- HTTP/2 and WebSocket upgrades are not explicitly handled.
- Idle connections time out after 5 minutes of inactivity.

## Minimal Status Integration Example

The example below checks RouterTunnel status from Python:

```python
import socket
import json

with socket.create_connection(("127.0.0.1", 25560), timeout=2) as s:
    s.sendall(b"\x00")
    n = s.recv(1)[0]
    data = s.recv(n)
    status = json.loads(data.decode("utf-8"))
    print(status)
```
