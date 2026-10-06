# Solr MCP — Community over Code 2026, Glasgow

Everything for the booth. The live card is what the QR points at:

**https://adityamparikh.github.io/solr-mcp/** · [slides](https://adityamparikh.github.io/solr-mcp/slides.html)

## Editing the card during the session

`site/` is the source of truth. Edit `site/index.html` (or `site/slides.html`), then:

```bash
./publish.sh
```

It pushes to the `gh-pages` branch and then **polls the live URL until it serves your
change** — usually 10–30s. It only reports success once the deployed bytes match, so a
green message means attendees are really seeing the new version. If it times out it says
so rather than lying, and points at the Actions tab.

Running it with no changes is a no-op, so it is safe to re-run.

The printed QR never changes — it points at the URL, not at a version — so you can edit
freely without reprinting anything.

## If the venue wifi cannot reach github.io

```bash
./serve-offline.sh          # or ./serve-offline.sh 8080
```

Serves `site/` from this laptop over the LAN and prints a QR for the address in the
terminal. Attendees must be on the same network.

The LAN address changes per network, so that QR is generated fresh on each run — **do not
print it in advance**. The printed card's QR is the github.io one and will not work
offline.

## Files

| | |
|---|---|
| `site/` | The card and slides. Edit here. |
| `publish.sh` | Push `site/` live and verify it deployed. |
| `serve-offline.sh` | Serve `site/` over the LAN with a scannable QR. |
| `solr-mcp-table-card.html` | A5 table card. Open and ⌘P. |
| `qr-pages.png` / `.svg` | The QR alone. Use the SVG for anything printed at size. |

## Notes

Pages are self-contained — the QR, styles and scripts are inlined, so they render with no
external requests and work from a USB stick.

The QR uses error-correction level H: roughly 30% of it can be scuffed or covered and it
still scans. Reprint if it gets worse than that.

The container image the card references is a personal pre-release build, not an Apache
release. The card says so, and that framing should stay.
