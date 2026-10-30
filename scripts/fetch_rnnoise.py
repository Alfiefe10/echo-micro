"""Downloads RNNoise 0.2 (BSD-3) into Echo/src/main/cpp/rnnoise, checks its hash and
applies the ARM/NEON include fix from upstream main (the 0.2 tarball does not build on arm64).

Usage: python3 scripts/fetch_rnnoise.py
"""
import hashlib
import io
import shutil
import tarfile
import urllib.request
from pathlib import Path

URL = "https://github.com/xiph/rnnoise/releases/download/v0.2/rnnoise-0.2.tar.gz"
SHA256 = "90fce4b00b9ff24c08dbfe31b82ffd43bae383d85c5535676d28b0a2b11c0d37"
DEST = Path(__file__).resolve().parent.parent / "android-studio-project/Echo/src/main/cpp/rnnoise"


def main():
    if (DEST / "src" / "denoise.c").exists():
        print(f"Ya está: {DEST}")
        return
    data = urllib.request.urlopen(URL, timeout=120).read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != SHA256:
        raise SystemExit(f"SHA-256 inesperado: {digest}")

    if DEST.exists():
        shutil.rmtree(DEST)
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        for member in tar.getmembers():
            parts = Path(member.name).parts
            if len(parts) < 2 or parts[1] not in ("src", "include", "COPYING"):
                continue
            if not (member.isfile() or member.isdir()):
                continue
            target = DEST.joinpath(*parts[1:])
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(tar.extractfile(member).read())

    neon = DEST / "src" / "vec_neon.h"
    text = neon.read_text()
    text = text.replace('#include "os_support.h"', '#include "common.h"').replace("OPUS_CLEAR(", "RNN_CLEAR(")
    neon.write_text(text)
    print(f"RNNoise 0.2 en {DEST}")


if __name__ == "__main__":
    main()
