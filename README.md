# crossdevice-audio

One set of headphone controls across a **Linux laptop**, a **Windows PC** and an **Android phone**,
for **AirPods Pro 2** and the **Skullcandy Crusher ANC 2**.

Noise cancelling, transparency, adaptive mode, bass, volume, media keys and battery are all available
from a radial flick menu on both computers, from a web panel, and from a home-screen widget on the phone.
It also has a shared 5-band EQ with a draggable curve, the AirPods' adaptive noise level and settings,
and the Crusher's extra settings (see [Customizing the sound](#customizing-the-sound)).

Two things are optional and off by default: **spatial audio** (Linux laptop only, with experimental head
tracking) and the **laptop mic tools** (a sound viewer and notch filters). Nothing here ever switches the
headset to call (HFP) mode, so audio quality stays the same while you use the controls.

It doesn't need an iPhone, a kernel driver, a Secure Boot change, a USB dongle or a rooted phone.

<p align="center">
  <img src="docs/images/ring-audio-both.png" width="520" alt="The Alt+Q ring with the Audio wedge fanned out">
</p>

## What it does

| Where | How you use it | What you get |
|---|---|---|
| **Windows PC** | `Alt+Q`, flick up, then flick out | Volume, media keys and Crusher modes and bass. AirPods modes go through the laptop. |
| **Linux laptop** | `Alt+Q`, flick up, then flick out | The same ring, plus an audio output switcher. The laptop is what talks to the AirPods. |
| **Web panel** | `https://<laptop>:8970` over Tailscale | Every control with live state and AirPods battery. |
| **Android widget** | Home screen | AirPods modes and battery straight from the phone, with no laptop or Tailscale needed. When the AirPods aren't on the phone it works through the laptop. |

The ring only shows controls that the connected headphones can actually do. AirPods get Adaptive, the
Crusher gets Bass, and with nothing connected only volume and track controls are left:

| Both connected | AirPods only | Crusher only |
|---|---|---|
| <img src="docs/images/ring-audio-both.png" width="260"> | <img src="docs/images/ring-airpods-only.png" width="260"> | <img src="docs/images/ring-crusher-only.png" width="260"> |

### The web panel

The panel is one page served by the laptop (`laptop/panel.html`). It is shown here on a phone-width screen
with example data, in two parts: the headphone controls first, then EQ, spatial audio and the laptop mic tools.

<p align="center">
  <img src="docs/images/panel-phone-1.png" width="360" alt="Web panel, top half: Crusher ANC 2 listening mode, sensory bass and extras; AirPods Pro noise control, Block noise slider (Block more to Hear more, with Block max / Balanced / Hear more / Full ANC), AirPods settings and press-and-hold modes">
  &nbsp;&nbsp;
  <img src="docs/images/panel-phone-2.png" width="360" alt="Web panel, bottom half: the equalizer with its draggable 5-band curve and presets, optional spatial audio (Off / Fixed / Head tracked), and the optional laptop mic tools">
</p>

- **Block noise (AirPods).** The slider runs from *Block more* to *Hear more* and sets the Adaptive level.
  The buttons are shortcuts: *Block max*, *Balanced*, *Hear more* (all Adaptive at different levels) and
  *Full ANC*. The AirPods' own microphones do the listening, so it works with nothing playing through the laptop.
- **Equalizer.** One volume, with automatic headroom so boosts don't clip. On the laptop it is a PipeWire
  smart filter in front of the current output.
- **Spatial audio** is Linux only and optional. Head tracking is experimental.
- **Laptop mic tools** is optional. It uses the laptop's microphone, not the AirPods, to find a hum or whine and
  notch it out of sound that goes through the laptop.
- **Crusher extras** show hear-through on calls and when paused, plus the firmware's feature flags (read-only).

### The Android widget

<p align="center">
  <img src="docs/images/widget-mockup.png" width="360" alt="The Android widget (mockup of the real layout)">
</p>
<p align="center"><sub>The home-screen widget (a mockup of its real layout)</sub></p>

## The interesting parts

**1. AirPods noise control on Windows with no driver.**
AirPods are controlled through Apple's AAP protocol, which runs over classic Bluetooth L2CAP on
channel `0x1001`. Normal Windows programs can't open that kind of connection: Winsock's L2CAP sockets
fail with WSAESOCKTNOSUPPORT (10044) or WSAENETDOWN (10050), and WinRT has no classic L2CAP API at all.
The only way in is a kernel driver, which rules out Secure Boot on stock settings. Instead of fighting
Windows, this project gives the job to another machine:

- The **Linux laptop** presents Apple's Bluetooth Device ID (`DeviceID = bluetooth:004C:0000:0000`).
  AirPods only allow two connections at once to hosts that look like Apple devices.
- The **PC** keeps a normal Windows Device ID and plays the audio. The laptop holds a control-only AAP
  link with its audio profile switched off.
- When you pick ANC in the PC's ring, it asks the laptop over Tailscale, and the laptop sends the
  command to the AirPods.

**2. Pairing order matters, and nobody had written it down.** Two hosts that both present as Apple
share **one** pairing slot, so each new pairing cancels the other's key, and Windows logs "mutual
authentication failed". Copying the link key between hosts doesn't help, because the key is tied to
each host's address. What works is pairing the laptop as Apple first and then the PC as a normal
Windows host. The AirPods keep both pairings and both connections. See
[docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md).

**3. Direct control from a non-rooted Samsung phone.** Android has no public API for classic L2CAP,
and LibrePods documents root as required on Samsung. The widget reaches Android's *hidden* classic
L2CAP socket through [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass), and on a
Galaxy S26 Ultra (One UI 8.5) the AirPods accept the connection. That gives noise modes from the
phone with no root, no laptop and no network.

**4. A phone-to-laptop hand-off.** The phone and the laptop can't share the AirPods: whichever one
joins knocks the other off. The widget listens for the AirPods connecting to and disconnecting from
the phone (`ACL_CONNECTED`/`ACL_DISCONNECTED`) and tells the laptop to let go or take them back. If the
phone can't reach the laptop, the laptop still steps aside on its own when it sees the phone's address
in the AirPods' connected-device list (AAP message `0x2E`).

**5. Battery without a connection.** AirPods broadcast their battery levels to anything nearby over
Bluetooth Low Energy (Apple manufacturer data `0x004C`, type `0x07`). Both the laptop and the phone read
this broadcast, so battery shows no matter which device is playing.

## Customizing the sound

| Control | AirPods Pro 2 | Crusher ANC 2 | Where |
|---|---|---|---|
| **EQ** (5 bands, presets Flat / Bass boost / Vocal / Treble / Custom, draggable curve) | Applied to the audio before it's sent, since AirPods have no EQ over AAP | Same, on top of its sensory bass | Panel (laptop output), Android app and widget (phone output), Windows (Equalizer APO), ring "EQ" wedge |
| Adaptive noise level, shown as the Block noise slider (Block more ↔ Hear more) and the Block max / Balanced / Hear more / Full ANC shortcuts | AAP `0x2E` | | Panel, Android app |
| Conversational Awareness, Personalized Volume, ear detection, one-bud ANC, "Off" in press and hold | AAP `0x28`, `0x26`, `0x0A`, `0x1B`, `0x34` | | Panel, Android app |
| Press-and-hold cycle (which modes) | AAP `0x1A` | | Panel, Android app |
| Sensory bass level and on/off | | `AT.CRUSHER` | Panel, Android app, `crusher_win.py`, ring |
| Transparency level, hear-through on calls / when paused | | `AT.UIAUDIO=transparency,,n`, `atoncall`, `atonpause` (shown only if the firmware answers) | Panel, Android app, `crusher_win.py` |
| Firmware feature flags (Tile Find Me, Spotify One Touch, Remote Control, Latency Mode) | | `AT.FEATURE=info`, read-only | Panel |

Every setting is read back from the device, so the controls show what the headphones report.

**The EQ is source-side.** It runs on whichever device is playing:
- **Laptop:** a PipeWire smart filter in front of the current output (`laptop/hp_eq.py`), with one volume
  and automatic headroom so boosts don't clip.
- **Phone:** Android `audiofx`, with no root. What it can do depends on the phone's audio effects. It tries the global output, which is deprecated and only
  works if the phone honours it, and also attaches to music apps that offer their audio session to
  external EQs. The app says which of these it got. Apps that do neither (most video apps and browsers)
  can't be equalized without root.
- **Windows:** needs [Equalizer APO](https://sourceforge.net/projects/equalizerapo/) config, written by
  `windows/eq_apo.py`. Equalizer APO itself has to be installed.

**Optional extras (laptop only).** Spatial audio plays stereo as two virtual speakers in front of you
(HRTF, `laptop/hp_spatial.py`); head tracking uses the AirPods' motion sensors and is experimental. The laptop
mic tools (`laptop/hp_sound.py`) show what the laptop microphone hears and can notch a hum or whine out of the
mic or out of the laptop's output. Both are off until you turn them on.

**Android 1.2.0** adds *Smart adaptive* (off by default): it uses the phone's activity recognition to move
the AirPods between Adaptive levels as you walk, drive or sit still.

All three use `shared/eq_presets.json`, so a preset sounds the same everywhere.

## Live Translation (Android 1.3.0)

A conversation translator that runs through the AirPods and stays on the phone. Start it from the app's
**Live Translation** button or the widget's **Translate** button. While it runs there is an ongoing
notification with **Stop**.

- **Their speech, in your ears.** The AirPods' microphones hear the other person. The phone recognizes the
  speech, translates it and speaks it to you in the AirPods.
- **Your speech, for them.** When you speak your own language (English by default), the translation is shown
  big on the screen (there is also a full-screen view) and spoken on the phone's own speaker.
- **Who spoke** is decided by ML Kit Language ID on each phrase, with the recognizer's language as the
  tie-break. *Hold while you talk* on the screen marks a phrase as yours if detection gets it wrong.
- **Noise control (optional).** By default the AirPods switch to noise cancelling for the session (or
  *Adaptive, block max*) so the original voice is quieter. The previous mode, and the adaptive level, are put
  back when you stop.
- **On-device only.** Uses the platform's on-device speech recognizer, falling back to the phone's default
  recognizer with "prefer offline" set if the on-device one lacks the language. Translation is ML Kit. Each
  language model is about 30 MB and downloads over Wi-Fi only. You can delete models from the same screen. ML Kit's
  usage-logging backend is removed from the app's manifest. The transcript is kept in memory, and is written
  to a file only when you tap **Save**.

How the audio is routed:
- During a session the app uses `MODE_IN_COMMUNICATION` and sets the AirPods (Bluetooth SCO) as the
  communication device with `setCommunicationDevice`. Their microphones then feed the recognizer, and
  voice-communication audio (the translation for you) plays in them. On stop it calls `clearCommunicationDevice`
  and returns to `MODE_NORMAL`, so music goes back to A2DP quality. Music is paused for the session
  (transient audio focus).
- Your reply is synthesized to a file and played as media with `setPreferredDevice(built-in speaker)`. The app
  checks where Android actually routed it. If the phone forces it to the AirPods anyway, the app switches
  the communication device to the speaker for that reply and then back to the AirPods. That costs about a
  second of mic time per reply.
- Recognition pauses while anything is spoken, and phrases that repeat what was just spoken are dropped. This
  stops the app from translating its own voice. It is half-duplex: speech that overlaps playback is missed.
- Call audio over Bluetooth SCO is narrowband or wideband, so translations in the AirPods sound like a phone call.

## How it fits together

```mermaid
flowchart LR
  subgraph PC["Windows PC"]
    ringW["Alt+Q ring"]
    crW["crusher_win.py<br/>RFCOMM via Winsock"]
  end
  subgraph Laptop["Linux laptop (Apple Device ID)"]
    ringL["Alt+Q ring"]
    panel["panel.py :8970<br/>AAP + RFCOMM + BLE"]
  end
  subgraph Phone["Android phone"]
    widget["Headphones widget"]
  end
  Crusher(["Crusher ANC 2"])
  AirPods(["AirPods Pro 2"])

  ringW -- "Crusher modes, bass" --> crW --> Crusher
  ringW -- "AirPods modes (Tailscale)" --> panel
  ringL --> panel
  widget -- "state and commands (Tailscale)" --> panel
  widget -- "hand-off: phone / laptop" --> panel
  panel -- "AAP over L2CAP 0x1001" --> AirPods
  panel -- "AT over RFCOMM 9" --> Crusher
  widget -. "direct AAP when the phone has them" .-> AirPods
  PC -. "A2DP audio" .-> AirPods
  Phone -. "A2DP audio" .-> AirPods
```

<p align="center"><img src="docs/images/how-it-fits-together.png" width="820" alt="How it fits together (image)"></p>

## Repository layout

| Path | What it is |
|---|---|
| `laptop/panel.py` | The hub: an HTTP API and web page. It keeps the AirPods AAP link open, sends Crusher AT commands, reads BLE battery, and handles the phone hand-off. |
| `laptop/panel.html` | The panel's page: controls, the EQ curve you drag, and the adaptive noise slider. |
| `laptop/hp_eq.py` | The laptop EQ: a PipeWire filter-chain sink, updated live. |
| `shared/eq_presets.json` | The EQ bands and presets, shared by the laptop, the phone and Windows. |
| `windows/eq_apo.py` | The EQ on Windows through Equalizer APO's config (Equalizer APO must be installed). |
| `laptop/headphone-panel.service` | A systemd user service for the panel. |
| `laptop/apple-did.sh` | Switches BlueZ to Apple's Device ID (and back with `undo`). |
| `windows/crusher_win.py` | Crusher control on Windows with no driver: RFCOMM through Winsock `AF_BTH` via ctypes. |
| `ring/audio_ring_excerpt.py` | The Audio part of the Alt+Q ring: fan geometry, capability filtering, and the Windows connection check. |
| `android/` | The home-screen widget app (Kotlin, no AndroidX), including Live Translation (`LiveTranslateService.kt`, `TranslateActivity.kt`, and the testable decisions in `TranslateLogic.kt` / `TranslateSession.kt`). |
| `docs/HOW-IT-WORKS.md` | The multi-device findings, with the packets that prove them. |
| `docs/PROTOCOL.md` | The Crusher AT commands, the AAP packets, the BLE battery format and the panel API. |

## Setup

### Laptop (Linux, BlueZ 5.x)

```bash
sudo bash laptop/apple-did.sh                 # Modalias should read bluetooth:v004Cp0000d0000
# re-pair the AirPods on the laptop (the AirPods remember a host's Device ID from pairing time)
mkdir -p ~/.config/headphone-panel ~/.local/share/headphone-panel ~/.config/systemd/user
cp laptop/config.example.json ~/.config/headphone-panel/config.json   # fill in your addresses
cp laptop/panel.py laptop/panel.html laptop/hp_eq.py shared/eq_presets.json ~/.local/share/headphone-panel/
cp laptop/headphone-panel.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now headphone-panel
pactl set-card-profile bluez_card.<AIRPODS_MAC_WITH_UNDERSCORES> off   # control only, no audio
tailscale serve --bg --https 8970 http://127.0.0.1:8970               # reach it from the PC and phone
```

### Windows PC

Pair the AirPods **after** the laptop's Apple pairing, and leave the PC's Device ID at its default.
For the Crusher, set the `CRUSHER_MAC` environment variable and run `python windows\crusher_win.py anc on`.
No driver and no admin rights are needed. For the EQ, install Equalizer APO, tick your headphones in its
Device Selector, then run `python windows\eq_apo.py preset bass` (keep `shared\eq_presets.json` where
the script can find it: next to it, or at `..\shared\`).

### Android phone

Download the APK from [Releases](../../releases), or build it yourself with `cd android && ./gradlew assembleRelease`.
Install it, open **Headphones**, allow **Nearby devices** and notifications (the EQ runs as a small
foreground service while it's on), pick your AirPods from your paired devices, and optionally enter your
laptop panel URL. Then add the **Headphones** widget to your home screen. The app screen has the EQ
curve and the AirPods and Crusher settings. The widget shows a preview of the curve and a button that
cycles through the presets.
The APK has nothing about any particular device built in.

## What didn't work

- **A kernel driver for L2CAP on Windows.** It's possible, but loading one needs test-signing (which
  means turning Secure Boot off) or Microsoft attestation signing. That's too much to ask of anyone
  who just wants to use it.
- **Winsock and WinRT L2CAP:** `SOCK_SEQPACKET` fails with 10044 and `SOCK_STREAM` with 10050.
- **Copying the laptop's link key into the Windows registry:** the AirPods still reject it.
- **Phone and laptop connected at the same time:** whichever joins second kicks the other within a
  second. The PC and laptop pair coexists, but the phone and laptop pair doesn't, so there's a hand-off
  instead.
- **Three hosts:** AirPods hold two connections at most.

## Build notes

Built in one night (about 5.5 hours) across three devices and two headsets. Seven routes were tried
for AirPods on Windows before the two-device relay worked. The whole thing is now about 5,000 lines of
code (roughly 2,500 Python, 2,200 Kotlin and a 290-line web panel page), and none of it needs root, a driver or a Secure Boot change.

<p align="center"><img src="docs/images/by-the-numbers.png" width="760" alt="By the numbers"></p>

## Credits

- [LibrePods](https://github.com/kavishdevar/librepods): AAP research, the Device ID trick and the control-command ids.
- [apple-wireshark](https://github.com/pabloaul/apple-wireshark): AAP opcode names.
- [OpenPods](https://github.com/adolfintel/OpenPods): the BLE proximity-broadcast battery layout.
- [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass).

Not affiliated with Apple or Skullcandy. AirPods is a trademark of Apple Inc.
