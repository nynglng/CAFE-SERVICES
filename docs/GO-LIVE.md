# Going live

The simulation is finished. This is what has to be true before it drives real
vouchers at a client site.

## 1. Find out what your controller actually exposes

This is the first blocker and it decides everything else. The Omada Open API is
**not available on every tier**, and hotspot voucher endpoints move between
controller versions.

Log into the controller for one client and look for:

**Settings → Platform Integration → Open API**

- **The menu is there** → create an application. You get a `client_id`, a
  `client_secret`, and an **Omadac ID**. Note the region base URL. Go to step 2.
- **The menu is missing** → that tier does not expose the API. Options, cheapest
  first:
  1. Move the site to a **self-hosted controller** — the Omada Software
     Controller runs free on any always-on PC, or a Raspberry Pi, or a small
     VPS, and exposes the full Open API. The EAPs just get re-adopted to it.
     This also removes the 5,000 cap question entirely.
  2. An **OC200 / OC300** hardware controller at the site (one-off cost, no
     subscription, same API).
  3. A paid **Cloud-Based Controller** subscription.

For two AC1200 EAP sites, a software controller on a cheap mini-PC at each shop
— the same box that runs this service and talks to the receipt printer — is
usually the right answer. It also means the voucher service keeps working when
the site's internet is down, which the cloud path can never do.

Write down for each client: controller type, version, Omadac ID, site id.

## 2. Verify the four API shapes

`src/omada/live.ts` is written from the documented v1 Open API but has **not**
been run against a real controller. Open the API browser
(`https://<controller>/doc.html` on a self-hosted or hardware controller, or the
Open API reference for your cloud region) and confirm:

1. **Token** — `POST /openapi/authorize/token?grant_type=client_credentials`,
   and that the access token is presented as `Authorization: AccessToken=<tok>`.
2. **Create voucher** — the field names in the request body: `amount`,
   `codeLength`, `durationType`, `duration`, `limitType`/`limitNum`,
   `rateLimit`, `expirationTime`. Units matter: confirm whether `duration` is
   milliseconds, minutes, or seconds on your version.
3. **Reading codes back** — creation returns a voucher *group*; confirm the
   detail call that lists the actual codes in it.
4. **Delete** — the per-voucher delete path, and whether your version has a bulk
   "delete expired/invalid" call. If it does, use it in
   `LiveOmadaAdapter.deleteVouchers` — it is one request instead of hundreds.

Fix any mismatch in `src/omada/live.ts` only. Nothing else in the service knows
Omada field names.

## 3. Point the service at one site, in parallel

Do not cut over both clients at once.

```bash
OMADA_MODE=live
OMADA_BASE_URL=https://<controller-or-region-host>
OMADA_CID=<omadac id>
OMADA_CLIENT_ID=…
OMADA_CLIENT_SECRET=…
```

Set the real `omadaSiteId` in `config/sites.json`, then:

```bash
curl -H "Authorization: Bearer $API_KEY" localhost:8787/api/v1/sites/cafe-one/status
```

A real `storedVouchers` count coming back means the credentials, the site id,
and the list call all work. Then issue one voucher and redeem it on a phone
before letting the POS near it.

Run the janitor manually the first few times (`POST /api/v1/admin/cleanup`) and
read the report before trusting it on a timer.

## 4. Wire Loyverse

1. In Loyverse, create the WiFi items with SKUs matching `loyverseSkus` in
   `config/sites.json` (`WIFI-1H`, `WIFI-3H`, `WIFI-1D`).
2. The webhook needs a public HTTPS URL. If the service runs on a box at the
   shop, a Cloudflare Tunnel gives you one without opening a port.
3. Register the `receipts.update` webhook at
   `https://your-host/webhooks/loyverse?siteId=cafe-one` and set the
   `X-Webhook-Secret` header.
4. Ring up one WiFi item on a test receipt. Check the logged payload — if
   Loyverse's key names differ from what `src/loyverse/webhook.ts` expects, that
   file is the only one to change.

## 5. The printer

`PRINTER_HOST` + `PRINTER_PORT=9100` sends ESC/POS straight to a network thermal
printer. Bluetooth/USB printers paired to the Loyverse tablet are **not**
reachable this way — those sites need either a second network printer or a
cashier-facing page that prints through the tablet's browser.

Printing never fails a sale: the code is minted and returned to the POS first,
and the slip can always be reprinted with
`POST /api/v1/issuances/:id/reprint`.

## 6. Before it is really production

- Run it under a process supervisor (systemd, or `pm2`) so it restarts on boot.
- Back up `data/ledger.json` — it is the only record of what was sold.
- Change `API_KEY` and `LOYVERSE_WEBHOOK_SECRET` from the example values.
- Do not expose the service to the internet beyond the webhook path; the POS
  endpoints should stay on the LAN or behind the tunnel with the bearer key.
- Decide the retention rule with each client: `unusedValidDays` is how long an
  unredeemed code stays alive. Shorter means the cap is never a concern.
