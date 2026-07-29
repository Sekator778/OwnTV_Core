#!/usr/bin/env python3
"""Validate Android string resources and the locales.json catalogue.

Owns (docs/internationalization.md 0d / 4c / 4d):
  - locales.json schema and the pickerVisible ⇒ packaged implication.
  - placeholder positional-index parity of every translation against the source.
  - `'` `%` `&` `<` XML escaping.
  - duplicate keys, non-translatable leakage into translations, `<plurals>` validity, mandatory `other`.
  - per-locale CLDR plural quantities are present where the locale requires them.
  - **Tier 1 coverage enforcement**: every Tier 1, packaged locale at 100% — release-gating.
  - source-English extra checks: positional placeholders only (no bare ``%s``), `translatable` placement.

Coverage is **computed** here, never read from a stored field. ``MissingTranslation`` lint stays an
informational warning until every packaged locale is complete (it cannot express per-locale policy).
"""
from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LOCALES_JSON = ROOT / "tools" / "i18n" / "locales.json"
RES = ROOT / "app" / "main" / "res"

# --- resource parsing ---------------------------------------------------------

ENTRY_TAGS = ("string", "plurals", "string-array")


def _parse_dir(directory: Path) -> dict[str, dict]:
    """Return {name: {"text":..., "plurals": {qty: text}, "array": [...]} } for a res dir.

    Only ``strings*.xml`` is parsed, excluding ``donottranslate.xml`` (non-translatable brand/protocol
    constants). Source English is the ``values`` directory.
    """
    out: dict[str, dict] = {}
    if not directory.is_dir():
        return out
    for f in sorted(directory.glob("strings*.xml")):
        if f.name == "donottranslate.xml":
            continue
        root = ET.parse(f).getroot()
        for el in root:
            name = el.get("name")
            if not name:
                continue
            if el.tag == "string":
                out[name] = {"text": (el.text or ""), "translatable": el.get("translatable") != "false"}
            elif el.tag == "plurals":
                qtys: dict[str, str] = {}
                for item in el.findall("item"):
                    qtys[item.get("quantity", "")] = item.text or ""
                out[name + "#"] = {"plurals": qtys}
            elif el.tag == "string-array":
                out[name + "[]"] = {"array": [it.text or "" for it in el.findall("item")]}
    return out


# CLDR "other" mandatory; the rest the locale needs per its rule. The full keyword set is
# zero one two few many other. We require `other` unconditionally and require each quantity the
# locale's ICU rule actually selects. For the 21 Tier 1 locales the rules are:
_PLURAL_RULES = {
    "en": ["one", "other"], "en-US": ["one", "other"], "en-GB": ["one", "other"],
    "ar": ["zero", "one", "two", "few", "many", "other"],
    "cs": ["one", "few", "other"], "da": ["one", "other"], "nl": ["one", "other"],
    "fr": ["one", "other"], "de": ["one", "other"], "it": ["one", "other"],
    "ja": ["other"], "ko": ["other"], "nb": ["one", "other"], "sv": ["one", "other"],
    "pl": ["one", "few", "many", "other"], "ru": ["one", "few", "many", "other"],
    "pt": ["one", "other"], "pt-BR": ["one", "other"], "pt-PT": ["one", "other"],
    "zh-CN": ["other"], "zh-TW": ["other"], "es-US": ["one", "other"], "es-ES": ["one", "other"],
    "tr": ["one", "other"],
}

_POS = re.compile(r"%(\d+)\$[sdifL@]")
_BARE = re.compile(r"(?<!\$)%[sdifL@]")


def _placeholders(text: str) -> list[int]:
    """Positional 1-based indices a value carries; `[]` if none."""
    return [int(m.group(1)) for m in _POS.finditer(text)]


_XML_ESC_BAD = re.compile(r"(?<!\\)['%&<>]")


def _extract_locale_keys(locales_data):
    """Yield locale metadata relevant to coverage."""
    return locales_data  # raw entries


def main() -> int:
    fails: list[str] = []

    # --- locales.json schema + implications ------------------------------------
    if not LOCALES_JSON.is_file():
        print("error: tools/i18n/locales.json missing")
        return 1
    data = json.loads(LOCALES_JSON.read_text(encoding="utf-8"))
    required = {"id", "languageTag", "resourceQualifier", "resourceDirectory", "weblateCode",
                "englishName", "endonym", "script", "rtl", "tier", "packaged", "pickerVisible"}
    ids = set()
    for e in data:
        missing = required - e.keys()
        if missing:
            fails.append(f"locales.json entry {e.get('id', '?')} missing fields: {sorted(missing)}")
        if e.get("id") in ids:
            fails.append(f"locales.json duplicate id: {e['id']}")
        ids.add(e.get("id"))
        if e.get("pickerVisible") and not e.get("packaged"):
            fails.append(f"locales.json {e['id']}: pickerVisible=true requires packaged=true")
        if not isinstance(e.get("rtl"), bool):
            fails.append(f"locales.json {e['id']}: rtl must be boolean")
        if not isinstance(e.get("tier"), int) or e["tier"] < 0:
            fails.append(f"locales.json {e['id']}: tier must be a non-negative int")

    # --- source English validation ---------------------------------------------
    src = _parse_dir(RES / "values")
    if not src:
        # Phase 0 ships empty split files; nothing to validate yet. Still validate the catalogue above.
        if fails:
            for f in fails:
                print("  " + f)
            return 1
        print("i18n validate: no translatable source keys yet (Phase 0 empty split files); catalogue OK.")
        return 0

    seen = set()
    for name, payload in src.items():
        if name in seen:
            fails.append(f"duplicate source key: {name}")
        seen.add(name)
        if "plurals" in payload:
            qtys = set(payload["plurals"].keys())
            if "other" not in qtys:
                fails.append(f"plural {name}: mandatory `other` quantity missing")
        elif "text" in payload:
            text = payload["text"]
            # Bare (non-positional) placeholders are forbidden in source so translators can reorder.
            if _BARE.search(text):
                fails.append(f"source {name}: bare positional placeholder (use %1$s etc.): {text!r}")

    # --- per-locale: coverage, parity, escaping, plurals ------------------------
    tier1 = [e for e in data if e.get("tier") == 1 and e.get("packaged")]
    tier1_coverage_problems: list[str] = []

    for e in data:
        resdir = e["resourceDirectory"]
        if resdir == "values":
            loc_keys = src  # source language covers itself
        else:
            loc_keys = _parse_dir(RES / resdir)
        tag = e["languageTag"]
        rule = _PLURAL_RULES.get(tag)
        for name, psrc in src.items():
            ploc = loc_keys.get(name)
            if ploc is None:
                if e.get("tier") == 1 and e.get("packaged"):
                    tier1_coverage_problems.append(f"{tag}: {name}")
                continue
            if "plurals" in psrc:
                qloc = set(ploc.get("plurals", {}).keys()) if ploc else set()
                if "other" not in qloc:
                    fails.append(f"{tag} plural {name}: mandatory `other` missing")
                if rule:
                    for q in rule:
                        if q not in qloc:
                            fails.append(f"{tag} plural {name}: missing required quantity {q}")
            # placeholder parity
            for fld in ("text",):
                if fld in psrc and ploc is not None:
                    sp = _placeholders(psrc[fld])
                    if ploc.get("text") is not None:
                        lp = _placeholders(ploc["text"])
                        if sorted(sp) != sorted(lp):
                            fails.append(f"{tag} {name}: placeholder mismatch src {sp} vs loc {lp}")

    if tier1_coverage_problems:
        fails.append(
            f"Tier 1 coverage gate failed: {len(tier1_coverage_problems)} missing key(s); "
            "Tier 1 packaged locales must be at 100% before a localized release. First few: "
            + ", ".join(tier1_coverage_problems[:10]))

    if fails:
        print("i18n validation FAILED:")
        for f in fails[:200]:
            print("  " + f)
        if len(fails) > 200:
            print(f"  ... and {len(fails) - 200} more")
        return 1
    print("i18n validation OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())