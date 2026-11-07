#!/usr/bin/env python3
"""Offline speech-to-text for BlackLab Desktop (run by the companion inside its Whisper venv).

    transcribe.py <audio file> [model] [hints]

hints: names, vendors and equipment tags Whisper should expect ("Trane, Mike, RTU-4").

Prints JSON lines: {"progress": 0.42} while it works, then one {"done": true, ...} with the segments.
Uses the NVIDIA GPU when it can (int8), otherwise the CPU. Nothing leaves the computer; the model is
downloaded once into ~/.cache/huggingface.
"""
import glob
import json
import os
import sys


def emit(**kw):
    print(json.dumps(kw), flush=True)


def cuda_lib_dirs() -> list[str]:
    site = os.path.dirname(os.path.dirname(os.__file__))
    pats = [os.path.join(sys.prefix, "lib", "python*", "site-packages", "nvidia", "*", "lib"), os.path.join(site, "site-packages", "nvidia", "*", "lib")]
    return sorted({d for p in pats for d in glob.glob(p)})


# ctranslate2 dlopen()s cuBLAS/cuDNN, so the pip-installed NVIDIA libraries must be on the loader path
# before the process starts: re-exec once with LD_LIBRARY_PATH set.
if not os.environ.get("BLACKLAB_WHISPER_ENV"):
    libs = cuda_lib_dirs()
    env = dict(os.environ, BLACKLAB_WHISPER_ENV="1", LD_LIBRARY_PATH=":".join(libs + [os.environ.get("LD_LIBRARY_PATH", "")]).strip(":"))
    os.execve(sys.executable, [sys.executable, *sys.argv], env)

from faster_whisper import WhisperModel  # noqa: E402


def load(name: str):
    try:
        return WhisperModel(name, device="cuda", compute_type="int8"), "cuda"
    except Exception as e:  # no NVIDIA GPU, no room on it, or libraries missing
        emit(note=f"GPU unavailable ({str(e)[:160]}); using the CPU")
        return WhisperModel(name, device="cpu", compute_type="int8", cpu_threads=os.cpu_count() or 4), "cpu"


def main():
    if len(sys.argv) < 2:
        emit(error="usage: transcribe.py <audio> [model]")
        return 2
    audio, name = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "large-v3-turbo")
    hints = sys.argv[3] if len(sys.argv) > 3 else ""
    opts = {"vad_filter": True, "beam_size": 5, **({"initial_prompt": hints[:600]} if hints else {})}
    model, device = load(name)
    try:
        segments, info = model.transcribe(audio, **opts)
        out = []
        for s in segments:
            out.append({"start": round(s.start, 1), "end": round(s.end, 1), "text": s.text.strip()})
            if info.duration:
                emit(progress=round(min(1.0, s.end / info.duration), 3))
    except RuntimeError as e:
        if device != "cuda":
            raise
        emit(note=f"GPU run failed ({str(e)[:160]}); retrying on the CPU")
        model = WhisperModel(name, device="cpu", compute_type="int8", cpu_threads=os.cpu_count() or 4)
        device = "cpu"
        segments, info = model.transcribe(audio, **opts)
        out = [{"start": round(s.start, 1), "end": round(s.end, 1), "text": s.text.strip()} for s in segments]
    emit(done=True, language=info.language, duration=round(info.duration or 0, 1), device=device, model=name, segments=out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
