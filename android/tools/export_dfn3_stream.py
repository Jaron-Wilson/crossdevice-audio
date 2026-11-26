#!/usr/bin/env python3
"""Re-creates android/app/src/main/assets/models/dfn3_stream.onnx (DeepFilterNet3 as a streaming ONNX graph).

What it does: loads the official DeepFilterNet3 weights through the deepfilternet Python package, wraps them in
the single-frame streaming module "torchDF" (github.com/grazder/DeepFilterNet, branch torchDF-changes), and
exports one graph: input_frame[480] (48 kHz), states[45304], atten_lim_db[] -> enhanced_audio_frame[480],
out_states[45304], lsnr[1]. Output delay: 1440 samples (30 ms).

Setup (a fresh virtualenv; CPU-only PyTorch is enough):
    pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu
    pip install deepfilternet==0.5.6 onnx onnxruntime
torchDF needs that branch's versions of df/deepfilternet3.py and df/modules.py (they expose a few sizes the
streaming wrapper reads); this script downloads them, and torch_df_streaming.py, at a pinned commit into a
scratch folder and puts them first on the import path. Newer torchaudio lacks torchaudio.backend, which
df/io.py imports; a stub is provided.

    python3 android/tools/export_dfn3_stream.py out.onnx
"""
import os, sys, tempfile, types, urllib.request

COMMIT = "1097015d53ced78fb234e7d7071a5dd4446e3952"
RAW = f"https://raw.githubusercontent.com/grazder/DeepFilterNet/{COMMIT}/"


def main(out):
    work = tempfile.mkdtemp(prefix="dfn3-export-")
    import df  # the installed deepfilternet package: copy it so two modules can be replaced
    import shutil
    pkg = os.path.join(work, "df")
    shutil.copytree(os.path.dirname(df.__file__), pkg)
    for f in ("deepfilternet3.py", "modules.py"):
        urllib.request.urlretrieve(RAW + "DeepFilterNet/df/" + f, os.path.join(pkg, f))
    urllib.request.urlretrieve(RAW + "torchDF/torch_df_streaming.py", os.path.join(work, "torch_df_streaming.py"))
    try:
        import torchaudio.backend.common  # noqa: F401
    except Exception:
        m = types.ModuleType("torchaudio.backend.common"); m.AudioMetaData = object
        sys.modules["torchaudio.backend"] = types.ModuleType("torchaudio.backend"); sys.modules["torchaudio.backend.common"] = m
    for k in [k for k in sys.modules if k == "df" or k.startswith("df.")]:
        del sys.modules[k]
    sys.path.insert(0, work)
    import torch
    from torch_df_streaming import TorchDFPipeline
    p = TorchDFPipeline(device="cpu")
    m = p.torch_streaming_model
    x = torch.rand(480)
    torch.onnx.export(torch.jit.script(m), (x, p.states, p.atten_lim_db), out,
                      input_names=["input_frame", "states", "atten_lim_db"],
                      output_names=["enhanced_audio_frame", "out_states", "lsnr"], opset_version=14, dynamo=False)
    print("wrote", out)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "dfn3_stream.onnx")
