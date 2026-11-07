#!/bin/bash
# Make this laptop present Apple's Bluetooth Device ID (vendor 0x004C) so AirPods allow 2 hosts.
# Undo: sudo bash apple-did.sh undo
set -e
CONF=/etc/bluetooth/main.conf
BAK=/etc/bluetooth/main.conf.bak-apple-did
if [ "$1" = undo ]; then
  cp "$BAK" "$CONF"
else
  [ -f "$BAK" ] || cp "$CONF" "$BAK"
  grep -q "^DeviceID = bluetooth:004C" "$CONF" || sed -i "/^\[General\]/a DeviceID = bluetooth:004C:0000:0000" "$CONF"
fi
systemctl restart bluetooth
sleep 3
bluetoothctl show | grep -i modalias
