#!/bin/bash
# Build the RouterTunnel proxy for macOS
# Usage: ./build.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/router_tunnel.c"
OUT="$SCRIPT_DIR/router_tunnel"

echo "[build] Compiling router_tunnel.c ..."

# Build a universal binary on Apple Silicon by default (arm64 + x86_64),
# so it runs natively on M-series Macs and also on Intel Macs.
if [[ "$(uname -m)" == "arm64" ]]; then
  echo "[build] Apple Silicon detected → building universal (arm64 + x86_64)"
  clang -O2 -Wall -Wextra -arch arm64 -arch x86_64 -o "$OUT" "$SRC"
else
  clang -O2 -Wall -Wextra -o "$OUT" "$SRC"
fi

echo "[build] Done → $OUT"
echo ""
echo "Usage:"
echo "  $OUT                    # auto-detect interface, port 25560"
echo "  $OUT --port 25560       # custom port"
echo "  $OUT --http-proxy 25561 # HTTP proxy port for browser routing"
echo "  $OUT --no-http-proxy    # disable HTTP proxy listener"
echo "  $OUT --interface en0    # force interface"
echo "  $OUT --always-route     # always install scoped default route"
echo "  $OUT --skip-test        # skip connectivity check"
echo ""
echo "Then enable 'Connect to Router' in the FDPClient multiplayer screen."
