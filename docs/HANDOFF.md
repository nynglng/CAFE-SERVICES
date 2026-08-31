# Handoff — 31 August 2026 (deployed, printing real vouchers at the counter)

Where the project stands, so work can resume on another machine without
re-deriving any of it.

## What the system is

A café sells a "WiFi Voucher" line item on Loyverse. The gateway sits between
Loyverse and the receipt printer, takes the WiFi line out of the receipt,
issues Omada voucher codes for it, and prints the codes underneath.

Two halves, both in this repo:

- `android/printgateway/` — the print gateway, an Android app. **This is the
  product.** It signs into Omada itself, issues real codes per sale, redraws
  the receipt, and sends the result to a Bluetooth printer.
- `src/`, `tools/` — the original Node service, which does the same thing from
  a PC through a signed-in Chrome profile. Still works, still the place the
  API was worked out, but no longer on the critical path.

## Omada: settled, do not re-litigate

Omada Cloud **Essentials has no Open API** — that is a paid Cloud Standard
feature. Confirmed twice: from TP-Link's product page, and from
`orgCategory: "free"` in the portal's own API payload. There is no Settings →
Platform Integration menu to find.

The way in is the portal's **own JSON API**, called with a session from a
logged-in Chrome profile driven by `playwright-core`. That is what
`src/omada/session.ts` does, and it is proven against the real account. Full
notes in [OMADA-CLOUD-API.md](OMADA-CLOUD-API.md).

The **root cause of the voucher pile-up** was not the manual work — it was that
every group was created as `validityType: 0` (Permanent) and ~98% were never
redeemed, so nothing could ever expire on its own. Automating generation alone
would not have fixed it. Groups are now created as Fixed Dates.

Omada flips a spent voucher to EXPIRED but **does not delete it**, and expired
vouchers still count against the 5000-voucher cap, so cleanup remains
necessary. `voucher-sale-N` runs it after every sale.

The safety rule for cleanup lives in `src/omada/housekeeping.ts` and is shared
by the tool and its test on purpose: a group is only ever deleted if it carries
the service tag, the POS name prefix, **and** is Fixed Dates. Manually made
groups are untouchable. Do not loosen this.

## The Android gateway

Loyverse is set to **Text** print mode and points at one Ethernet printer,
which is the gateway on port 9100. Text mode gives fixed-width rows with no
line breaks — the printer is expected to wrap — so the stream is re-cut into
rows before anything is read from it.

**The client's paper is 58mm, confirmed by the owner: 32 columns, 384px.** The
first calibration assumed 80mm (48 columns, 576px) from an early test print,
and at that width *nothing parses at all* — every item row is cut in the wrong
place. Real captures from the site are in `captures/` (gitignored: they are
real customer sales). Inspect them with `tools/inspect-capture.mjs` and measure
graphic ones with `tools/measure-capture.mjs`.

Useful discovery from the recalibration: **type sizes and line pitches are
identical between 58mm and 80mm** — only the widths change. Body type is 17px
tall either way, the total 26px, 37px to the quantity row, 56px between items.

The flow: `EscPosDecoder` → `ReceiptParser` → `SalePlan` → `ReceiptRenderer` +
`VoucherRenderer`, stacked into one bitmap by `PrintJob.replica()`.

`SalePlan` decides the shape of the printout: WiFi alone → voucher only; WiFi
with other items → receipt (WiFi line removed) then voucher; no WiFi → receipt
untouched.

`ReceiptRenderer` **replays every row Loyverse sent, in order**. It used to
rebuild the page from recognised pieces and silently dropped the rest — on a
real café sale that lost the PWD discount, the Card payment, "Dine in" and the
address, leaving items totalling 800 against a printed total of 770. Anything
unrecognised now survives by default. Do not go back to a whitelist.

Item names are the one deliberate exception: Loyverse breaks them at its
22-character text field, graphic mode breaks on pixels and lands elsewhere, so
`ReceiptParser.Item` records the rows each item occupies and the renderer
substitutes the rejoined name re-wrapped against `NAME_COLUMN_RIGHT`. Verified:
the rewrap lands on the same 19-character break as Loyverse's own output.

Alignment is decided by what a value **is** — only an amount or the receipt
number is pinned right. Rows arrive already trimmed, so trailing padding cannot
be consulted; splitting on any wide gap threw "POS: POS 1" to opposite edges.

`calibrate()` logs our type widths against real measurements. It currently
reads `upper15=200 (real 197)` and `upper19=287 (real 286)` — within a pixel or
three, so our type matches Loyverse's. If it ever drifts far, that log is the
first place to look. Note the references must cite strings that can actually
appear unbroken on 58mm paper; an earlier pair came from an 80mm capture and
made the type look 46px too wide when nothing was wrong.

Three details that silently corrupt everything if got wrong, all documented at
their call sites:

- Loyverse sends `0x7F` where `₱` belongs. It **occupies a print column**, so
  it becomes a space — never dropped, or every following character shifts.
  `ReceiptRenderer.restorePeso` puts the symbol back at that exact space.
- A wrapped item name rejoins with a space only if the part on the row stops
  short of the fixed 22-character name field; if it fills the field the break
  was mid-word and the halves join directly. See `ReceiptParser.joinName`.
- The quantity row carries the **per-unit** price, not the line total. Building
  it from `item.amount` printed "2 x ₱400.00" for two items at ₱200 — a
  customer reads that as ₱800. Only shows up above quantity 1.

### Building and testing

`android/printgateway/build.sh` — no Gradle, just `aapt2`/`javac`/`d8`/
`apksigner`. It fails loudly on a compile error; it used to swallow them and
install a stale APK, so do not reintroduce a `|| true` there. It now takes the
newest build-tools and platform actually installed rather than pinned versions,
which is what let a second machine build without touching the script.

Two things that cost time when setting up a new machine:

- **The debug key is per-machine**, so installing over a build from another PC
  fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. You have to uninstall first
  — and uninstalling **deletes the app's `jobs/` folder**, which is where every
  captured print lives. Pull it before you uninstall. There is a copy at
  `captures/phone-backup/`.
- After `am start` the socket takes a moment; wait for `listening on 9100` in
  logcat before replaying, or the job silently never arrives.

To replay a captured sale without ringing one up, send it from the **device's
own shell** — `adb forward` silently never reaches the app:

    adb shell 'cat /sdcard/Android/data/com.cafeservices.printgateway/files/jobs/<file>.bin | toybox nc 127.0.0.1 9100'

The app must be in the foreground first, or the connection is refused. Under
Git Bash, `export MSYS_NO_PATHCONV=1` or the device paths get mangled.

Every job writes its raw bytes and a `-replica.png` to that same `jobs/`
directory, so the replica can be compared against the real print at any time.

## How the phone holds an Omada session

The app signs in through a WebView (`OmadaLoginActivity`) and keeps the
cookies; `OmadaSession` calls the portal's own JSON API with them. Account and
site are read from `/api/v2/users/current` rather than compiled in - a site id
belonging to the wrong cafe fails silently, since codes are created happily and
simply do not work on the network in the room.

The account login is durable: it survived a force-stop and nine hours closed
overnight. The controller session underneath is not, and lapsing shows up as
errorCode -1200. `OmadaReconnector` loads the portal out of sight and retries;
a keepalive checks every 15 minutes between sales. That only works while the
account login holds - when TP-Link expires it, someone must sign in via OMADA.

When no codes can be had, the slip says "Please ask cashier for a wi-fi
voucher" and nothing else. It must never print invented digits: they look
exactly like real codes to a guest, who would type them, fail, and blame the
cafe.

## One sale, one set of codes

Two things print the same sale twice, and both were caught on the counter:

- **A split bill** prints one receipt per payment, each carrying the whole item
  list. Three vouchers paid half cash, half card issued three codes and then
  another three. The halves are indistinguishable - same items, same total,
  same time, both carrying "Amount due", only the payment line differs - so
  there is no final half to hold codes back for.
- **A reprint** of the same receipt would do the same.

Both are handled by naming the group after the sale: `POS-<receipt>-<stamp>`.
A sale that already bought vouchers is found by asking Omada, so this survives
a restart or a reinstall. Split halves are the one case Loyverse leaves the
receipt number off; they fall back to `SplitSales`, which matches on items and
total (never the payment rows) and only ever applies to receipts carrying an
"Amount due" row.

## It is live, and what the counter said about it

The gateway went into the cafe and printed real vouchers on the real printer
(a JK-5802H, 58mm, 60mm/s, USB+BT). Two complaints came back, both since
addressed. Do not undo any of this without measuring first.

**It printed lighter than Loyverse.** `INK_THRESHOLD` sat at mid-grey, which
throws away most of the antialiased edge of every glyph. Counted against a real
Loyverse print of the same receipt we burned 0.889x its ink per row; at 160 we
burn 0.996x. `tools/ink-compare.mjs` re-measures this — it decodes a Loyverse
capture and our replica PNG and counts dots at several cuts.

**A sale took about five seconds.** Almost none of it was work: instrumented,
the gateway's own decode, render and encode come to ~95ms. It was all waiting,
in four places:

- *Deciding the job had ended.* A 3000ms read timeout, paid in full on every
  print because Loyverse holds the socket open. Every ESC/POS job ends with a
  cut, so the cut now ends the job; the timeout is a 1200ms fallback.
- *Dialling Omada.* `request()` called `disconnect()` in a `finally`, which is
  HttpURLConnection's way of discarding the socket, so both calls of an issue
  paid a fresh TCP connect and TLS handshake to Singapore. It now disconnects
  only on failure. The existing keepalive moved from 15 minutes to 45 seconds
  so the pooled connection is still open when a sale arrives.
- *Dialling the printer.* An RFCOMM connect on every single receipt, which is
  the best part of a second or two. The link is now opened once and kept, with
  one retry on a failed write. `DRAIN_MS` went with it — that 600ms existed
  only to let the printer catch up before the socket closed, and it no longer
  closes.
- *Sending three times more than needed.* Every band went full width, blank
  paper included, and a receipt is about 60% blank rows. Loyverse encodes its
  gaps as bands one byte wide; we now do the same and trim content bands to
  their right edge. The same page dropped from 45.0 KB to 13.9 KB against
  Loyverse's own 14.4 KB, verified by round-tripping a replica through the new
  packing and decoding it back — 954px of paper either way, zero pixels
  different.

Also: `handleClient` ran on the accept thread, so the gateway accepted nothing
while a job issued and printed; jobs now get their own thread. And the screen
only learned of a job after it finished printing, which reads as a gateway slow
to accept it — it now says so on arrival.

**Vouchers are deliberately NOT pre-issued.** Pooling codes would take the
Omada call off the critical path, but the 24 hour expiry starts at creation, so
a code minted in the morning would hand an evening customer a few hours while
the slip promises a day. Irene rejected it for that reason and she is right.
The generation process is untouched; only the plumbing under it got faster.

**The timing line is the tool for any further work here.** Every job logs
`connect->data`, `read`, `vouchers`, `render`, `print` and a total, and
BluetoothPrinter logs whether the link was reused or reopened and how long each
took. Read those from a real sale before optimising anything — every guess made
about this so far has been wrong, and the parts are not the sizes they look.

**Still unmeasured on the new build:** nobody has yet done a sale on it and
read the timing line. That is the first thing to do. If `vouchers` is still
over a second the connection-pooling theory is wrong and the call needs timing
directly.

## Open, in the order it matters

1. **Time one sale on the current build.** Everything above is reasoned from
   measurements of the old one. Do a sale and a reprint back to back - the
   reprint should reuse the open printer link and be visibly quicker - then
   read the timing line before changing anything else.
2. **Multi-voucher spacing has never been on paper.** Single vouchers print
   correctly at the counter. Two and three render, but nothing above one has
   been physically printed.
3. **The phone must stay awake and charged.** The service is foreground and
   START_STICKY, but no work has been done on battery optimisation, and the
   staff will not be taught to restart anything.
4. **1913 legacy Permanent vouchers** still sit on the site and can never
   expire; cleanup will not touch them, by design. They are 38% of the 5000
   cap. Deleting them is a job for the portal, by hand, when convenient.
5. **Receipt shapes still unseen.** Refunds (the amount pattern has no minus
   sign, though a `- 30.00` discount renders correctly), variants and
   modifiers, a store logo (which would make `hasImage()` true and disable the
   replica entirely), and five-figure totals.

Smaller, noted but not acted on:

- No visual separator between the receipt and the voucher — they flow straight
  into each other.
- Reprinting a long-expired receipt mints fresh vouchers, since a cleaned-up
  group reads as spent. Left deliberately: Irene does not reprint old receipts,
  and refusing would also refuse a genuine next-morning reprint.
- The keepalive and the reconnect both cost a request; neither has been seen
  firing in anger, because the session has not yet lapsed.
