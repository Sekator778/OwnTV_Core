#!/usr/bin/env python3
"""Verify pseudolocale packaging in the BUILT APK (docs/internationalization.md 0b).

Enabling ``isPseudoLocalesEnabled`` alone is not proof: ``localeFilters`` can then strip the
generated ``en-rXA`` / ``ar-rXB`` resources, leaving the Phase 3g pseudolocale sweep silently doing
nothing. This script inspects the actual resource table of a built APK via ``aapt2`` and asserts the
full stated invariant, not just pseudolocale presence:

  debug   APK must contain BOTH ``en-rXA`` and ``ar-rXB``, AND no locale configuration outside the
          allowed debug set. ``ar`` is allowed in debug only: generating ``ar-rXB`` causes the
          resource merger to retain the ``ar`` parent qualifier, which is unavoidable and documented.
  release APK must contain NEITHER ``en-rXA`` nor ``ar-rXB``, AND no locale configuration outside the
          allowed release set (``en`` + ``en-rGB`` only).

The "no other production locale leaks" half is enforced here, not just documented: without it a
library locale folder that slipped past ``localeFilters`` would ship unnoticed.

Usage:
    python3 tools/i18n/check_pseudo_locales.py --apk path/to/app.apk --mode debug|release
    [--aapt2 /path/to/aapt2]   # auto-detected from $ANDROID_HOME/build-tools if omitted
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

PSEUDO = ("en-rXA", "ar-rXB")

# The full set of locale configurations the build is allowed to package. Everything else is a leak:
# either a library locale folder that slipped past localeFilters, or a parent qualifier the resource
# merger adds when a child is present. `ar` is in the debug set only — it is the unavoidable parent of
# the `ar-rXB` pseudolocale. `en` (the source) and `en-rGB` (the packaged regional override) ship in
# both variants. `en-rXA`/`ar-rXB` are debug-only.
_ALLOWED_DEBUG = {"en", "en-rGB", "en-rXA", "ar-rXB", "ar"}
_ALLOWED_RELEASE = {"en", "en-rGB"}


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


_LANG = re.compile(r"^[a-z]{2,3}$")
_REGION = re.compile(r"^r[A-Z]{2}$")


def _locale_configs(configs: set[str]) -> set[str]:
    """Extract the locale qualifier (``xx`` or ``xx-rYY``) from each aapt2 configuration line.

    aapt2 prints full configs like ``en-rGB-w720dp-h1280dp-long-mdpi``; the locale is the leading
    language tag optionally followed by an ``rYY`` region. Other leading qualifiers (``v26``,
    ``w720dp``, ``long``, ``port``, ...) are not locales and are ignored. The default unqualified
    config prints as a blank line and is already excluded by the caller.
    """
    out: set[str] = set()
    for c in configs:
        if not c:
            continue
        parts = c.split("-")
        first = parts[0]
        if not _LANG.match(first):
            continue
        locale = first
        if len(parts) > 1 and _REGION.match(parts[1]):
            locale += "-" + parts[1]
        out.add(locale)
    return out


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
    locales = _locale_configs(configs)
    present = {p for p in PSEUDO if p in locales}
    allowed = _ALLOWED_DEBUG if args.mode == "debug" else _ALLOWED_RELEASE
    leaks = locales - allowed
    if args.mode == "debug":
        missing = set(PSEUDO) - present
        if missing:
            print(f"FAIL (debug): pseudolocale(s) missing from APK: {sorted(missing)}")
            print("  isPseudoLocalesEnabled is set but localeFilters stripped them; "
                  "add the debug-only qualifiers via the per-variant API.")
            return 1
        if leaks:
            print(f"FAIL (debug): unexpected locale configuration(s) in APK: {sorted(leaks)}")
            print("  localeFilters did not strip a library locale folder, or a production locale leaked.")
            return 1
        print(f"OK (debug): pseudolocales present {sorted(present)}; no locale leaks")
        return 0
    # release: neither pseudolocale may be present.
    if present:
        print(f"FAIL (release): pseudolocale(s) leaked into release APK: {sorted(present)}")
        print("  The debug-only qualifier add must not reach release variants.")
        return 1
    if leaks:
        print(f"FAIL (release): unexpected locale configuration(s) in release APK: {sorted(leaks)}")
        return 1
    print("OK (release): no pseudolocales in release APK; no locale leaks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
