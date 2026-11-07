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

## AirPods battery broadcast (BLE, no connection)

Apple manufacturer data `0x004C`, first byte `0x07`. As a hex string `h` of that data:
- model: `h[6:10]` (`2420` = AirPods Pro 2 USB-C)
- flipped = `int(h[10],16) & 0x02 == 0`
- left = `h[12]` if flipped else `h[13]`; right = the other one; case = `h[15]` (each × 10 %, `f` = unknown)
- charging flags = `h[14]`: case `0b100`; left/right `0b01`/`0b10` (swapped when flipped)

## Panel HTTP API (laptop, 127.0.0.1:8970, published over Tailscale)

| Method | Path | Body | Notes |
|---|---|---|---|
| GET | `/api/state` | | `{crusher:{online,anc,transparency,bass}, airpods:{online,mode,battery,on_phone}}` |
| GET | `/api/caps` | | `{crusher:bool, airpods:bool}`. Fast; the rings poll it |
| POST | `/api/airpods/mode` | `off\|anc\|transparency\|adaptive` | 503 if the AirPods aren't connected |
| POST | `/api/crusher/mode` | `anc\|transparency\|off` | |
| POST | `/api/crusher/bass` | `0-10` | |
| POST | `/api/handoff` | `phone\|laptop` | Who has the AirPods. The laptop steps aside for the phone |
