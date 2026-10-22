#!/usr/bin/env python3
"""Fetch pinned upstream binaries, verify SHA-256, extract only explicitly named model files.

Build-time only. The Android application never downloads code/models or requests INTERNET.
"""
import hashlib
from pathlib import Path
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".cache"
MODEL = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"
AAR_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.14/sherpa-onnx-1.12.14.aar"
AAR_SHA = "5a629a899888cb2760e245d9d5340858b15591aee7fa13644cf57199ff2829b9"
MODEL_URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/{MODEL}.tar.bz2"
MODEL_SHA = "b2f7c89690dc8ce4c6ed6afeab7cd800c36ad1421fb6b6302b4a4b194cf7f35f"
FILES = {
    "encoder.int8.onnx": "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    "decoder.onnx": "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
    "joiner.int8.onnx": "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    "tokens.txt": "tokens.txt",
}


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def fetch(url, expected):
    CACHE.mkdir(exist_ok=True)
    path = CACHE / url.rsplit("/", 1)[1]
    if not path.exists() or digest(path) != expected:
        temp = path.with_suffix(".partial")
        print(f"Downloading {path.name}", flush=True)
        request = urllib.request.Request(url, headers={"User-Agent": "voicetoai-bridge-build/0.1"})
        try:
            with urllib.request.urlopen(request, timeout=120) as response, temp.open("wb") as handle:
                shutil.copyfileobj(response, handle)
            if digest(temp) != expected:
                raise RuntimeError(f"SHA-256 mismatch: {path.name}; refusing changed upstream asset")
            temp.replace(path)
        finally:
            temp.unlink(missing_ok=True)
    return path


def main():
    aar = fetch(AAR_URL, AAR_SHA)
    target = ROOT / "app/libs/sherpa-onnx-1.12.14.aar"
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(aar, target)
    archive = fetch(MODEL_URL, MODEL_SHA)
    assets = ROOT / "app/src/main/assets/kws"
    assets.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:bz2") as tar:
        for filename, source in FILES.items():
            member = tar.getmember(f"{MODEL}/{source}")
            if not member.isfile():
                raise RuntimeError(f"Not a regular file: {source}")
            with tar.extractfile(member) as handle, (assets / filename).open("wb") as output:
                shutil.copyfileobj(handle, output)
    # These are phonetic tokens, not characters or plain ASCII pinyin.
    tokens = {line.split()[0] for line in (assets / "tokens.txt").read_text().splitlines()}
    phrases = [("召唤大模型", "zh ào h uàn d à m ó x íng"), ("你好助手", "n ǐ h ǎo zh ù sh ǒu"), ("小智小智", "x iǎo zh ì x iǎo zh ì")]
    for index, (label, phrase) in enumerate(phrases):
        if not set(phrase.split()) <= tokens:
            raise RuntimeError(f"Unsupported keyword tokens: {phrase}")
        (assets / f"keywords-{index}.txt").write_text(f"{phrase} @{label}\n", encoding="utf-8")
    print("Verified AAR and Chinese KWS assets are ready.")


if __name__ == "__main__":
    main()
