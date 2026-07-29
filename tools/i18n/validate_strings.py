#!/usr/bin/env python3
"""Validate Android string resources and the locales.json catalogue.

Owns (docs/internationalization.md 0d / 4c / 4d):
  - locales.json schema: required fields, type checks, id/tag/qualifier uniqueness, qualifier
    validity, directory/qualifier correspondence, pickerVisible ⇒ packaged, stored coverage
    rejection, exact Tier 1 membership.
  - placeholder positional-index parity of every translation against the source (strings, plurals,
    string-array items — all of them), correctly reading placeholders wrapped in ``<xliff:g>``.
    Recognises full printf format specifiers including flags/width/precision (``%1$.2f``).
  - XML escaping checked on the RAW XML source (not ElementTree-decoded text) so valid entities
    like ``&amp;`` and ``&lt;`` are not false-positive'd.
  - duplicate keys (strings, plurals, arrays — all detected BEFORE overwrite).
  - non-translatable leakage into translation files.
  - empty / unfinished translations (blank text or identical-to-source for Tier 1 locales).
  - translation-only keys (keys in a translation file that don't exist in source, including leaked
    donottranslate keys).
  - ``<plurals>`` validity, mandatory ``other``, per-locale CLDR plural-quantity completeness, and
    placeholder parity for EVERY translation quantity (including locale-specific forms like Arabic
    zero/two/few/many that don't exist in the source).
  - **Tier 1 coverage enforcement**: every Tier 1, packaged locale at 100% — release-gating.

Coverage is **computed** here, never read from a stored field.
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

# Full printf-style format specifier: %, optional N$, optional flags [-#+0,(], optional width,
# optional .precision, then a conversion char. %% is the escaped literal percent. This covers
# %1$s, %2$d, %1$.2f, %02d, %-10s, etc. Space is excluded from flags so "50% off" is not matched.
_FMT = re.compile(r"%(?:\d+\$)?[\-#+0,(]*\d*(?:\.\d+)?[sdifL@bBhHcCoxXeEgGaA%n]")
# Positional only: %1$s, %2$.2f ... — captures the 1-based index for parity checking.
_POS = re.compile(r"%(\d+)\$[\-#+0,(]*\d*(?:\.\d+)?[sdifL@bBhHcCoxXeEgGaA]")
# Bare (non-positional): %s %d %f ... — forbidden in source so translators can reorder.
_BARE = re.compile(r"%(?!\d+\$)[\-#+0,(]*\d*(?:\.\d+)?[sdifL@bBhHcCoxXeEgGaA]")
# Valid XML entity: &amp; &lt; &#123; &#x1F; ...
_ENTITY = re.compile(r"&(?:[a-zA-Z]+|#x?[0-9]+);")
# xliff:g child tags inside string bodies (the only child element allowed in Android string resources).
_XLIFF_TAG = re.compile(r"</?xliff:g[^>]*>")
# Raw element body extractor: <string ...>BODY</string> or <item ...>BODY</item>, non-greedy, DOTALL.
_RAW_BODY = re.compile(r"<(string|item)\b([^>]*)>(.*?)</\1>", re.DOTALL)


def _flatten_text(el: ET.Element) -> str:
    """Decoded text of a <string>/<item> for placeholder and coverage checks (entities resolved)."""
    raw = "".join(el.itertext())
    raw = _XLIFF_TAG.sub("", raw)
    return raw


def _parse_dir(directory: Path) -> tuple[dict[str, dict], list[str]]:
    """Return (entries, errors) for a res dir.

    entries maps a SUFFIXED key → {"text":..., "plurals": {qty: text}, "array": [...],
    "translatable": bool, "kind": "string"|"plurals"|"array"}.
    Suffixes: ``""`` for <string>, ``#`` for <plurals>, ``[]`` for <string-array>.
    Duplicates are reported in [errors] rather than silently overwriting.
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
            if el.tag == "string":
                key = name
            elif el.tag == "plurals":
                key = name + "#"
            elif el.tag == "string-array":
                key = name + "[]"
            else:
                continue
            if key in out:
                errs.append(f"{directory.name}/{f.name}: duplicate key '{name}' ({el.tag})")
                continue  # keep first definition; do NOT overwrite
            if el.tag == "string":
                out[key] = {"text": _flatten_text(el), "translatable": el.get("translatable") != "false",
                            "kind": "string"}
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
                out[key] = {"plurals": qtys, "kind": "plurals"}
            elif el.tag == "string-array":
                out[key] = {"array": [_flatten_text(it) for it in el.findall("item")], "kind": "array"}
    return out, errs


# --- escaping (checked on RAW XML, not decoded text) --------------------------

def _check_escaping(file_path: Path) -> list[str]:
    """Check raw XML bodies for unescaped characters that ElementTree would decode and hide.

    ElementTree's ``itertext()`` resolves ``&amp;``→``&`` and ``&lt;``→``<`` before any check can
    run, so a valid ``Fish &amp; Chips &lt;3`` would look like ``Fish & Chips <3`` and be wrongly
    rejected. This function reads the raw file text and checks the body *between the tags*, where
    entities are still encoded. ``&`` and ``<`` as bare characters in the raw body would have caused
    an XML parse error (caught by ET.parse above), so they are not re-checked here; the remaining
    Android-specific escapes are apostrophe (``'``→``\\'``) and percent (``%``→``%%`` or a format spec).
    """
    errs: list[str] = []
    text = file_path.read_text(encoding="utf-8")
    for m in _RAW_BODY.finditer(text):
        tag = m.group(1)
        attrs = m.group(2)
        body = m.group(3)
        name_m = re.search(r'name="([^"]*)"', attrs)
        name = name_m.group(1) if name_m else "?"
        qty_m = re.search(r'quantity="([^"]*)"', attrs)
        label = f"{name}/{qty_m.group(1)}" if qty_m else name
        # Strip xliff:g child tags (valid markup inside string bodies).
        b = _XLIFF_TAG.sub("", body)
        # Strip valid XML entities so their & is not flagged.
        b = _ENTITY.sub("", b)
        # Strip valid format specifiers (including %%).
        b = _FMT.sub("", b)
        # Strip escaped apostrophes and quotes.
        b = b.replace("\\'", "").replace('\\"', "")
        # Any remaining ', % is an unescaped character that will break the Android build.
        bad = []
        if "'" in b:
            bad.append("unescaped apostrophe (use \\' or &apos;)")
        if "%" in b:
            bad.append("unescaped percent (use %% or a format specifier)")
        if bad:
            errs.append(f"{file_path.parent.name}/{file_path.name} {label}: {'; '.join(bad)} in {body.strip()[:60]!r}")
    return errs


# --- plural rules -------------------------------------------------------------

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


def _placeholders(text: str) -> list[int]:
    return [int(m.group(1)) for m in _POS.finditer(text)]


def _has_bare(text: str) -> bool:
    # _BARE matches format specs WITHOUT N$; _FMT matches all (including %%). If _FMT matches
    # something _BARE doesn't, that something is either positional or %%, both fine. If _BARE
    # matches, it's a bare %s/%d etc. — forbidden in source.
    return bool(_BARE.search(text))


# --- locales.json catalogue validation ----------------------------------------

_REQUIRED_FIELDS = {"id", "languageTag", "resourceQualifier", "resourceDirectory", "weblateCode",
                    "englishName", "endonym", "script", "rtl", "tier", "packaged", "pickerVisible"}

# Exact set of Tier 1 language tags the catalogue must contain (docs/internationalization.md 4d).
_EXPECTED_TIER1_TAGS = {
    "en-US", "ar", "pt-BR", "pt-PT", "zh-CN", "zh-TW", "cs", "da", "nl", "fr", "de", "it",
    "ja", "ko", "nb", "pl", "ru", "es-US", "es-ES", "sv", "tr",
}

# Valid Android resource qualifier forms for locales: xx, xx-rYY, xx-rYYY (3-digit? no, region is 2),
# xx-Script (4 letters). We allow: [a-z]{2,3}, [a-z]{2}-r[A-Z]{2}, [a-z]{2}-[A-Z][a-z]{3}.
_QUAL_RE = re.compile(r"^[a-z]{2,3}(?:-r[A-Z]{2}|-[A-Z][a-z]{3})?$")


def _validate_catalogue(data: list) -> list[str]:
    fails: list[str] = []
    ids: set[str] = set()
    tags: set[str] = set()
    qualifiers: set[str] = set()
    dirs: set[str] = set()
    tier1_tags: set[str] = set()
    for e in data:
        eid = e.get("id", "?")
        missing = _REQUIRED_FIELDS - e.keys()
        if missing:
            fails.append(f"locales.json entry {eid}: missing fields: {sorted(missing)}")
        if eid in ids:
            fails.append(f"locales.json duplicate id: {eid}")
        ids.add(eid)
        tag = e.get("languageTag", "")
        if tag in tags:
            fails.append(f"locales.json duplicate languageTag: {tag}")
        tags.add(tag)
        q = e.get("resourceQualifier", "")
        if q:
            if not _QUAL_RE.match(q):
                fails.append(f"locales.json {eid}: invalid resourceQualifier '{q}'")
            if q in qualifiers:
                fails.append(f"locales.json duplicate resourceQualifier '{q}'")
            qualifiers.add(q)
        d = e.get("resourceDirectory", "")
        if d:
            if d in dirs:
                fails.append(f"locales.json duplicate resourceDirectory '{d}'")
            dirs.add(d)
            # Directory/qualifier correspondence: values-<qualifier> (or "values" for the source).
            if d != "values":
                expected = "values-" + q
                if d != expected:
                    fails.append(f"locales.json {eid}: resourceDirectory '{d}' should be '{expected}'")
        # Stored coverage is forbidden — coverage is always computed.
        if "coverage" in e:
            fails.append(f"locales.json {eid}: 'coverage' field must not be stored (it is computed)")
        if e.get("pickerVisible") and not e.get("packaged"):
            fails.append(f"locales.json {eid}: pickerVisible=true requires packaged=true")
        for fld in ("rtl", "packaged", "pickerVisible"):
            if not isinstance(e.get(fld), bool):
                fails.append(f"locales.json {eid}: {fld} must be boolean")
        if not isinstance(e.get("tier"), int) or e["tier"] < 0:
            fails.append(f"locales.json {eid}: tier must be a non-negative int")
        # Weblate code must be non-empty and look like a Weblate code (xx or xx_YY).
        wc = e.get("weblateCode", "")
        # Weblate codes: xx, xx_YY (region), xx_Hans (script), xx_419 (UN M.49 numeric region).
        if not wc or not re.match(r"^[a-z]{2,3}(?:_[A-Za-z0-9]{2,4})?$", wc):
            fails.append(f"locales.json {eid}: invalid weblateCode '{wc}'")
        if e.get("tier") == 1:
            tier1_tags.add(tag)
    # Exact Tier 1 membership.
    if tier1_tags != _EXPECTED_TIER1_TAGS:
        missing = _EXPECTED_TIER1_TAGS - tier1_tags
        extra = tier1_tags - _EXPECTED_TIER1_TAGS
        if missing:
            fails.append(f"locales.json: missing Tier 1 languages: {sorted(missing)}")
        if extra:
            fails.append(f"locales.json: unexpected Tier 1 languages: {sorted(extra)}")
    return fails


# --- main ---------------------------------------------------------------------

def main() -> int:
    fails: list[str] = []

    # --- locales.json ----------------------------------------------------------
    if not LOCALES_JSON.is_file():
        print("error: tools/i18n/locales.json missing")
        return 1
    try:
        data = json.loads(LOCALES_JSON.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"error: locales.json is not valid JSON: {e}")
        return 1
    fails.extend(_validate_catalogue(data))

    # --- source English --------------------------------------------------------
    src, src_errs = _parse_dir(RES / "values")
    fails.extend(src_errs)
    # Escaping checks run on raw XML for every strings*.xml file in values/.
    for f in sorted((RES / "values").glob("strings*.xml")):
        if f.name == "donottranslate.xml":
            continue
        fails.extend(_check_escaping(f))

    if not src:
        if fails:
            print("i18n validation FAILED:")
            for f in fails:
                print("  " + f)
            return 1
        print("i18n validate: no translatable source keys yet (Phase 0 empty split files); catalogue OK.")
        return 0

    # Source key-level checks.
    for key, payload in src.items():
        name = key.rstrip("#[]")
        kind = payload["kind"]
        if kind == "plurals":
            qtys = set(payload["plurals"].keys())
            if "other" not in qtys:
                fails.append(f"source plural {name}: mandatory `other` quantity missing")
            for q, text in payload["plurals"].items():
                if _has_bare(text):
                    fails.append(f"source plural {name}/{q}: bare placeholder (use %1$s etc.): {text!r}")
                if text.strip() == "":
                    fails.append(f"source plural {name}/{q}: empty string")
        elif kind == "array":
            for i, text in enumerate(payload["array"]):
                if _has_bare(text):
                    fails.append(f"source array {name}[{i}]: bare placeholder (use %1$s etc.): {text!r}")
                if text.strip() == "":
                    fails.append(f"source array {name}[{i}]: empty string")
        elif kind == "string":
            text = payload["text"]
            if _has_bare(text):
                fails.append(f"source {name}: bare placeholder (use %1$s etc.): {text!r}")
            if payload.get("translatable", True) and text.strip() == "":
                fails.append(f"source {name}: empty translatable string")

    # --- per-locale ------------------------------------------------------------
    tier1_coverage_problems: list[str] = []

    for e in data:
        resdir = e["resourceDirectory"]
        if resdir == "values":
            continue
        loc_keys, loc_errs = _parse_dir(RES / resdir)
        fails.extend(loc_errs)
        for f in sorted((RES / resdir).glob("strings*.xml")):
            if f.name == "donottranslate.xml":
                continue
            fails.extend(_check_escaping(f))
        tag = e["languageTag"]
        rule = _PLURAL_RULES.get(tag)
        is_packaged = e.get("packaged") is True
        is_tier1 = e.get("tier") == 1

        # Translation-only keys: keys in the translation that don't exist in source (including leaked
        # donottranslate keys). Every translation key must have a source counterpart.
        for lkey in loc_keys:
            if lkey not in src:
                lname = lkey.rstrip("#[]")
                fails.append(f"{tag}: translation-only key '{lname}' has no source counterpart")

        for skey, psrc in src.items():
            # Skip source keys marked translatable="false" — they must NOT appear in translations.
            if psrc["kind"] == "string" and not psrc.get("translatable", True):
                continue
            ploc = loc_keys.get(skey)
            if ploc is None:
                if is_tier1 and is_packaged:
                    tier1_coverage_problems.append(f"{tag}: {skey.rstrip('#[]')}")
                continue
            # translatable="false" must NOT leak into translation files.
            if ploc.get("translatable") is False:
                fails.append(f"{tag} {skey.rstrip('#[]')}: translatable='false' must not appear in a translation file")
            if psrc["kind"] == "plurals":
                qloc = set(ploc.get("plurals", {}).keys())
                if "other" not in qloc:
                    fails.append(f"{tag} plural {skey.rstrip('#[]')}: mandatory `other` missing")
                if rule:
                    for q in rule:
                        if q not in qloc:
                            fails.append(f"{tag} plural {skey.rstrip('#[]')}: missing required quantity {q}")
                # Placeholder parity for EVERY quantity in the translation, not just those in source.
                # Locale-specific quantities (Arabic zero/two/few/many) must carry the same placeholders
                # as the source. Compare against the source `other` quantity for quantities not in source.
                src_other_ph = _placeholders(psrc["plurals"].get("other", ""))
                for q, ltext in ploc.get("plurals", {}).items():
                    sp = _placeholders(psrc["plurals"].get(q, "")) or src_other_ph
                    lp = _placeholders(ltext)
                    if sorted(sp) != sorted(lp):
                        fails.append(f"{tag} plural {skey.rstrip('#[]')}/{q}: placeholder mismatch src {sp} vs loc {lp}")
                    if ltext.strip() == "":
                        fails.append(f"{tag} plural {skey.rstrip('#[]')}/{q}: empty translation")
            elif psrc["kind"] == "array":
                sarr = psrc["array"]
                larr = ploc.get("array", [])
                if len(sarr) != len(larr):
                    fails.append(f"{tag} array {skey.rstrip('[]')}: length mismatch src {len(sarr)} vs loc {len(larr)}")
                else:
                    for i, stext in enumerate(sarr):
                        ltext = larr[i]
                        sp = _placeholders(stext)
                        lp = _placeholders(ltext)
                        if sorted(sp) != sorted(lp):
                            fails.append(f"{tag} array {skey.rstrip('[]')}[{i}]: placeholder mismatch src {sp} vs loc {lp}")
                        if ltext.strip() == "":
                            fails.append(f"{tag} array {skey.rstrip('[]')}[{i}]: empty translation")
            elif psrc["kind"] == "string":
                stext = psrc["text"]
                ltext = ploc.get("text", "")
                sp = _placeholders(stext)
                lp = _placeholders(ltext)
                if sorted(sp) != sorted(lp):
                    fails.append(f"{tag} {skey}: placeholder mismatch src {sp} vs loc {lp}")
                if ltext.strip() == "":
                    if is_packaged:
                        fails.append(f"{tag} {skey}: empty translation for a packaged locale")
                    else:
                        fails.append(f"{tag} {skey}: empty translation")
                # Unfinished: a Tier 1 translation identical to the source likely wasn't translated.
                # (en-rGB is tier 0 and legitimately shares most strings with en-US, so excluded.)
                if is_tier1 and ltext.strip() != "" and ltext.strip() == stext.strip():
                    fails.append(f"{tag} {skey}: translation identical to source (likely untranslated)")

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
