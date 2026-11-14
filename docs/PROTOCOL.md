# Protocol reference

## Skullcandy Crusher ANC 2: AT commands over RFCOMM channel 9

Plain text. Each command ends with `\r`, and you read the reply until `OK` or `ERROR`.
This works on Linux (`AF_BLUETOOTH`/`BTPROTO_RFCOMM`) and on Windows (Winsock `AF_BTH`, `BTHPROTO_RFCOMM=3`).

| Command | Effect / reply |
|---|---|
| `AT.UIAUDIO?` | `anc: on/off`, `transparency: on/off` |
| `AT.CRUSHER?` | `level: <n> / <max>` (bass, 0–10) |
| `AT.UIAUDIO=anc,on` | ANC on (send `AT.UIAUDIO=transparency,off` first) |
| `AT.UIAUDIO=transparency,on` | Transparency on |
| `AT.UIAUDIO=anc,off` + `AT.UIAUDIO=transparency,off` | Both off |
| `AT.CRUSHER=level,<0-10>` | Set the bass level |
| `AT.CRUSHER=state,on\|off` | Sensory bass on/off. `AT.CRUSHER?` also replies `state: ON/OFF` |

### Extras from Skull-iQ's AT code

These come from the AT request providers in the Skull-iQ app (a decompile of its Bragi SDK). They are
generic Bragi AT commands, so not every firmware has to implement them. The panel, the phone and
`crusher_win.py` read each one once per connection and only offer the ones that answered `OK`.
`crusher_win.py probe` lists what your firmware answers, and it only sends queries.

| Command | Effect / reply (as the app parses it) |
|---|---|
| `AT.UIAUDIO=transparency` | Query. The reply after `:` is `on\|off[,<level>]` |
| `AT.UIAUDIO=transparency,,<0-100>` | Transparency level. Note the empty state field |
| `AT.UIAUDIO=atoncall` / `=atoncall,on\|off` | Hear-through (transparency) during calls |
| `AT.UIAUDIO=atonpause` / `=atonpause,on\|off` | Hear-through while the music is paused |
| `AT.UIAUDIO=anc_filter` / `=anc_filter,<n>\|off` | ANC filter id. The valid range isn't in the app code, so only the API and the CLI expose it |
| `AT.VOLUME=up,<n>` / `AT.VOLUME=down,<n>` | Step the headset volume (`crusher_win.py volume`) |
| `AT.FEATURE=count` | Read-only feature count (`probe`). `AT.FEATURE=enable,<id>,on\|off` exists but is **not** used |

There is **no EQ over AT.** The app's EQ, Personal Sound (Mimi), button remapping and similar settings
go over Bragi's protobuf RPC or Airoha's RACE transport, not AT. For tone, the Crusher has its sensory
bass, and the source-side EQ (below) covers everything else.

## AirPods Pro 2: AAP over classic L2CAP channel `0x1001`

| Purpose | Bytes |
|---|---|
| Handshake | `00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00` |
| Feature flags | `04 00 04 00 4D 00 FF 00 00 00 00 00 00 00` |
| Turn on notifications | `04 00 04 00 0F 00 FF FF FE FF` |
| Noise mode | `04 00 04 00 09 00 0D <m> 00 00 00`, where `m`: 1 off, 2 ANC, 3 transparency, 4 adaptive |
| Mode report (AirPods → host) | `04 00 04 00 09 00 0D <m> …` |
| Battery (AirPods → host) | `04 00 04 00 04 00 <count> [<type> 01 <level> <status> 01]…`. Type: 2 **right**, 4 **left**, 8 case; status 1 charging, 2 discharging, 4 disconnected |
| Audio source (AirPods → host) | `04 00 04 00 0E 00 <mac, reversed> <type>`. Type: 0 none, 1 call, 2 media |
| Connected devices (AirPods → host) | `04 00 04 00 2E 00 01 00 <count> [<mac> <2 bytes>]…` |

### AAP control commands (settings)

Every setting is `04 00 04 00 09 00 <id> <v> 00 00 00`. The AirPods push the current value of each one
after the notification request and echo every change, which is how the panel and the phone read them
back. The ids come from [LibrePods](https://github.com/kavishdevar/librepods) (`docs/control_commands.md`,
`AACPManager.kt`). For on/off settings, `v` is `01` for on and `02` for off.

| id | Setting | `v` |
|---|---|---|
| `0x0D` | Noise mode | 1 off, 2 ANC, 3 transparency, 4 adaptive |
| `0x2E` | Adaptive Audio noise level | 0–100. **0 lets the most noise in, 100 filters the most.** The UIs show "Less noise ↔ More noise" as `100 - v`. It only has an effect in Adaptive |
| `0x28` | Conversational Awareness | on/off |
| `0x26` | Personalized Volume | on/off |
| `0x0A` | Automatic ear detection | on/off |
| `0x1A` | Press-and-hold cycle | bitmask: 1 off, 2 ANC, 4 transparency, 8 adaptive (keep at least two) |
| `0x34` | Offer "Off" in that cycle | on/off |
| `0x1B` | Noise cancelling with one AirPod | on/off |

Checked live on AirPods Pro 2 (2026-11): after the notification request (`0F 00 FF FF FF FF`) they report
`0x0D`, `0x1B`, `0x26`, `0x28`, `0x2E` (and others the panel doesn't use), but **never `0x0A`, `0x1A` or
`0x34`**, and they **don't echo a change back to the host that sent it**. A change is applied, though:
reconnecting AAP makes them report the new value (seen for `0x28`). So the panel remembers what it last
set (`~/.config/headphone-panel/airpods-settings.json`) and shows that for the unreported ones, and its
"Read back" button reopens the AAP channel (not the audio link) to get a fresh report. LibrePods does the
same for `0x1A` (it keeps `long_press_byte` in its preferences).

Not done, and why:
- **Loud Sound Reduction** is not an AAP control command. It is an ATT write (handle `0x1B`) on a
  separate Bluetooth channel, and LibrePods only opens that channel with a vendor-ID hook.
- **AirPods EQ.** LibrePods has packets for Headphone Accommodation (`0x53`) and a three-band custom EQ
  (`0x63`). Their encoding isn't documented well enough to send blind, so the EQ here is source-side.

## Source-side EQ (all platforms)

`shared/eq_presets.json` is the one model: five RBJ biquads at 80 Hz (low shelf), 250 Hz, 1 kHz and
3.5 kHz (peaks, Q 1), and 10 kHz (high shelf), with gains from -12 to +12 dB. Presets are Flat, Bass boost,
Vocal/Podcast and Treble, and any other set of gains is "Custom". A preamp of `-max(boost)` keeps the
curve from clipping.

| Platform | How it's applied |
|---|---|
| Linux laptop | `laptop/hp_eq.py` starts a `pipewire -c` filter-chain registered as a WirePlumber smart filter in front of the default output (or `eq_target` in config.json); the default output and its volume stay the real headphones. Gain changes are applied live with `pw-cli set-param`. Turning it off just stops the process |
| Android | `EqService` (a foreground service) uses `audiofx`. It tries DynamicsProcessing on the global session 0, then Equalizer on session 0, and also attaches to any player that broadcasts `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`. The biquad curve is sampled into 24 DynamicsProcessing bands, or into the phone's Equalizer bands |
| Windows | `windows/eq_apo.py` writes `crossdevice-eq.txt` (Preamp, LSC, PK and HSC filters) into Equalizer APO's config folder and includes it from `config.txt`. **Equalizer APO must be installed**; the script installs nothing |

## AirPods battery broadcast (BLE, no connection)

Apple manufacturer data `0x004C`, first byte `0x07`. As a hex string `h` of that data:
- model: `h[6:10]` (`2420` = AirPods Pro 2 USB-C)
- flipped = `int(h[10],16) & 0x02 == 0`
- left = `h[12]` if flipped else `h[13]`; right = the other one; case = `h[15]` (each × 10 %, `f` = unknown)
- charging flags = `h[14]`: case `0b100`; left/right `0b01`/`0b10` (swapped when flipped)
- status byte (`h[10:12]`, byte 5), as LibrePods' Linux code reads it: bit 6 = the bud sending this advert is
  in the case, bit 4 = one bud in the case, bit 2 = both in the case, bit 5 = left bud is primary; in-ear bits
  1 and 3 (which is which depends on bits 5 and 6). The Android finder uses bits 6/4/2 to split "case" from
  "bud out of the case". Byte 9 is the colour.
- The advert comes from a **resolvable private address** that changes about every 15 minutes. The phone app
  resolves it with the AirPods' IRK (below); without the IRK it can only guess (same model, colour and
  similar battery levels).

### What the AirPods sense and report themselves (no call mode)

| Message | Bytes | Meaning |
|---|---|---|
| Ear detection | `04 00 04 00 06 00 <primary> <secondary>` | 0 in ear, 1 out of ear, 2 in case. Sent on changes and once right after the notification request (seen) |
| Conversation Awareness | `04 00 04 00 4B 00 02 00 01 <level>` | The wearer started (01/02) or stopped (03) speaking; 08/09 normal. Only while CA is on |
| Microphone stream start | `04 00 04 00 58 00 00 00 09 00 00 01 82 00 00 00 04 96 00` | Asks for the mic stream |
| Microphone stream stop | `04 00 04 00 58 00 00 00 02 00 03 01` | |
| Microphone audio | `04 00 04 00 58 00 01 00` + 14 more header bytes, then `[timestamp u32 LE][len u8][AU]`… | AAC-ELD, mono, 64 kHz, 480-sample frames; AudioSpecificConfig `F8 E6 30 00` |

Sources: LibrePods [`docs/AAP Definitions.md`](https://github.com/kavishdevar/librepods) (0x06, 0x4B, 0x2E),
[librepods PR #655](https://github.com/librepods-org/librepods/pull/655) (`aacp.rs`, `aacp_audio.rs`, `eld.rs`) and
the LibrePods `android/rewrite` branch (`AACPManager.requestMicrophoneStream`, `MicrophoneFrame`, `EldDecoder`) for
0x58. LibrePods turns Conversation Awareness off while the stream runs; so does this app. The mic stream is not
documented by Apple and is not yet confirmed here on real AirPods, so the app treats it as experimental: if no
audio arrives within 5 s it stops and says so, and it never falls back to another microphone. No AAP message is
known that reports an ambient noise *level*: Adaptive Audio measures it inside the AirPods and acts on it.

The Crusher reports nothing it senses over AT: `AT.UIAUDIO?` and its unsolicited `.UIAUDIO` lines give only the
mode, `AT.CRUSHER?` the bass. The Airoha SDK inside Skull-iQ has an "environment detection" call (stationary
noise per side, ANC gains), but Skull-iQ never calls it, and it would go over Airoha's RACE transport, not AT.

### Ear detection: left and right (Android 1.9.0)

The `0x06` report is ordered **primary bud first**, not left first. The primary is the bud that holds the link to
the phone (and the microphone); when it leaves the ear the AirPods hand that role to the other bud and send the
report again with the pair exchanged (LibrePods, "AAP Definitions"). So `00 02` means "one bud in ear, the other
in the case", and which one is left needs a second source. The newest of these observations wins:

1. **The AAP battery report's charging states** (`0x04`, above, with the types and states as corrected there). A
   bud in the case is charging (`01`) or off the link (`04`); a bud out of it discharges (`02`). When the ear report
   has exactly one bud in the case and the battery report exactly one bud charging or off the link, that is the
   same bud, which says whether the primary is left or right. The battery report must be no older than 5 s before
   the ear report. With the notification mask `FF FF FF FF` the battery report comes right after the link opens
   (with `FF FF FE FF`, as 1.9.0 sent, none came in minutes); LibrePods' Android app and the laptop panel send
   `FF FF FF FF` too.
2. **The BLE proximity advert**, status bit 5 ("left bud is primary", LibrePods' Linux `blemanager.cpp`), from a
   short scan (up to 6 s, stopped at the first advert from these AirPods) when the link opens and when the ears
   change, at most every 30 s. Not yet confirmed on a real advert: on this phone, with the screen off, the scan
   heard no advert at all.
3. **The battery report's order**: LibrePods' Linux app takes the first bud listed as the primary (`battery.hpp`).
   In the one live check (right bud in the ear and primary, left in the case) the right bud (`02`) was listed
   first, which agrees, but that can't yet be told from a fixed right-then-left order, so it is used only while
   nothing else has said. (1.9.0 used it with left and right swapped, which is why its sides were reversed.)
4. **Role swap inference**: a report `(a, b)` followed within 2 s by `(b, a)`, with no other observation in
   between, is the primary hand-off, so the known side flips with it.

**Lid closed on a bud** (seen live): the bud in the case drops off the link, so the ear report gives a value
other than 0/1/2 for it ("unknown") and the battery report state `04` (disconnected, level 0). The app then shows
that bud as "in case (lid closed)" if it was last in the case or charging, or if nothing is known about it (a bud
out of the case stays on the link), and treats it as in the case for auto-pause and resume; a bud that was last in
an ear or out and then drops off reads "disconnected". The last place and charging state of each side are kept
across links and app restarts for this.

If none of these is known yet, the app says "One bud in ear, the other in case (which is left isn't known yet)"
instead of guessing. The pause and listening rules don't need the sides: they count buds in an ear.

The app keeps **one** AAP link per phone (`AapLink.kt`): ear detection holds it while the AirPods are connected,
and the mic stream and short reads or settings join it rather than opening a second link (the AirPods expect one
link per host). The opening sequence (handshake, features `0x4D`, notifications `0x0F`) is sent once, when the
first user opens it.

### Proximity keys over AAP (Android Last seen / finder)

Sent on the phone's own AAP link, format from LibrePods (`AACPManager`): request
`04 00 04 00 30 00 <types> 00` (`01` = IRK, `04` = encryption key; the app asks only for `01`, at most once an
hour until it has it). Reply `04 00 04 00 31 00 <count>` then per key `<type> 00 <len> 00 <key bytes>`. The
16-byte IRK is least-significant byte first; an address resolves when `ah(IRK, prand) == hash` (Bluetooth Core
spec, Vol 3 Part H), with `prand` = the top three address bytes and `hash` = the bottom three. The key stays
in the app's private storage.

AirPods battery over AAP: `04 00 04 00 04 00 <count>` then per component `<type> 01 <level> <status> 01`
(type 2 **right**, 4 **left**, 8 case, 1 single headset; status 0 unknown, 1 charging, 2 discharging, 4 disconnected;
LibrePods `battery.hpp`). Up to 1.9.1 the app and the laptop panel had both swapped (2 read as left, 2 read as
charging), which showed left and right battery the wrong way round and reversed the ear-detection sides. A real
report with both buds in the ears and the case away: `04 00 04 00 04 00 03 02 01 5B 02 01 04 01 5D 02 01 08 01 00 04 01`
= right 91% discharging, left 93% discharging, case disconnected.

No "play a sound" command is documented by LibrePods (its README lists it as planned, not done), so the app
doesn't send one.

### Seeing the packets live (Android Packets screen)

The phone app's *Packets* screen (1.8.0) logs every AAP packet and AT command/reply at the one place each
crosses the socket (`PhoneDirect.write` and the shared AAP link's reader in `AapLink`, `CrusherDirect`'s session) and decodes them with
the tables above. Opcodes it decodes: `0x04` battery, `0x06` ear detection, `0x09` control commands, `0x0E`
audio source, `0x0F` notifications, `0x17` head tracking, `0x1A` rename, `0x1D` device info, `0x2E` connected
devices, `0x30`/`0x31` proximity keys, `0x4B` Conversation Awareness, `0x4D` feature flags, `0x58` mic stream;
anything else shows as "unknown opcode". Addresses (`0x0E`, `0x2E`), key bytes (`0x31`), names and serials
(`0x1A`, `0x1D`, and any text in any packet except model numbers and firmware versions) are masked before
they are stored.

## Panel HTTP API (laptop, 127.0.0.1:8970, published over Tailscale)

| Method | Path | Body | Notes |
|---|---|---|---|
| GET | `/api/state` | | `{crusher:{online,anc,transparency,bass,bass_on,extras}, airpods:{online,mode,battery,on_phone,settings}, eq:{…}}`. A setting is `null` until the device reports it |
| GET | `/api/eq` | | `{enabled,preset,gains,target,running,available,error,bands,presets,gain_min,gain_max}` |
| POST | `/api/eq` | `{"enabled":true,"preset":"bass"}` or `{"gains":[6,2,0,0,0]}` | Any subset |
| POST | `/api/eq/cycle` | | Next preset, and turns the EQ on (the ring's EQ wedge) |
| POST | `/api/airpods/setting` | `{"name":"adaptive_level","value":30}` | Names: `adaptive_level` (raw 0–100), `conversational_awareness`, `personalized_volume`, `ear_detection`, `one_bud_anc`, `allow_off` (bool), `press_hold_modes` (list of `off\|anc\|transparency\|adaptive`) |
| POST | `/api/crusher/setting` | `{"name":"transparency_level","value":40}` | `bass_on`, `transparency_level` (0–100), `at_on_call`, `at_on_pause` (bool), `anc_filter` (int or `"off"`) |
| POST | `/api/airpods/refresh` | | Reopens the AAP channel so the AirPods report their settings again (takes ~2 s) |
| GET | `/api/sound` | | `{available, sources:[{id,kind:laptop\|bluetooth,label,muted}], listening, bins, notch:{mic,output}}` |
| GET | `/api/sound/stream?src=<id>` | | Server-sent events, ~8/s: `{"seq":n,"bins":[160 dB values, 20 Hz–11 kHz log-spaced]}`. Capture stops ~4 s after the last viewer leaves |
| GET | `/api/sound/inspect?lo=&hi=` | | Peak frequency, level, tonal/steady and a rough label for that band over the last ~3 s, plus ANC advice |
| POST | `/api/sound/stop` | | Stop listening now |
| POST | `/api/sound/unmute` | source id | Unmute that microphone |
| POST | `/api/sound/notch` | `{"kind":"mic"\|"output","freqs":[60,120]}` | Notch filter on the laptop mic (new default input) or in front of the default output; `[]` turns it off and restores the old default |
| GET | `/api/caps` | | `{crusher:bool, airpods:bool, eq:bool}`. Fast; the rings poll it |
| POST | `/api/airpods/mode` | `off\|anc\|transparency\|adaptive` | 503 if the AirPods aren't connected |
| POST | `/api/crusher/mode` | `anc\|transparency\|off` | |
| POST | `/api/crusher/bass` | `0-10` | |
| POST | `/api/handoff` | `phone\|laptop` | Who has the AirPods. The laptop steps aside for the phone |
| GET | `/api/track` | | Last seen on the laptop: `{airpods:{connected, last_connected, events:[{t, event:connected\|disconnected, where:"laptop"}]}, crusher:{…}}`. Times are Unix seconds. Polled from BlueZ every 30 s, last 50 per device, stored in `~/.local/state/headphone-panel/track.json` |
| POST | `/api/track/clear` | | Clears that log |

## Sound viewer and "block that sound" (laptop panel)

What the AirPods can't do: their ANC filters are internal, and AAP has no way to tell the ANC which sound to
remove. Their microphones *are* reachable without call mode through the AAP microphone stream (0x58, below),
which the phone app uses; the laptop panel doesn't (yet), and never opens the HFP call mic.

What the panel does instead (`laptop/hp_sound.py`):
- **Viewer**: `parec` captures mono 24 kHz from the laptop mic only (the headphones' call mic is never
  offered: opening it would switch them to HFP); numpy FFTs (4096 points, a frame every 125 ms) feed a
  waterfall + spectrum in the page. It only runs while the page is open and visible. For listening through
  the AirPods themselves, use the phone app's *What your AirPods hear* (below).
- **Pick a sound**: tap (1/3 octave) or drag a range. The panel reports the peak frequency, whether it's a
  tone or spread out, whether it's steady, and a rough label (mains hum, rumble, fan, voices, whine, hiss).
- **Reduce it**: outside sound -> only the AirPods' ANC (buttons for Noise Cancellation / Adaptive at the
  strongest setting; best on steady low sounds). Sound through the laptop -> a PipeWire `bq_notch` filter
  chain on the laptop mic or on the output (optionally with 2x and 3x harmonics for hum).
