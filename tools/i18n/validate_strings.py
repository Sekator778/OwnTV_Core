#!/usr/bin/env python3
"""Validate Android string resources and the locales.json catalogue.

Owns (docs/internationalization.md 0d / 4c / 4d):
  - locales.json schema and the pickerVisible ⇒ packaged implication.
  - placeholder positional-index parity of every translation against the source (strings, plurals,
    string-array items — all of them), correctly reading placeholders wrapped in ``<xliff:g>``.
  - XML escaping of unescaped ``'`` ``%`` ``&`` ``<`` ``>`` in string bodies.
  - duplicate keys (detected BEFORE overwrite, across all strings*.xml in a directory).
  - non-translatable leakage into translation files, and ``translatable="false"`` placement.
  - empty / unfinished translations (a key present but blank text) for packaged locales.
  - ``<plurals>`` validity, mandatory ``other``, and per-locale CLDR plural-quantity completeness.
  - **Tier 1 coverage enforcement**: every Tier 1, packaged locale at 100% — release-gating.

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
RES = ROOT / "app" / "src" / "main" / "res"

# --- resource parsing ---------------------------------------------------------

# <xliff:g id="count">%1$d</xliff:g> — the placeholder text is the element's *text*, which ET returns
# for it.text. So reading it.text already captures the placeholder. For <string-array><item> entries
# that wrap placeholders in xliff, the same applies. No special handling is needed beyond it.text,
# but we strip xliff wrapper markup defensively in case a translator (or Weblate) emits it as text.


def _flatten_text(el: ET.Element) -> str:
    """The user-visible text of a <string> or <plurals><item>, including placeholders inside xliff:g.

    ElementTree exposes ``<xliff:g>%1$s</xliff:g>`` text via ``el.text`` plus child ``.tail`` values,
    so a naive ``el.text`` alone drops everything after a child element. This concatenates the full
    descendant text so ``%1$s ... %2$d`` inside ``<xliff:g>`` wrappers is captured for placeholder
    parity. We also strip the literal ``<xliff:g ...>`` / ``</xliff:g>`` tags in case Weblate emitted
    them as raw text rather than parsed markup.
    """
    raw = "".join(el.itertext())
    raw = re.sub(r"</?xliff:g[^>]*>", "", raw)
    return raw


def _parse_dir(directory: Path) -> tuple[dict[str, dict], list[str]]:
    """Return (entries, errors) for a res dir.

    entries maps name → {"text":..., "plurals": {qty: text}, "array": [...], "translatable": bool}.
    Duplicates are reported in [errors] rather than silently overwriting the first occurrence.
    Only ``strings*.xml`` is parsed, excluding ``donottranslate.xml``.
    """
    out: dict[str, dict] = {}
    errs: list[str] = []
    if not directory.is_dir():
        return out, errs
    for f in sorted(directory.glob("strings*.xml")):
        if f.name == "donottranslate.xml":
            continue
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError as e:
            errs.append(f"{directory.name}/{f.name}: XML parse error: {e}")
            continue
        for el in root:
            name = el.get("name")
            if not name:
                continue
            if name in out:
                errs.append(f"{directory.name}/{f.name}: duplicate key '{name}'")
                # Keep the first definition; do NOT overwrite, so the duplicate is detectable.
                continue
            if el.tag == "string":
                out[name] = {"text": _flatten_text(el), "translatable": el.get("translatable") != "false"}
            elif el.tag == "plurals":
                qtys: dict[str, str] = {}
                dup_q: set[str] = set()
                for item in el.findall("item"):
                    q = item.get("quantity", "")
                    if q in qtys:
                        dup_q.add(q)
                        continue
                    qtys[q] = _flatten_text(item)
                for q in dup_q:
                    errs.append(f"{directory.name}/{f.name}: plural '{name}' duplicate quantity '{q}'")
                out[name + "#"] = {"plurals": qtys}
            elif el.tag == "string-array":
                out[name + "[]"] = {"array": [_flatten_text(it) for it in el.findall("item")]}
    return out, errs


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

# Positional placeholder: %1$s, %2$d, %3$f ... (also %n$L for DateUtils-style and %n@ for string args).
_POS = re.compile(r"%(\d+)\$[sdifL@]")
# Bare (non-positional) placeholder: %s %d ... but NOT %1$s (already matched by _POS), and NOT %% .
_BARE = re.compile(r"(?<![%0-9])%[sdifL@]")
# Unescaped XML-significant characters in a string body. Android allows ' and % literally only when
# escaped (\', \%) or wrapped in xliff; & < > must be entities. Apostrophes inside words are tolerated
# by the build only when quoted or escaped, so flag a bare ' to surface translator mistakes early.
_UNESCAPED = re.compile(r"(?<!\\)['&<>]")
# A valid printf-style format specifier (positional or bare): %, optional N$, optional flags/width/prec,
# then a conversion char. %% is the escaped literal percent. Any % that is NOT one of these is an
# unescaped percent that breaks the build (e.g. "50% off"). Space is deliberately excluded from the
# flags class so a "%" followed by a space and a word ("50% off") is not misread as a spec.
_FORMAT_SPEC = re.compile(r"%(?:\d+\$)?[\-#+0,(]*\d*(?:\.\d+)?[sdifL@bBhHcCoxXeEgGaA%n]")
_ENTITY = re.compile(r"&(?:[a-zA-Z]+|#x?[0-9]+);")


def _placeholders(text: str) -> list[int]:
    """Positional 1-based indices a value carries; `[]` if none."""
    return [int(m.group(1)) for m in _POS.finditer(text)]


def _bare_placeholders(text: str) -> bool:
    return bool(_BARE.search(text))


def _escaping_ok(text: str) -> bool:
    # Strip valid format specifiers (including %%), then any % that remains is an unescaped percent
    # delimiter — a translator mistake like "50% off" that should be "50%% off".
    stripped = _FORMAT_SPEC.sub("", text)
    if "%" in stripped:
        return False
    # Strip valid XML entities (&amp; &lt; &#123; &#x1F; ...) so their leading & is not flagged.
    stripped = _ENTITY.sub("", stripped)
    return not _UNESCAPED.search(stripped)


def main() -> int:
    fails: list[str] = []

    # --- locales.json schema + implications ------------------------------------
    if not LOCALES_JSON.is_file():
        print("error: tools/i18n/locales.json missing")
        return 1
    try:
        data = json.loads(LOCALES_JSON.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"error: locales.json is not valid JSON: {e}")
        return 1
    required = {"id", "languageTag", "resourceQualifier", "resourceDirectory", "weblateCode",
                "englishName", "endonym", "script", "rtl", "tier", "packaged", "pickerVisible"}
    ids = set()
    qualifier_to_id: dict[str, str] = {}
    for e in data:
        missing = required - e.keys()
        if missing:
            fails.append(f"locales.json entry {e.get('id', '?')} missing fields: {sorted(missing)}")
        if e.get("id") in ids:
            fails.append(f"locales.json duplicate id: {e['id']}")
        ids.add(e.get("id"))
        q = e.get("resourceQualifier")
        if q:
            if q in qualifier_to_id:
                fails.append(f"locales.json duplicate resourceQualifier '{q}' (ids {qualifier_to_id[q]} and {e.get('id')})")
            qualifier_to_id[q] = e.get("id", "?")
        if e.get("pickerVisible") and not e.get("packaged"):
            fails.append(f"locales.json {e.get('id')}: pickerVisible=true requires packaged=true")
        if not isinstance(e.get("rtl"), bool):
            fails.append(f"locales.json {e.get('id')}: rtl must be boolean")
        if not isinstance(e.get("tier"), int) or e["tier"] < 0:
            fails.append(f"locales.json {e.get('id')}: tier must be a non-negative int")
        if not isinstance(e.get("packaged"), bool):
            fails.append(f"locales.json {e.get('id')}: packaged must be boolean")
        if not isinstance(e.get("pickerVisible"), bool):
            fails.append(f"locales.json {e.get('id')}: pickerVisible must be boolean")

    # --- source English validation ---------------------------------------------
    src, src_errs = _parse_dir(RES / "values")
    fails.extend(src_errs)
    if not src:
        # Phase 0 ships empty split files; nothing translatable to validate yet. Still validate the
        # catalogue above so a malformed locales.json fails CI immediately.
        if fails:
            print("i18n validation FAILED:")
            for f in fails:
                print("  " + f)
            return 1
        print("i18n validate: no translatable source keys yet (Phase 0 empty split files); catalogue OK.")
        return 0

    for name, payload in src.items():
        if "plurals" in payload:
            qtys = set(payload["plurals"].keys())
            if "other" not in qtys:
                fails.append(f"source plural {name}: mandatory `other` quantity missing")
            for q, text in payload["plurals"].items():
                if _bare_placeholders(text):
                    fails.append(f"source plural {name}/{q}: bare positional placeholder (use %1$s etc.): {text!r}")
                if not _escaping_ok(text):
                    fails.append(f"source plural {name}/{q}: unescaped XML-significant char in {text!r}")
        elif "array" in payload:
            for i, text in enumerate(payload["array"]):
                if _bare_placeholders(text):
                    fails.append(f"source array {name}[{i}]: bare positional placeholder (use %1$s etc.): {text!r}")
                if not _escaping_ok(text):
                    fails.append(f"source array {name}[{i}]: unescaped XML-significant char in {text!r}")
        elif "text" in payload:
            text = payload["text"]
            # Bare (non-positional) placeholders are forbidden in source so translators can reorder.
            if _bare_placeholders(text):
                fails.append(f"source {name}: bare positional placeholder (use %1$s etc.): {text!r}")
            if not _escaping_ok(text):
                fails.append(f"source {name}: unescaped XML-significant char in {text!r}")
            # A translatable source key must not be empty (a blank display label is a bug, not a stub).
            if payload.get("translatable", True) and text.strip() == "":
                fails.append(f"source {name}: empty translatable string")

    # --- per-locale: coverage, parity, escaping, plurals, empty translations ----
    tier1_coverage_problems: list[str] = []

    for e in data:
        resdir = e["resourceDirectory"]
        if resdir == "values":
            continue  # source language covers itself; validated above
        loc_keys, loc_errs = _parse_dir(RES / resdir)
        fails.extend(loc_errs)
        tag = e["languageTag"]
        rule = _PLURAL_RULES.get(tag)
        is_packaged = e.get("packaged") is True
        for name, psrc in src.items():
            # Skip source keys marked translatable="false" — they never appear in translations.
            if psrc.get("text") is not None and not psrc.get("translatable", True):
                continue
            ploc = loc_keys.get(name)
            if ploc is None:
                if e.get("tier") == 1 and is_packaged:
                    tier1_coverage_problems.append(f"{tag}: {name}")
                continue
            # translatable="false" must NOT leak into translation files.
            if ploc.get("translatable") is False:
                fails.append(f"{tag} {name}: translatable='false' must not appear in a translation file")
            if "plurals" in psrc:
                qloc = set(ploc.get("plurals", {}).keys())
                if "other" not in qloc:
                    fails.append(f"{tag} plural {name}: mandatory `other` missing")
                if rule:
                    for q in rule:
                        if q not in qloc:
                            fails.append(f"{tag} plural {name}: missing required quantity {q}")
                # placeholder parity across every quantity present in both
                for q, stext in psrc["plurals"].items():
                    if q in ploc.get("plurals", {}):
                        sp = _placeholders(stext)
                        lp = _placeholders(ploc["plurals"][q])
                        if sorted(sp) != sorted(lp):
                            fails.append(f"{tag} plural {name}/{q}: placeholder mismatch src {sp} vs loc {lp}")
                        if not _escaping_ok(ploc["plurals"][q]):
                            fails.append(f"{tag} plural {name}/{q}: unescaped XML-significant char")
                        if ploc["plurals"][q].strip() == "":
                            fails.append(f"{tag} plural {name}/{q}: empty translation")
            elif "array" in psrc:
                sarr = psrc["array"]
                larr = ploc.get("array", [])
                if len(sarr) != len(larr):
                    fails.append(f"{tag} array {name}: length mismatch src {len(sarr)} vs loc {len(larr)}")
                else:
                    for i, stext in enumerate(sarr):
                        ltext = larr[i]
                        sp = _placeholders(stext)
                        lp = _placeholders(ltext)
                        if sorted(sp) != sorted(lp):
                            fails.append(f"{tag} array {name}[{i}]: placeholder mismatch src {sp} vs loc {lp}")
                        if not _escaping_ok(ltext):
                            fails.append(f"{tag} array {name}[{i}]: unescaped XML-significant char")
                        if ltext.strip() == "":
                            fails.append(f"{tag} array {name}[{i}]: empty translation")
            elif "text" in psrc:
                stext = psrc["text"]
                if ploc.get("text") is not None:
                    ltext = ploc["text"]
                    sp = _placeholders(stext)
                    lp = _placeholders(ltext)
                    if sorted(sp) != sorted(lp):
                        fails.append(f"{tag} {name}: placeholder mismatch src {sp} vs loc {lp}")
                    if not _escaping_ok(ltext):
                        fails.append(f"{tag} {name}: unescaped XML-significant char in {ltext!r}")
                    # Empty/unfinished translation for a packaged locale is a real gap, not a stub.
                    if is_packaged and ltext.strip() == "":
                        fails.append(f"{tag} {name}: empty translation for a packaged locale")

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
