#!/usr/bin/env bash
#
# Serve the card from this laptop over the local network, for when the venue
# wifi cannot reach github.io. Prints a QR for the LAN URL so people can still
# scan rather than type.
#
#   ./serve-offline.sh [port]     (default 8000)
#
# The LAN address changes with the network, so this generates the QR fresh each
# run — do not print it in advance.

set -euo pipefail

PORT="${1:-8000}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SITE="$HERE/site"

[ -d "$SITE" ] || { echo "error: $SITE not found"; exit 1; }

# First non-loopback IPv4 on a real interface.
IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
if [ -z "$IP" ]; then
  IP="$(ifconfig 2>/dev/null | awk '/inet /&&$2!="127.0.0.1"{print $2; exit}')"
fi
[ -n "$IP" ] || { echo "error: could not determine a LAN address — are you on wifi?"; exit 1; }

URL="http://${IP}:${PORT}/"

echo "==> serving $SITE at $URL"
echo "    (attendees must be on the same network; this address changes per network)"
echo

if python3 -c "import segno" 2>/dev/null; then
  python3 - "$URL" <<'PY'
import sys, segno
segno.make(sys.argv[1], error='m').terminal(compact=True)
PY
  echo
else
  echo "    (pip3 install segno for a scannable QR here)"
  echo
fi

echo "    Ctrl-C to stop."
echo
cd "$SITE"
exec python3 -m http.server "$PORT" --bind 0.0.0.0
