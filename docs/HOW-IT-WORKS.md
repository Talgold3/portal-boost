# How Portal Boost works

This is the technical background for people who want to understand, audit or improve the app. You don't need any of it to use Portal Boost.

## The idea

During Remote Play setup, the Portal sends the PS5 a "LaunchSpec": a JSON description of the stream it wants, including a bitrate of `25000` kbps. The PS5 honours whatever bitrate the LaunchSpec asks for, for the whole session. Change that one number before it reaches the PS5, and the PS5 streams at the higher rate.

[atameric/ps5-portal-high-bitrate](https://github.com/atameric/ps5-portal-high-bitrate) found where the number is and how to change it. That tool runs on a computer and uses ARP redirection to get in the path between Portal and PS5. ARP redirection needs raw sockets, which an unrooted Android phone doesn't have. Portal Boost gets into the path differently: it becomes the Portal's network.

## Network layout

```
                 home Wi-Fi (5 GHz, ch 36)             Wi-Fi Direct (same channel)
   PS5 ──cable── router  ─────────────────────  phone  ───────────────────────────  Portal
                                               192.168.49.1                    192.168.49.x
```

- The phone stays on the home Wi-Fi and creates a Wi-Fi Direct group, `DIRECT-PB-PortalBoost`, with a passphrase generated once per install. The Portal sees it as an ordinary WPA2 network.
- Wi-Fi Direct and the home connection share the phone's single radio. On the same channel they share airtime cleanly. On different channels the radio time-slices between them, and latency jumps to 100 ms or more. That's why the router must be on a fixed, non-DFS channel.
- If Wi-Fi Direct isn't available (Android 9 or older, or a failure), the app falls back to Android's LocalOnlyHotspot. It works, but the SSID and password change on every start and latency is worse.

## Session relay

All of this uses ordinary sockets. Nothing needs root.

| Port | Protocol | What Portal Boost does |
|---|---|---|
| 9302 | UDP | **Discovery.** The Portal broadcasts a search. The app answers with the PS5's own reply (cached from asking the PS5 directly), so the Portal treats the phone as the PS5. Wake-up requests are forwarded. |
| 9295 | TCP | **Session setup and control.** Relayed byte for byte. |
| 9296 | UDP | **Stream ("Takion").** Relayed both ways. The first large Portal→PS5 message is patched (below). |
| 9297 | UDP | **Bandwidth test ("senkusha").** Relayed. |

The relay marks packets with DSCP so Wi-Fi gives them priority: video to the Portal as AC_VI, input to the PS5 as AC_VO. UDP buffers are kept small (256 KB), so stale video is dropped on the phone and doesn't queue up behind newer frames.

## The patch

See [`Patch.kt`](../android/app/src/main/java/dev/portalboost/Patch.kt).

1. The Portal's first big Takion message (about 2.6 KB, split over two UDP packets) carries the LaunchSpec as a protobuf field: base64 text of a stream-cipher-encrypted JSON blob, normally 2296 characters.
2. The packet is matched narrowly: fixed header bytes, length, protobuf tag and version 20. Anything else passes through untouched.
3. The plaintext at offset 334 is `25000`. Because the cipher is a stream cipher, XOR-ing the ciphertext XORs the plaintext. The app decodes the few base64 characters around offset 334, XORs in a small delta, re-encodes them and writes them back. The packet length never changes.
   - One byte turns the leading `2` into `3`, `4`, `5` or `6` (35–65 Mbps).
   - Five bytes turn `25000` into `1e+05` or `2e+05` (100 or 200 Mbps).
4. The message authentication tag (GMAC) is still zero at this stage of the protocol, so the PS5 doesn't notice the change.

The app logs `PATCHED`, `NOT_MATCHED` or `WRONG_SIZE`, and the UI shows "Boost applied" or "Boost not applied" from that.

## Internet passthrough

The Portal won't start Remote Play unless it has internet access, and the Wi-Fi Direct group has none. So the app runs an HTTP/CONNECT proxy on `192.168.49.1:8080`, set in the Portal's Wi-Fi "Proxy server" field. It only serves clients that are *not* on the home subnet, so it isn't an open proxy on your LAN.

## Where the extra latency comes from

The upstream desktop tool relays only the session start. After that, the PS5 streams straight to the Portal, so it adds no latency during play.

Portal Boost can't drop out like that. The Portal is on the phone's network and talks to the phone's address, so every video packet crosses two Wi-Fi hops (router → phone → Portal) on one radio. In testing this added 4–7 ms: about 4 ms on the Portal hop, plus some queueing on the home hop that grows with bitrate.

Ways to remove it, all outside the scope of an unrooted app:

- **Root:** ARP redirection, as upstream does.
- **A small always-on device** (a Raspberry Pi or a travel router) running the upstream tool.
- **USB-C Ethernet** on the phone for the home side. Not implemented yet; expected to bring it down to about 5–7 ms total.

## Diagnostics

The Advanced tab shows:

- **Ping per hop:** phone ↔ Portal, phone ↔ router, phone ↔ PS5, every 0.2 s.
- **Video loss:** gaps in the 16-bit packet index of the plaintext Takion AV header on the PS5 → phone leg. Loss on the Portal leg can't be seen from the phone.
- **Channel checks:** a DFS warning and a home/Portal channel mismatch warning.
- **Full log:** with Copy log for bug reports. The log includes the Wi-Fi password; remove it before posting.
