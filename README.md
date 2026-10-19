# Portal Boost

**Sharper PS5 Remote Play on the PlayStation Portal, from an unrooted Android phone.**

The Portal asks the PS5 for a stream of about 25 Mbps, and in practice gets about 20. Portal Boost sits between the two, rewrites that one request, and the PS5 streams at about 50–60 Mbps for the rest of the session. The picture is noticeably sharper, especially in dark scenes and fast motion.

No root, no jailbreak, no changes to the Portal or PS5 firmware. It's a normal Android app.

> Independent community project. Not affiliated with or endorsed by Sony. Use at your own risk.

## Results

Tested with a Galaxy S25, a PlayStation Portal and a wired PS5, router on 5 GHz channel 36 at 40 MHz:

| | Stock | Portal Boost (profile 65) |
|---|---|---|
| Stream bitrate | ≈ 20 Mbps | ≈ 47–60 Mbps |
| Latency shown on the Portal | ≈ 4 ms | ≈ 8–11 ms |
| Stutter | none | none |

**The trade-off:** the video passes through the phone, so it makes two Wi-Fi hops instead of one. That adds about 4–7 ms. Most people won't feel it, but if you play competitive shooters, try it before you commit.

This has been tested on **one** phone, one Portal and one router. Reports from other setups are very welcome. See [Reporting a problem](#reporting-a-problem).

## Requirements

- **Android 10 or newer** with Wi-Fi Direct. Older phones fall back to a random-password hotspot, which works but is laggier.
- **PS5 connected to the router with a cable.**
- **Router with 5 GHz on a fixed channel: 36, 40, 44 or 48, at 40 MHz width.** Not Auto. This matters a lot; see [Why the router channel matters](#why-the-router-channel-matters).
- The phone stays on your home 5 GHz Wi-Fi while it runs, with the app open and the screen on.

## Install

Download the latest APK from the [Releases](../../releases) page and install it. Android will ask you to allow installs from your browser or file manager.

Or build it yourself; see [Building](#building).

## Setup

The app has a step-by-step guide on the **Setup** tab. In short:

1. **Phone:** connect to your home Wi-Fi on 5 GHz, the same router the PS5 is wired to.
2. **Router:** set 5 GHz to channel 36, 40, 44 or 48 and the width to 40 MHz.
3. **Portal Boost:** tap Start. The app finds your PS5 and creates a private Wi-Fi network for the Portal.
4. **Portal:** go to Wi-Fi settings → **Enter Manually**:
   - SSID: `DIRECT-PB-PortalBoost`
   - Security: WPA2
   - Password: shown in the app (it's random, created once per install)
   - Proxy server: **Use**, address `192.168.49.1`, port `8080`
5. Start Remote Play on the Portal as usual. The app shows **Boost applied** when it worked.

You only enter the network once; the Portal remembers it.

**Optional:** on the Portal, forget your home Wi-Fi. Otherwise, whenever Portal Boost is off, the Portal rejoins home Wi-Fi and plays at stock quality (which is also how you turn the boost off).

## Quality profiles

| Profile | Requested bitrate | When to use |
|---|---|---|
| 45 | 45 Mbps | Most stable on a busy Wi-Fi |
| 55 | 55 Mbps | Sharper, with headroom |
| **65** (default) | 65 Mbps | Sharpest picture that stayed smooth in testing |
| 100 | 100 Mbps | Highest quality; needs a clean 5 GHz channel |

Advanced has 35, 200 (experimental; the PS5 encoder tops out around 100) and Off (relay at stock quality).

The PS5 keeps the bitrate it was given until the session ends. To switch profiles, stop Portal Boost, close Remote Play on the Portal, then start both again.

## Why the router channel matters

The phone has one Wi-Fi radio. It talks to your router and to the Portal at the same time, and that only works well when both links use the **same channel**:

- **Auto** often picks a DFS channel (for example 64). Wi-Fi Direct can't use DFS channels, so the Portal network lands on a different channel. The radio then hops between the two, and latency becomes unplayable.
- **160 MHz or 80 MHz** gave latency spikes in testing. **40 MHz** was the sweet spot. 20 MHz was stable too, but not better.

The app checks this and warns you when the channel is wrong.

## How it works

1. The phone joins your home Wi-Fi and creates a Wi-Fi Direct group that the Portal joins as a normal Wi-Fi network.
2. The Portal looks for the PS5 with a UDP broadcast. Portal Boost answers it, so the Portal connects to the phone.
3. The phone relays the session to the PS5 (TCP 9295, UDP 9296 and 9297).
4. On the way, it patches one byte of the Portal's first stream message, which carries the requested bitrate. The PS5 then streams at the higher rate for the whole session.
5. The Portal won't start Remote Play without internet access, so the app also runs a small HTTP proxy on port 8080 that serves only the Portal's network.

More detail in [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md).

## Privacy

- No accounts, analytics, ads or telemetry. The app makes no network requests of its own.
- Traffic is relayed, not stored or inspected beyond the one bitrate field.
- The HTTP proxy refuses connections from your home network, so it isn't an open proxy on your LAN.
- The log (Advanced tab) stays on the phone unless you copy it.

## Reporting a problem

Open an [issue](../../issues) and include:

- Phone model and Android version
- Router model, 5 GHz channel and width
- The profile you used, and what the Portal showed for latency
- The log from Advanced → Copy log

**Before you paste the log, remove the Wi-Fi password line.**

## Desktop version

[`portal_proxy.py`](portal_proxy.py) is the original proof of concept for Windows, macOS or Linux. It needs Python 3 and a machine the Portal can reach:

```sh
python3 portal_proxy.py --ps5 <PS5_IP> --profile 65
```

The Android app is the maintained version.

## Building

Requires JDK 17 and the Android SDK (platform 34).

```sh
cd android
./gradlew testDebugUnitTest assembleDebug
```

The APK is in `android/app/build/outputs/apk/debug/`.

## Credits

- The bitrate patch comes from [atameric/ps5-portal-high-bitrate](https://github.com/atameric/ps5-portal-high-bitrate) (MIT), which does the same thing from a computer using ARP redirection. Go give it a star.
- Protocol background from [chiaki-ng](https://github.com/streetpea/chiaki-ng).

## License

MIT. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

PlayStation, PS5 and PlayStation Portal are trademarks of Sony Interactive Entertainment Inc.
