# Handoff — 30 August 2026

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

`ReceiptRenderer` redraws the receipt as a pixel-faithful replica of Loyverse's
own graphic mode. Every constant in it was measured off a real capture, not
chosen by eye; `NAME_COLUMN_RIGHT = 478` was pinned by four constraints that
left a two-pixel window. `calibrate()` logs our type widths against known real
values so drift can be caught. If Loyverse changes its font, that log is the
first place to look.

Two details that silently corrupt everything if got wrong, both documented at
their call sites:

- Loyverse sends `0x7F` where `₱` belongs. It **occupies a print column**, so
  it becomes a space — never dropped, or every following character shifts.
- A wrapped item name rejoins with a space only if the part on the row stops
  short of the fixed 38-character name field; if it fills the field the break
  was mid-word and the halves join directly. See `ReceiptParser.joinName`.

### Building and testing

`android/printgateway/build.sh` — no Gradle, just `aapt2`/`javac`/`d8`/
`apksigner`. It fails loudly on a compile error; it used to swallow them and
install a stale APK, so do not reintroduce a `|| true` there.

To replay a captured sale without ringing one up, send it from the **device's
own shell** — `adb forward` silently never reaches the app:

    adb shell 'cat /sdcard/Android/data/com.cafeservices.printgateway/files/jobs/<file>.bin | toybox nc 127.0.0.1 9100'

The app must be in the foreground first, or the connection is refused. Under
Git Bash, `export MSYS_NO_PATHCONV=1` or the device paths get mangled.

Every job writes its raw bytes and a `-replica.png` to that same `jobs/`
directory, so the replica can be compared against the real print at any time.

## Open, in the order it matters

1. **Voucher codes on the phone.** The biggest unknown. Omada issuing runs on a
   PC through a real Chrome profile; the phone has none. Likely path is a
   WebView holding the session, unproven. Until then the gateway prints
   placeholder codes derived from the receipt number.
2. **Bluetooth printer.** The gateway renders but does not transmit — the
   printer is at the client and has never been in hand. Risk: contention if
   Loyverse holds the connection open. The single-gateway design mitigates
   this, since only the gateway would talk to the printer.
3. **Multi-voucher layout is unverified.** Only a single-voucher capture has
   been replayed. Ring up a WiFi Voucher with quantity 3 and check the spacing.
4. **Receipt shapes: partly seen now.** Three real sales are captured and all
   parse correctly at 32 columns. A **PWD discount** appears indented
   (`| PWD                     - 30.00|`), so it does not match the item
   pattern and passes through harmlessly — but only that one discount type has
   been seen. A payment row (`Card`, `Gcash`) *does* sit at column 0 with an
   amount and matches the item pattern; it is rejected only because no
   quantity row follows, so do not weaken that check.

   Still unseen: refunds (the amount pattern has no minus sign), variants and
   modifiers, a store logo (which would make `hasImage()` true and disable the
   replica entirely), and five-figure totals.

   **`NAME_FIELD` and `NAME_COLUMN_RIGHT` are now pinned at 58mm.** A test sale
   with two deliberately long names — one unbroken, one spaced — was printed in
   both modes from a phone's Loyverse (the site's item list must not be
   polluted). `NAME_FIELD = 22` is exact: the unbroken name broke after exactly
   22 characters. `NAME_COLUMN_RIGHT = 291` is bracketed to 286..297 and sits
   mid-window.

   That test also proved something the design assumed but had never shown:
   **the two modes wrap in different places.** Text fit `aaa bbb ccc ddd eee`
   (19 chars, since `fff` would exceed the 22-char field) while graphic fit
   `aaa bbb ccc ddd eee fff` — because graphic wraps on pixels and `fff` is
   narrow. Copying text rows onto the canvas would break names in the wrong
   place; re-wrapping against `NAME_COLUMN_RIGHT` is what makes it correct.

   Both `joinName` cases are verified end to end: the spaced name rejoins with
   its space restored, the unbroken one joins directly with no spurious space.
5. **Voucher typography — settled.** The verdict was to match the receipt
   rather than have a look of its own: same fonts, same sizes, keep it simple.
   `VoucherRenderer` now takes both sizes and all three line pitches from
   `ReceiptRenderer` instead of defining its own, so the two cannot drift apart.
   The code prints at the receipt's bold total size, the one place that page
   already shouts; everything else is body size. Rendered but **not yet seen on
   paper** — it needs a build on the machine with the Android SDK.
