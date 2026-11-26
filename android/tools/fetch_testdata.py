#!/usr/bin/env python3
"""Test audio for the Filtered hearing tests (never shipped, never committed).

Streams the start of LibriSpeech test-clean (CC BY 4.0, https://www.openslr.org/12) and keeps about a minute of
read speech from a few speakers as 16 kHz 16-bit mono PCM in android/testdata/librispeech/<speaker>.pcm
(gitignored). Only the first ~80 MB of the archive are read. Needs Python 3 and the soundfile package
(pip install soundfile).

    python3 android/tools/fetch_testdata.py
"""
import io, os, sys, tarfile, urllib.request
import numpy as np
import soundfile as sf

URL = "https://www.openslr.org/resources/12/test-clean.tar.gz"
SPEAKERS = {"1320": "M", "2094": "F", "260": "M", "3575": "F", "7127": "M"}
SECONDS = 60
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "testdata", "librispeech")


def main():
    os.makedirs(OUT, exist_ok=True)
    have = {s: [] for s in SPEAKERS}
    budget = 80_000_000
    with urllib.request.urlopen(URL) as r:
        class Capped(io.RawIOBase):
            def __init__(self): self.left = budget
            def readable(self): return True
            def readinto(self, b):
                if self.left <= 0: return 0
                d = r.read(min(len(b), self.left)); self.left -= len(d); b[:len(d)] = d; return len(d)
        try:
            with tarfile.open(fileobj=io.BufferedReader(Capped()), mode="r|gz") as tar:
                for m in tar:
                    parts = m.name.split("/")
                    if not m.isfile() or not m.name.endswith(".flac") or len(parts) < 4: continue
                    spk = parts[2]
                    if spk not in have or sum(len(x) for x in have[spk]) >= SECONDS * 16000: continue
                    x, sr = sf.read(io.BytesIO(tar.extractfile(m).read()), dtype="int16")
                    assert sr == 16000
                    have[spk].append(x)
        except (tarfile.ReadError, EOFError, OSError):
            pass                                     # the archive was cut short on purpose
    for s, xs in have.items():
        if not xs: print(f"speaker {s}: not found in the first part of the archive", file=sys.stderr); continue
        x = np.concatenate(xs)[: SECONDS * 16000]
        x.astype("<i2").tofile(os.path.join(OUT, f"{s}.pcm"))
        print(f"speaker {s} ({SPEAKERS[s]}): {len(x) / 16000:.1f} s")
    with open(os.path.join(OUT, "SOURCE.txt"), "w") as f:
        f.write("Excerpts of LibriSpeech test-clean (Panayotov et al., 2015), CC BY 4.0, https://www.openslr.org/12\n"
                "Speakers (LibriSpeech ids): " + ", ".join(f"{k} ({v})" for k, v in SPEAKERS.items()) + "\n")


if __name__ == "__main__":
    main()
