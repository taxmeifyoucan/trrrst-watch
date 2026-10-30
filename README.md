# TRRRST Watch

**An alternative FOSS android companion app for the new Casio F-B100W**

In the contrast to the bloated adware official app, TRRRST Watch focuses on the actual watch features - simple steps, alarm, settings you need. No online account, telemtry, ads, or proprietary dependencies. The app does not even
request the Android `INTERNET` permission, all works fully offline and private. 

## Features

- Connect to the watch and synchronize its clock
- Download today's steps, hourly activity, distance, and local daily history
- Configure alarms, one-time/snooze modes and the hourly chime 
- Set home and world time cities, DST behavior, do the swap for home/world time
- Settings options for 12/24-hour display, key sounds, power saving, backlight duration, date order, connection duration, automatic time synchronization
- Configuration for your personal options - step goal, height, weight, countdown timer
- Also when connected can ring based onthe watch's phone finder feature


## Requirements

- Android 8.0 (API 26) or newer
- Bluetooth Low Energy
- Casio F-B100W in any color

The app requires Bluetooth/nearby devices permission but does not need internet access at all. 

The F-B100W advertises only briefly, hold the left bottom 'C' button to connect. Start a connection in the app, then you can wake up the watch while using the app unless you push another button that disconnects. 

## Build

JDK 17 and the Android SDK (platform 35) are required.

```sh
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected device with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the produced APK. 

## Project layout

- `app/src/main/java/dev/trrrst/watch/ble/` — BLE transport and protocol codecs
- `app/src/main/java/dev/trrrst/watch/ui/` — Compose UI
- `app/src/main/java/dev/trrrst/watch/data/` — local step-history storage
- `app/src/test/` — codec regression tests using captured protocol fixtures
- `fastlane/` — F-Droid-compatible metadata
- `art/` — editable launcher-icon source


## Disclaimer

This is an independent, unofficial project and is not ffiliated with CASIO. CASIO and F-B100W are trademarks of their respective owner. No Casio applicationcode, images, or audio are included.

Use at your own risk. Firmware modification and memory access are not features
of this app. When poking in memory I already soft bricked the watch once, had to take it apart and restart so be careful!  

# Acknowledgments

TRRRST Watch builds on public reverse-engineering work by the Casio open-source community:

- [izivkov/gshock_api](https://github.com/izivkov/gshock_api) - the Casio BLE/GATT command family, transport behavior and initial F-B100W model support provided important prior art for this project.
- [taviso/casiosync](https://github.com/taviso/casiosync) — independent analysis of the Casio life log record

The following tools were also useful during protocol research:

- [jadx](https://github.com/skylot/jadx)
- [blutter](https://github.com/worawit/blutter)

