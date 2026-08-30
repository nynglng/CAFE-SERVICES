# Print Gateway (Android)

Sits between Loyverse POS and the receipt printer so the receipt can be
restructured on its way through.

## Why this shape

Loyverse cannot change its receipt layout and has no hook for injecting text
into a print. What it *can* do is print to an **Ethernet printer at an IP
address you type in**. So the gateway becomes that printer: it listens on port
9100 — the port every ESC/POS network printer listens on — and Loyverse is
pointed at the device's own address. What arrives is precisely the byte stream
that would have gone to the paper.

```
Loyverse POS ──TCP 9100──► Print Gateway ──► (restructure) ──► Bluetooth printer
   (same Android device)        │
                               └──► on-screen preview while there is no printer
```

Because the POS and the gateway are on the same phone, this works with no
internet, no cloud webhook, and no latency.

## Status

Working today:

- listens on 9100 and accepts print jobs
- decodes ESC/POS into readable text (alignment, bold, double width/height,
  feeds, cuts, and skipping logo/barcode payloads so binary does not leak in)
- shows each job as it would look on 58mm paper

Not built yet:

- the restructuring rules (this is the point of the exercise)
- forwarding to the real Bluetooth printer, which the client has and we do not
- persisting jobs across restarts; they are in memory only

## Build and install

No Gradle. The app needs no AndroidX and no Kotlin, so the SDK's own tools are
enough and a rebuild takes seconds:

```bash
./build.sh          # build only
./build.sh install  # build, install, launch
```

It expects the Android SDK at `%LOCALAPPDATA%\Android\Sdk` and a JDK at
Android Studio's bundled `jbr`. Override with `ANDROID_SDK`, `JAVA_HOME_WIN`,
`BUILD_TOOLS_VERSION`, `PLATFORM`.

## Pointing Loyverse at it

1. Open Print Gateway; note the addresses it lists
2. Loyverse POS → Settings → Printers → add a printer
3. Choose a generic ESC/POS model, and enter the gateway's IP
   - `127.0.0.1` if Loyverse accepts it (same device, works with no network)
   - otherwise the Wi-Fi address, e.g. `192.168.1.7` (needs Wi-Fi up)
4. Tap **Print test** in Loyverse — it should appear in the gateway

## Testing without Loyverse

Send a canned receipt from a PC over adb:

```bash
adb push receipt.bin /data/local/tmp/receipt.bin
adb shell "nc -w 3 127.0.0.1 9100 < /data/local/tmp/receipt.bin"
```

Note that `adb forward tcp:9100 tcp:9100` did **not** work for this — the
connection is accepted at the PC end but never reaches the app. Send from the
device's own shell as above, which is also the path Loyverse will use.

On Git Bash, prefix adb commands with `MSYS_NO_PATHCONV=1` or it rewrites
`/data/local/tmp` into a Windows path.

## Layout

```
AndroidManifest.xml
build.sh                     aapt2 + javac + d8 + apksigner, no Gradle
src/.../MainActivity.java    the preview screen
src/.../PrintServerService.java  foreground service holding the socket
src/.../EscPosDecoder.java   ESC/POS byte stream -> lines + styling
src/.../PrintJob.java        one job, and how it would look on paper
```

The service is a foreground service on purpose: the POS will be the app in
front, and a background socket would be killed the moment the screen slept.
