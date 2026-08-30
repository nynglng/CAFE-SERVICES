# Handoff — 30 August 2026 (updated after the 58mm recalibration)

Where the project stands, so work can resume on another machine without
re-deriving any of it.

## What the system is

A café sells a "WiFi Voucher" line item on Loyverse. The gateway sits between
Loyverse and the receipt printer, takes the WiFi line out of the receipt,
issues Omada voucher codes for it, and prints the codes underneath.

Two halves, both in this repo:

- `src/`, `tools/` — a Node service that talks to Omada Cloud and issues
  vouchers. Runs on a PC. Works today.
- `android/printgateway/` — the print gateway, an Android app. Works today
  except that the codes it prints are placeholders.

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

## Open, in the order it matters

1. **Voucher codes on the phone.** The biggest unknown, and now the main thing
   standing between this and a real deployment. Omada issuing runs on a PC
   through a real Chrome profile; the phone has none. Likely path is a WebView
   holding the session, unproven. Until then the gateway prints placeholder
   codes derived from the receipt number.
2. **Bluetooth printer.** The gateway renders but does not transmit — the
   printer is at the client and has never been in hand. Risk: contention if
   Loyverse holds the connection open. The single-gateway design mitigates
   this, since only the gateway would talk to the printer.
3. **Nothing has been on paper yet.** Every check so far is against Loyverse's
   own graphic output, which is the right reference, but no physical print has
   been compared. The voucher's longest line, `WILL EXPIRE AFTER 24 HOURS`,
   comes to ~341px on 384px paper — 21px each side, the tightest thing on the
   page and the first to check.
4. **Receipt shapes still unseen.** Refunds (the amount pattern has no minus
   sign, though a `- 30.00` discount does render correctly), variants and
   modifiers, a store logo (which would make `hasImage()` true and disable the
   replica entirely), and five-figure totals. Pass-through means an unknown row
   now survives rather than vanishing, so the risk is layout, not loss.
5. **Multi-voucher spacing is still unverified on paper.** Two and three
   vouchers render, but only single-voucher output has been compared against
   anything real.

Smaller, noted but not acted on:

- No visual separator between the receipt and the voucher — they flow straight
  into each other.
- The voucher's expiry line hardcodes "24 HOURS" while the real validity comes
  from `VOUCHER_VALID_HOURS` on the service side. They agree today; change the
  validity and the slip quietly lies.
- `VoucherRenderer.render` takes access hours but not validity hours, which is
  why the above is hardcoded. Fixing it changes the signature and its call site.
