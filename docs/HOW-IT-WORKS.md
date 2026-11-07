# How the multi-device AirPods setup works

These notes come from testing AirPods Pro 2 (USB-C, model `0x2024`) against three hosts: a Windows 11 PC
(Realtek/MediaTek RZ608 radio, stock Microsoft stack), a Linux laptop (BlueZ 5.85, PipeWire/WirePlumber 0.5)
and a Galaxy S26 Ultra (One UI 8.5). Every claim below was observed, with the packet or log line shown.

## 1. Windows can't open the AirPods' control channel

AAP, Apple's accessory protocol, runs over **classic L2CAP on channel `0x1001`**. From normal user-mode Windows code:

| Attempt | Result |
|---|---|
| `socket(AF_BTH, SOCK_SEQPACKET, BTHPROTO_L2CAP)` | `WSAESOCKTNOSUPPORT` (10044) |
| `socket(AF_BTH, SOCK_STREAM, BTHPROTO_L2CAP)` + `connect(0x1001)` | `WSAENETDOWN` (10050) |
| WinRT `Windows.Devices.Bluetooth` | Only RFCOMM, LE GATT and adverts; no classic L2CAP |
| Control check: RFCOMM to the AirPods' Hands-Free channel 7 | `WSAEADDRNOTAVAIL` (10049). The radio is reachable, but Windows' own HFP driver holds that channel |

So user-mode Windows can't reach the channel. A kernel profile driver could, but loading it needs
test-signing (Secure Boot off) or Microsoft attestation.

## 2. AirPods allow two hosts, but only for "Apple" hosts

Every Bluetooth host publishes a Device ID record, which includes a vendor. AirPods enable their two-host mode based on it:

```
# /etc/bluetooth/main.conf, [General]
DeviceID = bluetooth:004C:0000:0000        # vendor 0x004C = Apple, source 0x0001 = Bluetooth SIG
```

After `systemctl restart bluetooth`, `bluetoothctl show` reports `Modalias: bluetooth:v004Cp0000d0000`.
The AirPods record a host's Device ID **when they pair**, so re-pair after changing it.

Windows has a documented registry equivalent (`HKLM\SYSTEM\CurrentControlSet\Services\BTHPORT\Parameters`,
values `DIDVendorID` and `DIDVendorIDSource`). It takes effect after the radio is turned off and on, with
no reboot. As section 3 shows, though, it isn't needed and actually gets in the way.

## 3. Pairing order: two Apple hosts share one slot

| Step | What happened |
|---|---|
| Laptop paired as Apple, then the PC paired as Apple | The laptop's link key stopped working (`br-connection-key-missing`). |
| The laptop re-paired | Windows: *"The mutual authentication between the local Bluetooth adapter and a device … failed"* (event 16). |
| The laptop's key copied into `BTHPORT\Parameters\Keys\<pc>\<airpods>` | Still rejected. The key is tied to the host's address. |
| **Laptop paired as Apple, then the PC paired with its *normal* Windows Device ID** | **Both pairings kept, and both hosts connected at once.** |

With that setup, the AirPods' connected-devices report (AAP opcode `0x2E`) lists both hosts:

```
04 00 04 00 2E 00 01 00 02 | <pc-mac> 01 04 | <laptop-mac> 02 02
                         ^count   PC (Windows)         laptop (Apple ID)
```

The PC kept playing music while the laptop sent `04 00 04 00 09 00 0D 02 00 00 00` (ANC), and the mode
changed in the user's ears with no dropout.

Three more things matter for this setup:

- **The laptop must not play audio.** `pactl set-card-profile bluez_card.<mac> off`, which WirePlumber
  remembers, keeps it control-only. With its A2DP profile active, any system sound pulls the AirPods
  away from the PC.
- **Don't reconnect quickly.** A panel that retried every 5 s kept stealing the link from the PC
  (you hear the AirPods' connect chime over and over). Retrying every 60 s, or only when the laptop has
  been handed the AirPods, fixes it.
- **While another host is playing, the AirPods don't send mode echoes or battery to the laptop.**
  Commands still work, and battery comes from the BLE broadcast instead (section 6).

## 4. The phone and the laptop can't share

Repeated twice, including after re-pairing the phone after the laptop:

```
05:28:15  0x2E: 2 devices | <phone-mac> (phone) | <laptop-mac> (laptop)
05:28:16  laptop disconnected                       <- one second after the phone joined
05:29:18  laptop rejoined -> 0x2E: 1 device (laptop) <- and the phone's music stopped
```

The PC and laptop pair coexists, but the phone and laptop pair doesn't. Making the laptop control-only
didn't change that, and neither did AVRCP, because it also connects in the working PC case. So the
phone and laptop **hand off** instead:

- the widget catches `ACL_CONNECTED` / `ACL_DISCONNECTED` for the AirPods and `POST`s `phone` or `laptop`
  to the panel's `/api/handoff`;
- while the phone has the AirPods, the panel closes its AAP link, disconnects, and stops retrying;
- if the phone couldn't reach the panel, the panel still steps aside when the phone's address
  appears in a `0x2E` report.

## 5. Direct control from a non-rooted phone

Android's public API only opens LE L2CAP channels (CoC). The classic socket still exists as a hidden
API (`BluetoothSocket(device, TYPE_L2CAP=3, auth, encrypt, port=0x1001, …)`), and a normal app can
reach it through `HiddenApiBypass`. The open question was whether the phone's Bluetooth stack would
accept the AirPods' channel configuration. LibrePods lists root as required below Android 16 QPR3.
**On a Galaxy S26 Ultra (One UI 8.5) it connected**, and the handshake, the feature flags and the
noise-mode command all work. The widget then keeps its highlight in sync by reading back the
`09 00 0D <mode>` echo.

## 6. Battery from the BLE broadcast

AirPods advertise Apple manufacturer data (`0x004C`) of type `0x07` ("proximity pairing"). Bytes 3–4
hold the model (`24 20` for AirPods Pro 2 USB-C). Nibbles in bytes 5–7 hold left, right and case
battery in tens of percent (15 means unknown), along with charging flags. The left and right nibbles
swap depending on a "flipped" bit (the OpenPods layout). Neither reader needs a connection, so battery
shows wherever the AirPods are.
