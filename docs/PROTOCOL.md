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
| Battery (AirPods → host) | `04 00 04 00 04 00 <count> [<type> 01 <level> <status> 01]…`. Type: 2 left, 4 right, 8 case |
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
| Linux laptop | `laptop/hp_eq.py` starts a `pipewire -c` filter-chain. It creates a "Headphones EQ" sink (`hp_eq.input`) and makes it the default, and the filter's output goes to the sink that was the default (or to `eq_target` in config.json). Gain changes are applied live with `pw-cli set-param`. Turning it off restores the old default |
| Android | `EqService` (a foreground service) uses `audiofx`. It tries DynamicsProcessing on the global session 0, then Equalizer on session 0, and also attaches to any player that broadcasts `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`. The biquad curve is sampled into 24 DynamicsProcessing bands, or into the phone's Equalizer bands |
| Windows | `windows/eq_apo.py` writes `crossdevice-eq.txt` (Preamp, LSC, PK and HSC filters) into Equalizer APO's config folder and includes it from `config.txt`. **Equalizer APO must be installed**; the script installs nothing |

## AirPods battery broadcast (BLE, no connection)

Apple manufacturer data `0x004C`, first byte `0x07`. As a hex string `h` of that data:
- model: `h[6:10]` (`2420` = AirPods Pro 2 USB-C)
- flipped = `int(h[10],16) & 0x02 == 0`
- left = `h[12]` if flipped else `h[13]`; right = the other one; case = `h[15]` (each × 10 %, `f` = unknown)
- charging flags = `h[14]`: case `0b100`; left/right `0b01`/`0b10` (swapped when flipped)

## Panel HTTP API (laptop, 127.0.0.1:8970, published over Tailscale)

| Method | Path | Body | Notes |
|---|---|---|---|
| GET | `/api/state` | | `{crusher:{online,anc,transparency,bass,bass_on,extras}, airpods:{online,mode,battery,on_phone,settings}, eq:{…}}`. A setting is `null` until the device reports it |
| GET | `/api/eq` | | `{enabled,preset,gains,target,running,available,error,bands,presets,gain_min,gain_max}` |
| POST | `/api/eq` | `{"enabled":true,"preset":"bass"}` or `{"gains":[6,2,0,0,0]}` | Any subset |
| POST | `/api/eq/cycle` | | Next preset, and turns the EQ on (the ring's EQ wedge) |
| POST | `/api/airpods/setting` | `{"name":"adaptive_level","value":30}` | Names: `adaptive_level` (raw 0–100), `conversational_awareness`, `personalized_volume`, `ear_detection`, `one_bud_anc`, `allow_off` (bool), `press_hold_modes` (list of `off\|anc\|transparency\|adaptive`) |
| POST | `/api/crusher/setting` | `{"name":"transparency_level","value":40}` | `bass_on`, `transparency_level` (0–100), `at_on_call`, `at_on_pause` (bool), `anc_filter` (int or `"off"`) |
| GET | `/api/caps` | | `{crusher:bool, airpods:bool, eq:bool}`. Fast; the rings poll it |
| POST | `/api/airpods/mode` | `off\|anc\|transparency\|adaptive` | 503 if the AirPods aren't connected |
| POST | `/api/crusher/mode` | `anc\|transparency\|off` | |
| POST | `/api/crusher/bass` | `0-10` | |
| POST | `/api/handoff` | `phone\|laptop` | Who has the AirPods. The laptop steps aside for the phone |
