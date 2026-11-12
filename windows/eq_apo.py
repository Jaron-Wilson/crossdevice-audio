#!/usr/bin/env python3
r"""Source-side EQ on Windows through Equalizer APO (https://sourceforge.net/projects/equalizerapo/).

Windows has no built-in system-wide EQ, and AirPods have no EQ over AAP, so the EQ runs on the PC's
audio output. Equalizer APO must be installed (and enabled for the AirPods / Crusher output in its
Device Selector). This script does not install anything; it only writes Equalizer APO config:

  <APO config dir>\crossdevice-eq.txt    the filters (rewritten on every change)
  <APO config dir>\config.txt            gets one "Include: crossdevice-eq.txt" line (added once)

Equalizer APO reloads its config by itself when these files change, so there's no audio restart.
The bands and presets come from shared/eq_presets.json, the same model as the laptop and the phone.

Usage:
  python eq_apo.py status
  python eq_apo.py on|off
  python eq_apo.py preset flat|bass|vocal|treble
  python eq_apo.py gains <bass> <lowmid> <mid> <highmid> <treble>     (dB, -12..12)
  python eq_apo.py cycle                                              (next preset, turns EQ on)
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
INCLUDE = "crossdevice-eq.txt"
STATE = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "crossdevice-audio", "eq.json")
APO_TYPE = {"lowshelf": "LSC", "peaking": "PK", "highshelf": "HSC"}


def load_model():
    for p in (os.path.join(HERE, "eq_presets.json"), os.path.join(HERE, "..", "shared", "eq_presets.json")):
        if os.path.exists(p):
            with open(p) as f:
                return json.load(f)
    sys.exit("eq_presets.json not found (copy shared/eq_presets.json next to this script)")


def apo_config_dir():
    """Equalizer APO's config folder, from its registry key, or None if it isn't installed."""
    try:
        import winreg
        for view in (winreg.KEY_WOW64_64KEY, winreg.KEY_WOW64_32KEY):
            try:
                with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\EqualizerAPO", 0,
                                    winreg.KEY_READ | view) as k:
                    path = winreg.QueryValueEx(k, "ConfigPath")[0]
                    if os.path.isdir(path):
                        return path
            except OSError:
                continue
    except ImportError:
        pass
    default = os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "EqualizerAPO", "config")
    return default if os.path.isdir(default) else None


def clamp(model, gains):
    g = [float(x) for x in gains][:len(model["bands"])]
    g += [0.0] * (len(model["bands"]) - len(g))
    return [round(max(model["gain_min"], min(model["gain_max"], x)), 1) for x in g]


def render(model, state):
    """The text of crossdevice-eq.txt for this state."""
    lines = ["# Written by crossdevice-audio windows/eq_apo.py; edits here are overwritten.",
             f"# preset: {state['preset']}  enabled: {state['enabled']}"]
    if not state["enabled"]:
        return "\n".join(lines + ["# EQ off"]) + "\n"
    gains = state["gains"]
    lines.append(f"Preamp: {-max([0.0] + gains):.1f} dB")          # headroom so boosts don't clip
    for i, (b, g) in enumerate(zip(model["bands"], gains), 1):
        lines.append(f"Filter {i}: ON {APO_TYPE[b['type']]} Fc {b['freq']} Hz Gain {g:.1f} dB Q {b['q']}")
    keys = [b["key"] for b in model["bands"]]
    sub = model.get("subsonic")
    if sub and "sub" in keys and gains[keys.index("sub")] > 0:      # cut below the sub-bass boost
        lines.append(f"Filter {len(keys) + 1}: ON HPQ Fc {sub['freq']} Hz Q {sub['q']}")
    return "\n".join(lines) + "\n"


def ensure_include(cfg_dir):
    cfg = os.path.join(cfg_dir, "config.txt")
    try:
        with open(cfg, encoding="utf-8-sig") as f:
            text = f.read()
    except FileNotFoundError:
        text = ""
    if f"Include: {INCLUDE}" not in text:
        with open(cfg, "a", encoding="utf-8") as f:
            f.write(("" if text.endswith("\n") or not text else "\n") + f"Include: {INCLUDE}\n")


def load_state(model):
    s = {"enabled": False, "preset": "flat", "gains": [0.0] * len(model["bands"])}
    try:
        with open(STATE) as f:
            s.update(json.load(f))
    except (OSError, ValueError):
        pass
    s["gains"] = clamp(model, s["gains"])
    return s


def apply(model, state):
    os.makedirs(os.path.dirname(STATE), exist_ok=True)
    with open(STATE, "w") as f:
        json.dump(state, f)
    cfg_dir = apo_config_dir()
    if not cfg_dir:
        print("Equalizer APO is not installed: saved the EQ setting, but nothing is applied. "
              "Install Equalizer APO, enable it for your headphones in its Device Selector, then re-run.")
        return False
    with open(os.path.join(cfg_dir, INCLUDE), "w", encoding="utf-8") as f:
        f.write(render(model, state))
    ensure_include(cfg_dir)
    return True


def main():
    model = load_model()
    presets = {p["key"]: p for p in model["presets"]}
    s = load_state(model)
    a = sys.argv[1:] or ["status"]
    if a[0] == "status":
        print(json.dumps({**s, "apo_config": apo_config_dir()}))
        return
    if a[0] in ("on", "off"):
        s["enabled"] = a[0] == "on"
    elif a[0] == "preset":
        if a[1] not in presets:
            sys.exit(f"unknown preset; one of {', '.join(presets)}")
        s.update(enabled=True, preset=a[1], gains=clamp(model, presets[a[1]]["gains"]))
    elif a[0] == "gains":
        s.update(enabled=True, gains=clamp(model, a[1:]))
        s["preset"] = next((k for k, p in presets.items() if clamp(model, p["gains"]) == s["gains"]), "custom")
    elif a[0] == "cycle":
        keys = list(presets)
        nxt = keys[(keys.index(s["preset"]) + 1) % len(keys)] if s["preset"] in keys else keys[0]
        s.update(enabled=True, preset=nxt, gains=clamp(model, presets[nxt]["gains"]))
    else:
        sys.exit(__doc__)
    ok = apply(model, s)
    print(json.dumps({**s, "applied": ok}))


if __name__ == "__main__":
    main()
