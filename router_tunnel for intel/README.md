# RouterTunnel — VPN bypass proxy for FDPClient

A lightweight macOS TCP proxy that routes Minecraft traffic through your **physical network interface** (e.g. Wi-Fi / Ethernet), bypassing VPN tunnels.

## How it works

1. Uses the macOS-specific `IP_BOUND_IF` socket option to force outbound connections through a specific interface (e.g. `en0`), regardless of VPN routing rules
2. Listens on `127.0.0.1:25560` and acts as a transparent Minecraft proxy
3. Optional HTTP/HTTPS proxy on `127.0.0.1:25561` for browser routing
3. Reads the Minecraft handshake packet to find the real server address
4. Connects to the real server via the physical interface
5. Relays all traffic bidirectionally

The FDPClient mod detects the tunnel, and sends connections through it automatically.

## Build

```bash
cd router_tunnel
chmod +x build.sh
./build.sh
```

Or manually:
```bash
clang -O2 -Wall -Wextra -o router_tunnel router_tunnel.c
```

## Usage

```bash
# Auto-detect interface, default port
./router_tunnel

# Custom port
./router_tunnel --port 25560

# Enable the HTTP proxy on a custom port (default 25561)
./router_tunnel --http-proxy 25561

# Disable the HTTP proxy listener
./router_tunnel --no-http-proxy

# Force a specific interface
./router_tunnel --interface en0

# Always install scoped default route at startup (legacy behavior)
./router_tunnel --always-route

# Skip the initial connectivity test
./router_tunnel --skip-test
```

## In-game setup

1. Start `./router_tunnel` in a terminal
2. Launch Minecraft with FDPClient
3. Go to **Multiplayer** → click **Router** → enable **ConnectToRouter**
4. (macOS) Optional: open **Router Devices** to scan for Android tunnel devices on the local network
5. The status should show **"Tunnel active"** with your physical interface info
6. Join any server — traffic goes through your router, not the VPN

## Troubleshooting

### Connectivity test fails

By default, RouterTunnel uses **on-demand route fallback**: it first tries pure `IP_BOUND_IF` and only attempts a scoped default route if connectivity fails. This avoids unnecessary route-table churn that can reduce performance.

If you still see `Connectivity test FAILED`, add a scoped route manually:

```bash
# Find your gateway (usually your router IP like 192.168.1.1)
route -n get default -ifscope en0

# Add a scoped default route
sudo route add default <gateway_ip> -ifscope en0
```

### How to find your gateway

```bash
# Shows the default gateway for en0
route -n get default -ifscope en0
```

### Verify the tunnel works

With the tunnel running, the FDPClient status line next to the Router button shows:
- **Tunnel active: en0 (192.168.x.x)** — working
- **Tunnel: not running** — tunnel daemon not detected
- **Detecting...** — checking tunnel/interface status

## Architecture

```
┌─────────────┐     ┌───────────────────┐     ┌──────────────┐
│  Minecraft   │────▶│  RouterTunnel     │────▶│  MC Server   │
│  (FDPClient) │     │  127.0.0.1:25560  │     │  (Internet)  │
│              │◀────│                   │◀────│              │
└─────────────┘     │  IP_BOUND_IF=en0  │     └──────────────┘
   localhost         └───────────────────┘        via en0
                        bypasses VPN               (router)
```

## Chrome extension (list-based routing)

There is a Chrome extension in `chrome_extension` that lets you route **only a list of sites**
through RouterTunnel (via the HTTP proxy) with a single on/off toggle.

### What it does

- When routing is ON, Chrome uses `127.0.0.1:25561` as a proxy **only** for domains in the list
- Subdomains are included (e.g. adding `example.com` also matches `api.example.com`)
- All other traffic stays normal (through the VPN)

### How to use

1. Start RouterTunnel (HTTP proxy enabled):
   - `./router_tunnel` (default HTTP proxy port is `25561`)
2. In Chrome, open `chrome://extensions`
3. Enable **Developer mode**
4. Click **Load unpacked** and select `chrome_extension`
5. Click the extension, add a URL or domain to the list, and turn **Routing** ON

You can remove domains or turn routing OFF at any time.

**Note:** The extension normalizes entries to the main domain by taking the last two labels
(e.g. `ps.ssis-suzhou.net` becomes `ssis-suzhou.net`). This is a simple heuristic and may be
incorrect for some country-code domains like `example.co.uk`.

## Health check protocol

The FDPClient mod checks if the tunnel is running by:
1. Connecting to `127.0.0.1:25560`
2. Sending a single `0x00` byte
3. Reading back: `[1 byte length][JSON status]`

Response example: `{"status":"ok","interface":"en0","ip":"192.168.50.139"}`

### Control commands

- `0x01` — refresh gateway/route state (same response framing as health check)


### Android phone tunnel (optional)

RouterTunnel can forward traffic via an Android phone acting as a tunnel server.

1. Start the Android app tunnel server (see Android app folder)
2. Provide the phone IP and password to RouterTunnel:

```bash
./router_tunnel --phone-host 192.168.43.1 --phone-port 45454 --phone-password-file ./router_tunnel.password
```

You can also pass `--phone-password` directly, but a password file is recommended.
