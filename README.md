# Cafe Voucher Service

A small middleware service that issues TP-Link Omada hotspot vouchers **one at a
time, at the moment of sale**, and deletes spent ones automatically.

It replaces the manual loop of *generate 500 vouchers → print them → hand them
out → hit the 5,000 voucher ceiling → delete the used ones by hand → generate
another batch.*

```
Loyverse POS ──receipts webhook──┐
                                 ├──► Voucher Service ──Open API──► Omada controller
Any POS / cashier page ──HTTP────┘         │                          (site A, site B)
                                           ├──► thermal printer (ESC/POS)
                                           └──► janitor: purges spent vouchers
```

Right now it runs against a **simulated controller**, so the whole flow works
end to end with no hardware and no cloud account. Switching to the real
controller is one environment variable plus the verification pass in
[docs/GO-LIVE.md](docs/GO-LIVE.md).

## Running it

Needs Node 22.6+ (tested on 24). No `npm install` — there are no runtime
dependencies.

```bash
cp .env.example .env      # edit API_KEY and LOYVERSE_WEBHOOK_SECRET
npm run demo              # narrated end-to-end walkthrough
npm test                  # 7 tests, including the 5,000-voucher cap recovery
npm start                 # start the service on :8787
```

`npm run demo` plays out a shift: a sale, a POS retry, a guest using their hour,
the automatic cleanup, a Loyverse receipt (delivered twice, as Loyverse does),
and an internet outage.

## Running the LIVE tools on a different machine

The simulator above needs nothing but Node. The tools that talk to the real
Omada Cloud site need two more things, and neither travels with the repo:

1. **`npm install`** — the live tools drive a browser via `playwright-core`.
2. **A Chrome profile that is logged into Omada Cloud.** The session lives in
   that profile, not in this repo, so on a new machine you must log in once by
   hand. Nothing is stored in git, by design.

Point the tools at that machine's paths and at your Omada account with
environment variables. The live `npm run` commands read them from a `.env`
file in the repo root, which git ignores; copy `.env.example` to start one.
Path defaults assume the original workstation's layout:

```bash
CHROME_PROFILE="C:/path/to/a/chrome-profile"
CHROME_EXE="C:/Program Files/Google/Chrome/Application/chrome.exe"
OMADA_ACCOUNT_ID=...                        # required - see .env.example
OMADA_SITE_ID=...                           # required - see .env.example
HEADED=1                                    # show the window, e.g. to log in
```

First run on a new machine:

```bash
npm install
HEADED=1 npm run live      # log into Omada in the window that opens, then use it
```

Live commands: `npm run live` (issue real vouchers), `npm run verify:live`
(prove create → read → expire → delete still works), `npm run capture`
(re-record the portal's API if TP-Link changes it).

## What the POS calls

One endpoint does the work.

```http
POST /api/v1/vouchers
Authorization: Bearer <API_KEY>
Content-Type: application/json

{ "siteId": "cafe-one", "planId": "1h", "reference": "R-1041",
  "idempotencyKey": "R-1041:line1:0" }
```

```json
{
  "issuanceId": "2aa32632-…",
  "code": "01234567",
  "source": "live",
  "replayed": false,
  "slip": { "text": "…ready to print…", "fields": { "code": "01234567", … } },
  "print": { "printed": true, "target": "192.168.1.50:9100" }
}
```

`idempotencyKey` is the important one: send the receipt/line id, and a retry
after a timeout returns **the same code** instead of minting a second voucher.

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/vouchers` | Issue a code for a sale |
| `POST` | `/api/v1/issuances/:id/reprint` | Reprint the same code (lost slip) |
| `GET` | `/api/v1/issuances/:id` | Look up a past sale |
| `GET` | `/api/v1/sites/:siteId/plans` | Plan list for the POS button layout |
| `GET` | `/api/v1/sites/:siteId/status` | Stored vouchers vs cap, reserve level |
| `POST` | `/api/v1/admin/cleanup` | Run the purge now |
| `POST` | `/api/v1/admin/pool/refill` | Top up the offline reserve |
| `POST` | `/webhooks/loyverse` | Loyverse `receipts.update` receiver |
| `GET` | `/healthz` | Liveness |

Simulation-only routes (`OMADA_MODE=mock`): `/api/v1/sim/redeem`,
`/sim/advance`, `/sim/offline`, `/sim/fill` — redeem a code, fast-forward the
clock, cut the controller off, or stuff a site full of vouchers.

## How a sale is served

In priority order, so a sale almost never fails:

1. **Replay** — the idempotency key was already used → return the same code.
2. **Live** — mint a fresh voucher on the controller. The normal path.
3. **Sweep and retry** — site is at its 5,000 ceiling → purge spent vouchers,
   then mint. This is the chore, automated.
4. **Reserve pool** — controller unreachable → hand out a pre-made code so the
   counter keeps selling through an outage.

Only if all four fail does the POS get a `503` with a reason it can show the
cashier.

## The Loyverse reality

**Loyverse cannot print a generated code onto its own sale receipt.** Its API is
read/write for receipts and items, but nothing injects dynamic text into the
printed receipt — the header and footer are static store settings.

So the flow is: cashier rings up *WiFi 1 Hour* → Loyverse fires
`receipts.update` → this service mints the voucher → a **second slip** prints
with the code. The cashier does nothing extra; the customer gets two pieces of
paper.

The webhook keys every voucher to `receipt:lineItem:index`, so Loyverse's
duplicate deliveries return the same codes rather than minting new ones.

Point each client's webhook at their own URL:
`https://your-host/webhooks/loyverse?siteId=cafe-one`, with the shared secret in
an `X-Webhook-Secret` header. Or set `loyverseStoreId` per site in
`config/sites.json` and skip the query parameter.

## Configuration

`config/sites.json` holds both clients — SSID, voucher cap, and the plans with
their durations, speed limits, prices, and the Loyverse SKUs that map to them.

```json
{ "id": "1h", "name": "WiFi 1 Hour", "durationMinutes": 60,
  "downKbps": 5000, "upKbps": 2000, "deviceLimit": 1,
  "priceMinor": 2000, "currency": "PHP", "codeLength": 8,
  "unusedValidDays": 7, "poolTarget": 8, "loyverseSkus": ["WIFI-1H"] }
```

`unusedValidDays` is what keeps the cap under control: a code nobody redeems
dies on its own and the janitor removes it. `poolTarget` is how many spare codes
to keep for outages — keep it small, they count against the 5,000.

Environment (`.env`): `API_KEY`, `LOYVERSE_WEBHOOK_SECRET`, `OMADA_MODE`
(`mock` | `live`), the four `OMADA_*` credentials, `JANITOR_INTERVAL_MINUTES`,
`JANITOR_HIGH_WATER`, and `PRINTER_HOST` / `PRINTER_PORT`. With no
`PRINTER_HOST`, slips are written to `data/print-log.txt` instead of printed.

## Layout

```
src/omada/adapter.ts   the interface both backends implement
src/omada/mock.ts      simulated controller: cap, expiry, sessions
src/omada/live.ts      real Open API client — READ THE HEADER BEFORE USE
src/domain/issuer.ts   the four-step hybrid issuing logic
src/domain/janitor.ts  the automatic purge
src/domain/pool.ts     the offline reserve
src/store/ledger.ts    our record of every sale, independent of the controller
src/print/slip.ts      the printed slip + ESC/POS encoding
src/loyverse/webhook.ts  receipt → vouchers
```

Every Omada-specific field name lives in `src/omada/live.ts`. Nothing else in
the service knows what Omada calls anything.

## Status

- Simulation: complete and tested.
- Live Omada: written but **unverified** against a real controller. See
  [docs/GO-LIVE.md](docs/GO-LIVE.md).
- Loyverse webhook: payload keys follow the documented shape; confirm with one
  test sale before relying on it.
