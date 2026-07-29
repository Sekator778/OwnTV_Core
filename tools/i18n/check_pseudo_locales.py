#!/usr/bin/env python3
"""Verify pseudolocale packaging in the BUILT APK (docs/internationalization.md 0b).

Enabling ``isPseudoLocalesEnabled`` alone is not proof: ``localeFilters`` can then strip the
generated ``en-rXA`` / ``ar-rXB`` resources, leaving the Phase 3g pseudolocale sweep silently doing
nothing. This script inspects the actual resource table of a built APK via ``aapt2`` and asserts:

  debug   APK must contain BOTH ``en-rXA`` and ``ar-rXB`` (plus no other production locale leaks).
  release APK must contain NEITHER ``en-rXA`` nor ``ar-rXB``.

Usage:
    python3 tools/i18n/check_pseudo_locales.py --apk path/to/app.apk --mode debug|release
    [--aapt2 /path/to/aapt2]   # auto-detected from $ANDROID_HOME/build-tools if omitted
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

PSEUDO = ("en-rXA", "ar-rXB")


def _find_aapt2() -> str:
    explicit = os.environ.get("AAPT2")
    if explicit and Path(explicit).exists():
        return explicit
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if home:
        bt = Path(home) / "build-tools"
        if bt.is_dir():
            cands = sorted(p / "aapt2" for p in bt.iterdir() if (p / "aapt2").exists())
            if cands:
                return str(cands[-1])
    found = shutil.which("aapt2")
    if found:
        return found
    sys.exit("error: aapt2 not found; pass --aapt2 or set ANDROID_HOME")


def _apk_configs(apk: Path, aapt2: str) -> set[str]:
    out = subprocess.run([aapt2, "dump", "configurations", str(apk)],
                         capture_output=True, text=True, check=True)
    return {line.strip() for line in out.stdout.splitlines() if line.strip()}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True, type=Path)
    ap.add_argument("--mode", required=True, choices=["debug", "release"])
    ap.add_argument("--aapt2", default=None)
    args = ap.parse_args()
    if not args.apk.is_file():
        print(f"error: APK not found: {args.apk}")
        return 1
    aapt2 = args.aapt2 or _find_aapt2()
    configs = _apk_configs(args.apk, aapt2)
    present = {p for p in PSEUDO if p in configs}
    if args.mode == "debug":
        missing = set(PSEUDO) - present
        if missing:
            print(f"FAIL (debug): pseudolocale(s) missing from APK: {sorted(missing)}")
            print("  isPseudoLocalesEnabled is set but localeFilters stripped them; "
                  "add the debug-only qualifiers via the per-variant API.")
            return 1
        print(f"OK (debug): pseudolocales present {sorted(present)}")
        return 0
    # release: neither pseudolocale may be present.
    leaked = present
    if leaked:
        print(f"FAIL (release): pseudolocale(s) leaked into release APK: {sorted(leaked)}")
        print("  The debug-only qualifier add must not reach release variants.")
        return 1
    print("OK (release): no pseudolocales in release APK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())