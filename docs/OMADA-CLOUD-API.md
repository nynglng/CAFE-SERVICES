# Omada Cloud API — reverse-engineered notes

Everything here was derived from the live account on 2026-08-29 by capturing the
portal's own traffic. Re-derive it with `npm run capture` if TP-Link changes
their site.

## The situation

Omada Cloud **Essentials** (the free tier, which both cafe sites are on) does
not expose the Open API — that is gated behind paid **Cloud Standard**. There is
no "Platform Integration" menu anywhere in Essentials; it is not hidden, it does
not exist.

**But the portal itself is a single-page app driven by REST endpoints**, and we
are a legitimate operator of that portal. Driving a logged-in browser profile
and calling those same endpoints works, with no subscription.

## How to authenticate

1. Launch Chrome with a profile that is logged into Omada Cloud
2. Load `https://aps1-omada-cloud.tplinkcloud.com/`, then **poll until the portal
   reports `login: true`** — the SPA establishes the controller session *after*
   load, and how long that takes varies. Calling too early returns
   `errorCode -1200` ("You have been logged out of the controller"). See
   "Session lifecycle" below; a fixed wait is not reliable.
3. Read the `csrfToken` cookie
4. Send on every call:
   - `x-requested-with: XMLHttpRequest` — **required**, without it the server 404s
   - `csrf-token: <value of the csrfToken cookie>`
   - `accept: application/json, text/plain, */*`

`request-hash` is sent by the portal but is **not verified** — a deliberately
bogus value behaves identically to omitting it. We do not reproduce it.

Implemented in [../src/omada/session.ts](../src/omada/session.ts).

## Session lifecycle (the part that bites)

The session lives in a Chrome profile, never in this repo. Two things about it
cost real time to learn:

**1. `loginStatus` returns success even when nobody is logged in.**

```json
{ "errorCode": 0, "msg": "Success.",
  "result": { "configured": true, "orgType": 20, "orgCategory": "free",
              "login": false, "registeredRoot": true } }
```

`errorCode: 0` only means the question was answered. The real state is
`result.login`. Checking the error code alone reports a healthy session on a
profile that has never been signed in. Always check `result.login === true`.

(`orgCategory: "free"` here is the Essentials tier confirming itself.)

**2. The controller session does not survive reopening the profile.**

`TPOMADA_SESSIONID` is a session cookie, so a freshly launched profile has no
controller session and every call returns -1200 for a few seconds while the SPA
re-establishes one. Waiting a fixed number of seconds was flaky; `open()` now
polls `loginStatus` until `login: true`, reloading the page every few attempts
to nudge the app along.

When the cloud login itself has expired, no amount of waiting helps — run
`npm run login`, which opens a visible window and waits for a real sign-in.

## Identifiers

| Thing | Value |
| --- | --- |
| API base | `https://aps1-api-omada-essential-controller.tplinkcloud.com` |
| Portal UI | `https://aps1-omada-cloud.tplinkcloud.com/` |
| Omadac ID | 32 hex characters, one per account |
| Site id | 24 hex characters, one per cafe |

Paths are `{apiBase}/{omadacId}/api/v2/hotspot/sites/{siteId}/...`

The ids belong to a client's account, so they are kept out of the repo. The
Android gateway learns both from whoever signs in. The PC tools read
`OMADA_ACCOUNT_ID` and `OMADA_SITE_ID` from `.env`. Any portal call in the
browser's Network tab shows both in its path.

## Endpoints

| Purpose | Call |
| --- | --- |
| Session check | `GET /api/v2/hotspot/loginStatus?needToken=true` |
| List groups | `GET .../voucherGroups?currentPage=1&currentPageSize=100&searchField=name%2Ccode` |
| **Read codes in a group** | `GET .../voucherGroups/{groupId}?currentPage=1&currentPageSize=500` |
| Codes (alt, more fields) | `GET .../vouchers?groupId={groupId}&currentPage=1&currentPageSize=N` |
| Create group | `POST .../voucherGroups` |
| Delete group | `DELETE .../voucherGroups/{groupId}` |
| Portals | `GET .../setting/voucher/portals` |

Vouchers are created in **groups**; a group holds N codes and is the unit of
deletion. Create returns only `{ id }` — codes must be read back separately.

Paths that do NOT exist (return `-1600 Unsupported request path`):
`voucherGroups/{id}/vouchers`, `voucherGroups/{id}/voucher`,
`setting/voucher/{id}`.

## Create body

Confirmed working. See `createGroup` in [../src/omada/cloud.ts](../src/omada/cloud.ts).

Key fields:

| Field | Meaning |
| --- | --- |
| `amount` | how many codes (1–5000) |
| `codeLength` | 6–10 |
| `codeForm: [0]` | digits only |
| `type: 2` | Unlimited For Usage |
| `durationType: 1` | Voucher Duration (counts from first login) |
| `duration` | **minutes** (120 = 2 hours) |
| `maxUsers` | devices per code |
| `validityType` | **0 = Permanent, 1 = Fixed Dates** |
| `effectiveTime` / `expirationTime` | epoch ms; required when `validityType: 1` |

### The expiry gotcha

For Fixed Dates, `voucherValidityEnable` stays **`false`**. Setting it `true`
gets the request rejected with `-1001 Invalid request parameters`. The dates go
in `effectiveTime` (start of day) and `expirationTime` (23:59:59.999 of the last
day), as absolute epoch milliseconds.

## Response shapes

Every response is `{ errorCode, msg, result }` with `errorCode: 0` on success.

A voucher row:

```json
{ "code": "012345", "id": "<voucher-id>", "status": 0,
  "endTime": 9223372036854775807, "timeLeftSec": 7200, "timeUsedSec": 0 }
```

- `status`: 0 unused, 1 in use, 2 expired
- `endTime`: `9223372036854775807` (Java `Long.MAX_VALUE`) means **never
  expires**. It overflows a JS number, so compare it as a string.

## Why vouchers pile up (the actual root cause)

State of the live site when measured:

```
3 groups, 1913 vouchers stored — 1881 unused, 32 used
every group: validityType = 0 (Permanent)
```

Batches of 500–700 are created on **Permanent**, roughly 2% get redeemed, and
the other 98% can never expire. They accumulate until the 5000 cap forces a
manual delete.

Automating generation alone does not fix this. **Creating groups with
`validityType: 1` and a sensible expiry is the fix** — unused codes then die on
their own and the cap stops being a concern.

## Error codes seen

| Code | Meaning |
| --- | --- |
| `0` | success |
| `-1001` | invalid request parameters (bad field combination) |
| `-1200` | logged out of the controller — re-open the session |
| `-1600` | unsupported request path |
