# Switch USB Inspector (Kamino Films)

An Android app that shows exactly what a Nintendo Switch 2 (or any USB device) exposes over USB-C, and can experimentally push an image's raw bytes to a writable endpoint.

## Features
- **Scan**: lists every USB device the phone can see as host. Nintendo devices (VID `0x057E`) are starred and sorted first.
- **Phone side**: shows the phone's own USB role (host, device, or charge-only) and power direction, so you can tell why a device isn't showing up.
- **Full dump** (after you grant permission): manufacturer, product and serial strings; parsed raw descriptors with a hex dump; the BOS descriptor; and language IDs.
- **Live events**: logs attach, detach and USB-role changes as they happen.
- **Auto-open**: plug in a Nintendo device and Android offers to open the app, with permission pre-granted.
- **Send image (experimental)**: pick an image and its bytes are sent in 16 KB chunks to the chosen bulk or interrupt OUT endpoint. There's an optional 4-byte length header, and the app reads the reply from the IN endpoint. Every result is reported honestly: accepted, stalled, or timed out.
- **Share log**: exports the whole session as text.

## Important
A stock Switch 2 has no software that receives files over USB. Usually the phone sees no device at all (the Switch acts as host, or the link is charge-only), or it sees no OUT endpoint. If bytes are "accepted", that only means the USB controller took them. It doesn't mean the console did anything with them. No keys or firmware files are needed or used.

## Build the APK (GitHub Actions, no Android Studio needed)
1. Create a new GitHub repo, e.g. `kaminofilms/SwitchUsbInspector`.
2. Push this folder to it, including the `.github` folder.
3. Open **Actions → Build APK**. The build runs on every push, or you can tap **Run workflow**.
4. Download the **SwitchUsbInspector-apk** artifact and install `app-release.apk` (or `app-debug.apk`).

Local build (with the Android SDK installed): `gradle assembleDebug`

## Using it with the Switch 2
1. Connect through a USB-C OTG adapter or an OTG-capable cable. Turn on OTG if your phone has a setting for it.
2. Keep the Switch 2 awake in handheld mode.
3. Open the app and tap **Scan**. If nothing appears, read the "Phone side" lines; they tell you which side is acting as host.
4. If a device appears: tap **Permission**, then **Full dump**, then **Share log**.
