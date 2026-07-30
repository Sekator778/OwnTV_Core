#!/usr/bin/env python3
"""Regression tests for the OwnTV i18n guardrail scripts.

Run:  python3 tools/i18n/test_i18n_tools.py

Each test constructs a minimal fixture (temp res tree + locales.json) and exercises one tool against
it, asserting the documented pass/fail behaviour. These exist because the first implementation pass
documented correct behaviour without testing the bypass/negative case; these tests pin the bypass
cases so a future regression is caught here, not in review.
"""
from __future__ import annotations

import io
import json
import contextlib
import importlib.util
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _load(name: str, path: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _make_fixture(tmpdir: Path, source_xml: str, locales: list, translations: dict[str, str] | None = None):
    """Build a temp res tree + locales.json. translations maps resourceDirectory → XML string."""
    res = tmpdir / "app/src/main/res"
    res.mkdir(parents=True)
    (res / "values").mkdir()
    (res / "values/strings.xml").write_text(source_xml)
    for resdir, xml in (translations or {}).items():
        (res / resdir).mkdir(parents=True)
        (res / f"{resdir}/strings.xml").write_text(xml)
    (tmpdir / "tools/i18n").mkdir(parents=True)
    (tmpdir / "tools/i18n/locales.json").write_text(json.dumps(locales))
    return res


def _locale(id, tag, qualifier, resdir, **kw):
    base = {"id": id, "languageTag": tag, "resourceQualifier": qualifier,
            "resourceDirectory": resdir, "weblateCode": kw.get("weblateCode", id),
            "englishName": kw.get("englishName", id), "endonym": kw.get("endonym", id),
            "script": kw.get("script", "Latn"), "rtl": kw.get("rtl", False),
            "tier": kw.get("tier", 1), "packaged": kw.get("packaged", True),
            "pickerVisible": kw.get("pickerVisible", True)}
    # Remove non-schema keys from kw that we already handled
    for k in list(base):
        if k not in {"id","languageTag","resourceQualifier","resourceDirectory","weblateCode",
                      "englishName","endonym","script","rtl","tier","packaged","pickerVisible"}:
            del base[k]
    return base


# All 21 Tier 1 locales (matching tools/i18n/locales.json) so test fixtures pass the membership gate.
_FULL_TIER1 = [
    _locale("en-US", "en-US", "en", "values", weblateCode="en"),
    _locale("ar", "ar", "ar", "values-ar", weblateCode="ar", script="Arab", rtl=True, packaged=False, pickerVisible=False),
    _locale("pt-BR", "pt-BR", "pt", "values-pt", weblateCode="pt_BR", packaged=False, pickerVisible=False),
    _locale("pt-PT", "pt-PT", "pt-rPT", "values-pt-rPT", weblateCode="pt_PT", packaged=False, pickerVisible=False),
    _locale("zh-CN", "zh-CN", "zh-rCN", "values-zh-rCN", weblateCode="zh_Hans", script="Hans", packaged=False, pickerVisible=False),
    _locale("zh-TW", "zh-TW", "zh-rTW", "values-zh-rTW", weblateCode="zh_Hant", script="Hant", packaged=False, pickerVisible=False),
    _locale("cs", "cs", "cs", "values-cs", packaged=False, pickerVisible=False),
    _locale("da", "da", "da", "values-da", packaged=False, pickerVisible=False),
    _locale("nl", "nl", "nl", "values-nl", packaged=False, pickerVisible=False),
    _locale("fr", "fr", "fr", "values-fr", packaged=False, pickerVisible=False),
    _locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False),
    _locale("it", "it", "it", "values-it", packaged=False, pickerVisible=False),
    _locale("ja", "ja", "ja", "values-ja", script="Jpan", packaged=False, pickerVisible=False),
    _locale("ko", "ko", "ko", "values-ko", script="Hang", packaged=False, pickerVisible=False),
    _locale("nb", "nb", "nb", "values-nb", weblateCode="nb_NO", packaged=False, pickerVisible=False),
    _locale("pl", "pl", "pl", "values-pl", packaged=False, pickerVisible=False),
    _locale("ru", "ru", "ru", "values-ru", script="Cyrl", packaged=False, pickerVisible=False),
    _locale("es-US", "es-US", "es-rUS", "values-es-rUS", weblateCode="es_419", packaged=False, pickerVisible=False),
    _locale("es-ES", "es-ES", "es", "values-es", weblateCode="es_ES", packaged=False, pickerVisible=False),
    _locale("sv", "sv", "sv", "values-sv", packaged=False, pickerVisible=False),
    _locale("tr", "tr", "tr", "values-tr", packaged=False, pickerVisible=False),
]


def _full_tier1():
    """Return a deep copy of _FULL_TIER1 so tests can mutate entries without polluting siblings."""
    return json.loads(json.dumps(_FULL_TIER1))


def _tier1_with(overrides):
    """Return _FULL_TIER1 with specific entries overridden by id."""
    by_id = {e["id"]: e for e in _full_tier1()}
    for e in overrides:
        by_id[e["id"]] = e
    return list(by_id.values())


# ===========================================================================
# validate_strings.py
# ===========================================================================

class TestValidateStrings(unittest.TestCase):

    def setUp(self):
        self.vs = _load("vs_test", "tools/i18n/validate_strings.py")
        self.tmpdir = Path(tempfile.mkdtemp())
        self.vs.TRANSLATION_STATUS = self.tmpdir / "tools/i18n/translation_status.json"

    def _write_status(self, payload):
        self.vs.TRANSLATION_STATUS.parent.mkdir(parents=True, exist_ok=True)
        self.vs.TRANSLATION_STATUS.write_text(json.dumps(payload))

    def _run(self, res, locales_json, release=False):
        self.vs.RES = res
        self.vs.LOCALES_JSON = locales_json
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = self.vs.main(release=release)
        return rc, buf.getvalue()

    def test_catalogue_missing_coverage_field_ok(self):
        """locales.json entries must NOT have a 'coverage' field (it is computed)."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, out)

    def test_catalogue_rejects_stored_coverage(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        locales[0]["coverage"] = 100  # forbidden
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("must not be stored", out)

    def test_catalogue_duplicate_languageTag(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = [
            _locale("en", "en-US", "en", "values"),
            _locale("en2", "en-US", "en-rGB", "values-en-rGB", tier=0, pickerVisible=False),
        ]
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("duplicate languageTag", out)

    def test_catalogue_dir_qualifier_mismatch(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-fr", tier=1, packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("should be 'values-de'", out)

    def test_catalogue_invalid_qualifier(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("x", "xx", "123bad", "values-123bad", tier=1, packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("invalid resourceQualifier", out)

    def test_catalogue_tier1_membership(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        # Only en-US as tier 1, missing the other 20
        locales = [_locale("en-US", "en-US", "en", "values")]
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("missing Tier 1", out)

    def test_xml_entities_not_false_rejected(self):
        """Fish &amp; Chips &lt;3 must NOT be rejected — entities are valid XML."""
        source = '<resources><string name="food">Fish &amp; Chips &lt;3</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, out)

    def test_unescaped_apostrophe_in_source(self):
        source = '<resources><string name="x">It\'s fine</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("unescaped apostrophe", out)

    def test_unescaped_percent_in_source(self):
        source = '<resources><string name="x">50% off</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("unescaped percent", out)

    def test_escaped_percent_ok(self):
        source = '<resources><string name="x">100%% done</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, out)

    def test_positional_with_precision(self):
        """%1$.2f must be recognized as positional, not bare."""
        source = '<resources><string name="x">Score: %1$.2f points</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, out)  # no bare placeholder, no escaping issue

    def test_duplicate_plurals_key_detected(self):
        source = '''<resources>
            <plurals name="songs"><item quantity="one">%1$d song</item><item quantity="other">%1$d songs</item></plurals>
            <plurals name="songs"><item quantity="one">X</item><item quantity="other">Y</item></plurals>
            </resources>'''
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("duplicate key 'songs'", out)

    def test_duplicate_array_key_detected(self):
        source = '''<resources>
            <string-array name="items"><item>A</item><item>B</item></string-array>
            <string-array name="items"><item>C</item></string-array>
            </resources>'''
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("duplicate key 'items'", out)

    def test_duplicate_plurals_no_overwrite(self):
        """First definition must be retained, not overwritten by the duplicate."""
        source = '''<resources>
            <plurals name="songs"><item quantity="one">%1$d song</item><item quantity="other">%1$d songs</item></plurals>
            <plurals name="songs"><item quantity="one">BAD</item></plurals>
            </resources>'''
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        self.vs.RES = res
        self.vs.LOCALES_JSON = self.tmpdir / "tools/i18n/locales.json"
        entries, errs = self.vs._parse_dir(res / "values")
        self.assertIn("other", entries["songs#"]["plurals"])
        self.assertEqual(entries["songs#"]["plurals"]["one"], "%1$d song")  # first retained

    def test_arabic_plural_quantities_placeholder_parity(self):
        """Arabic zero/two/few/many quantities must carry the same placeholders as source."""
        source = '<resources><plurals name="songs"><item quantity="one">%1$d song</item><item quantity="other">%1$d songs</item></plurals></resources>'
        # Arabic translation with zero/two/few/many but missing %1$d in 'few'
        de_xml = '<resources><plurals name="songs"><item quantity="zero">0</item><item quantity="one">%1$d</item><item quantity="two">2</item><item quantity="few">few</item><item quantity="many">many</item><item quantity="other">%1$d</item></plurals></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("ar", "ar", "ar", "values-ar", script="Arab", rtl=True, packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-ar": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        # 'few' has no %1$d but source 'other' has %1$d → mismatch
        self.assertIn("placeholder mismatch", out)

    def test_translation_only_key_detected(self):
        """A key in the translation that doesn't exist in source must be flagged."""
        source = '<resources><string name="hello">Hello</string></resources>'
        de_xml = '<resources><string name="hello">Hallo</string><string name="extra">Extra</string></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("translation-only key 'extra'", out)

    def test_translatable_false_leak_in_translation(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        de_xml = '<resources><string name="hello" translatable="false">Hallo</string></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("translatable='false' must not appear", out)

    def test_identical_to_source_is_not_rejected(self):
        """A Tier 1 translation identical to the source is NOT rejected — legitimate unchanged
        translations (OK, TV, PIN, Wi-Fi, brand names, loanwords) must pass. 'Needs editing' must
        come from Weblate state, not textual equality."""
        source = '<resources><string name="ok">OK</string><string name="hello">Hello world</string></resources>'
        de_xml = '<resources><string name="ok">OK</string><string name="hello">Hello world</string></resources>'
        locales = _tier1_with([_locale("de", "de", "de", "values-de", packaged=True, pickerVisible=True)])
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        self._write_status({"schemaVersion": 1, "locales": {"de": {"ok": "translated", "hello": "approved"}}})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, out)

    def test_needs_editing_translation_state_rejected(self):
        """Unfinished metadata fails; equality to the source is never used as the proxy."""
        source = '<resources><string name="hello">Hello</string></resources>'
        de_xml = '<resources><string name="hello">Hello</string></resources>'
        locales = _tier1_with([_locale("de", "de", "de", "values-de", packaged=True, pickerVisible=True)])
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        self._write_status({"schemaVersion": 1, "locales": {"de": {"hello": "needs-editing"}}})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("is unfinished", out)

    def test_missing_review_state_for_packaged_translation_rejected(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        de_xml = '<resources><string name="hello">Hallo</string></resources>'
        locales = _tier1_with([_locale("de", "de", "de", "values-de", packaged=True, pickerVisible=True)])
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        self._write_status({"schemaVersion": 1, "locales": {}})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json", release=True)
        self.assertEqual(rc, 1, out)
        self.assertIn("missing translation review state", out)

    def test_bare_placeholder_in_translation_rejected(self):
        """A translation that introduces a bare %s where the source has none must be rejected."""
        source = '<resources><string name="hello">Hello</string></resources>'
        de_xml = '<resources><string name="hello">Hallo %s</string></resources>'
        locales = _tier1_with([_locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)])
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("bare placeholder in translation", out)

    def test_bare_placeholder_in_translation_array_rejected(self):
        """A bare %s in a translated string-array item must be rejected."""
        source = '<resources><string-array name="items"><item>A</item><item>B</item></string-array></resources>'
        de_xml = '<resources><string-array name="items"><item>A</item><item>B %s</item></string-array></resources>'
        locales = _tier1_with([_locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)])
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("bare placeholder in translation", out)

    def test_bare_placeholder_in_translation_plural_rejected(self):
        """A bare %s in a translated plural quantity must be rejected."""
        source = '<resources><plurals name="songs"><item quantity="one">%1$d song</item><item quantity="other">%1$d songs</item></plurals></resources>'
        de_xml = '<resources><plurals name="songs"><item quantity="one">%1$d Lied</item><item quantity="other">%s Lieder</item></plurals></resources>'
        locales = _tier1_with([_locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)])
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("bare placeholder in translation", out)

    def test_b_plus_qualifier_accepted(self):
        """b+sr+Latn (Android script-qualified folder form) must be accepted."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _tier1_with([_locale("sr-Latn", "sr-Latn", "b+sr+Latn", "values-b+sr+Latn",
                                        packaged=False, pickerVisible=False)])
        # Remove one tier1 to keep membership exact (sr-Latn is not in the expected set)
        locales = [e for e in locales if e["id"] != "sr-Latn"]
        # Add it back with tier=0 so it doesn't affect the Tier 1 membership check
        sr = _locale("sr-Latn", "sr-Latn", "b+sr+Latn", "values-b+sr+Latn", tier=0,
                     weblateCode="sr_Latn", packaged=False, pickerVisible=False)
        locales.append(sr)
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        # Should pass — b+ qualifier is valid and its Weblate code uses the canonical underscore form.
        self.assertEqual(rc, 0, out)
        self.assertNotIn("invalid resourceQualifier", out)

    def test_canonical_weblate_mapping_pin(self):
        """A wrong weblateCode for a pinned qualifier must be rejected."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        # Corrupt pt-BR's weblateCode (should be pt_BR, not pt_PT)
        for e in locales:
            if e["id"] == "pt-BR":
                e["weblateCode"] = "pt_PT"
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("should be 'pt_BR'", out)

    def test_xliff_placeholder_parity(self):
        """Placeholders wrapped in <xliff:g> must be captured for parity checking."""
        xliff_ns = 'xmlns:xliff="urn:oe:names:tc:xliff:document:1.2"'
        source = f'<resources {xliff_ns}><string name="greet">Hello <xliff:g id="n">%1$s</xliff:g>, %2$d items</string></resources>'
        # German: swap placeholder order (valid) but drop one
        de_xml = '<resources><string name="greet">Hallo %1$s</string></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("placeholder mismatch", out)

    def test_tier1_coverage_gate(self):
        """A packaged Tier 1 locale missing a source key must fail the coverage gate."""
        source = '<resources><string name="a">A</string><string name="b">B</string></resources>'
        de_xml = '<resources><string name="a">A</string></resources>'  # missing 'b'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=True, pickerVisible=True)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("Tier 1 coverage gate failed", out)

    def test_bare_placeholder_in_source(self):
        source = '<resources><string name="x">Value %s</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("bare placeholder", out)

    # --- Plural validation fixes ---

    def test_source_plural_missing_one_rejected(self):
        """Source English must carry the 'one' quantity (English's CLDR rule requires one, other)."""
        source = '<resources><plurals name="songs"><item quantity="other">%1$d songs</item></plurals></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("missing required English quantity `one`", out)

    def test_plural_zero_placeholders_not_confused_with_absent(self):
        """A source quantity present with zero placeholders must match a translation with zero —
        the old `or` fallback confused 'quantity absent' (empty list) with 'present, no placeholders'."""
        source = '<resources><plurals name="songs"><item quantity="one">One song</item><item quantity="other">%1$d songs</item></plurals></resources>'
        de_xml = '<resources><plurals name="songs"><item quantity="one">Ein Lied</item><item quantity="other">%1$d Lieder</item></plurals></resources>'
        locales = _tier1_with([_locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)])
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, f"valid zero-placeholder quantity was rejected: {out}")

    def test_french_plural_many_required(self):
        """French's CLDR rule includes 'many' (for large round numbers) — a French translation
        missing 'many' must be rejected."""
        source = '<resources><plurals name="songs"><item quantity="one">%1$d song</item><item quantity="other">%1$d songs</item></plurals></resources>'
        fr_xml = '<resources><plurals name="songs"><item quantity="one">%1$d chanson</item><item quantity="other">%1$d chansons</item></plurals></resources>'
        locales = _tier1_with([_locale("fr", "fr", "fr", "values-fr", packaged=False, pickerVisible=False)])
        res = _make_fixture(self.tmpdir, source, locales, {"values-fr": fr_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("missing required quantity many", out)

    # --- Qualifier and Weblate validation fixes ---

    def test_b_plus_numeric_region_accepted(self):
        """b+es+419 (UN M.49 numeric region in b+ form) must be accepted."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        # Add a non-tier1 entry with b+es+419
        locales.append(_locale("es-419", "es-419", "b+es+419", "values-b+es+419", tier=0,
                               weblateCode="es_419", packaged=False, pickerVisible=False))
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, f"b+es+419 was rejected: {out}")
        self.assertNotIn("invalid resourceQualifier", out)

    def test_plain_script_qualifier_rejected_by_aapt2_policy(self):
        """Android script folders must use b+ syntax; sr-Latn is not an aapt2 resource qualifier."""
        self.assertIsNone(self.vs._QUAL_RE.fullmatch("sr-Latn"))
        self.assertIsNone(self.vs._QUAL_RE.fullmatch("b+de"))
        self.assertIsNone(self.vs._QUAL_RE.fullmatch("b+en+US"))
        self.assertIsNotNone(self.vs._QUAL_RE.fullmatch("b+sr+Latn"))
        self.assertIsNotNone(self.vs._QUAL_RE.fullmatch("b+es+419"))

    def test_b_plus_lowercase_script_rejected(self):
        """b+sr+latn (lowercase script) must be rejected — Android requires title-case script."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        locales.append(_locale("sr-Latn", "sr-Latn", "b+sr+latn", "values-b+sr+latn", tier=0,
                               packaged=False, pickerVisible=False))
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertIn("invalid resourceQualifier", out)

    def test_canonical_weblate_all_entries_pinned(self):
        """Changing German's weblateCode from 'de' to 'fr' must be rejected — all entries are pinned."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        for e in locales:
            if e["id"] == "de":
                e["weblateCode"] = "fr"  # wrong — should be 'de'
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("should be 'de'", out)

    def test_tier_42_rejected(self):
        """tier=42 must be rejected — only 0 and 1 are valid."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        for e in locales:
            if e["id"] == "de":
                e["tier"] = 42
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("tier must be 0 or 1", out)

    def test_blank_english_name_rejected(self):
        """A blank englishName must be rejected."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        for e in locales:
            if e["id"] == "de":
                e["englishName"] = ""
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("englishName must be a non-blank string", out)

    def test_boolean_true_tier_rejected(self):
        """JSON true is an int subclass in Python; it must not satisfy the integer tier schema."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        for e in locales:
            if e["id"] == "de":
                e["tier"] = True
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("tier must be 0 or 1", out)

    def test_catalogue_root_type_rejected_without_crash(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        res = _make_fixture(self.tmpdir, source, [])
        (self.tmpdir / "tools/i18n/locales.json").write_text(json.dumps({"de": {}}))
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("root must be an array", out)

    # --- translatable=false placement and formatting fixes ---

    def test_source_donottranslate_requires_false(self):
        """Every source entry in donottranslate.xml must explicitly be non-translatable."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        (res / "values/donottranslate.xml").write_text(
            '<resources><string name="hidden">Visible text</string></resources>')
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("must declare translatable=\"false\"", out)

    def test_source_donottranslate_collides_with_source_key(self):
        """The constants namespace may not duplicate a key from strings*.xml."""
        source = '<resources><string name="hidden">Visible text</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        (res / "values/donottranslate.xml").write_text(
            '<resources><string name="hidden" translatable="false">Protocol</string></resources>')
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("duplicate key 'hidden'", out)

    def test_source_donottranslate_valid_entry_passes(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        (res / "values/donottranslate.xml").write_text(
            '<resources><string name="brand" translatable="false">OwnTV</string></resources>')
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, out)

    def test_translatable_false_in_strings_xml_rejected(self):
        """translatable='false' inside strings.xml must be rejected — it belongs in donottranslate.xml."""
        source = '<resources><string name="brand" translatable="false">OwnTV</string><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("translatable='false' on 'brand' must be in donottranslate.xml", out)

    def test_translatable_false_single_quote_is_rejected(self):
        source = "<resources><string name='brand' translatable='false'>OwnTV</string></resources>"
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("translatable='false' on 'brand'", out)

    def test_translation_donottranslate_file_is_rejected(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": '<resources></resources>'})
        (res / "values-de/donottranslate.xml").write_text(
            '<resources><string name="app_name" translatable="false">OwnTV</string></resources>')
        # Make the synthetic locale part of the catalogue without changing the exact Tier 1 set.
        for e in locales:
            if e["id"] == "de":
                e["packaged"] = False
                e["pickerVisible"] = False
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("donottranslate.xml leaked key 'app_name'", out)

    def test_empty_source_still_checks_translation_only_keys(self):
        source = '<resources></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales, {
            "values-de": '<resources><string name="leaked">Leaked</string></resources>'})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("translation-only key 'leaked'", out)

    def test_full_java_format_placeholders_recognized(self):
        "%1$tY, %1$tL and %1$S must be recognized as positional, not flagged as unescaped percent."""
        source = '<resources><string name="year">Year %1$tY %1$tL %1$S</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, f"Java format placeholders were rejected: {out}")

    def test_invalid_java_format_placeholders_rejected(self):
        """Formatter-invalid flag/conversion combinations must fail before runtime formatting."""
        invalid = ["%0$s", "%1$#s", "%1$-s", "%1$.2d", "%1$0tY", "%1$0f",
                   "%1$+x", "%1$,e", "%1$#g", "%1$(a", "%1$L", "%1$tJ", "%1$n"]
        locales = _full_tier1()
        for placeholder in invalid:
            with self.subTest(placeholder=placeholder):
                source = f'<resources><string name="x">Value {placeholder}</string></resources>'
                case_dir = Path(tempfile.mkdtemp())
                res = _make_fixture(case_dir, source, locales)
                rc, out = self._run(res, case_dir / "tools/i18n/locales.json")
                self.assertEqual(rc, 1, f"invalid placeholder {placeholder} passed: {out}")
                self.assertIn("invalid Java/Android format placeholder", out)

    def test_invalid_java_format_placeholder_in_translation_rejected(self):
        source = '<resources><string name="x">Value %1$s</string></resources>'
        de_xml = '<resources><string name="x">Wert %1$#s</string></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("invalid Java/Android format placeholder", out)

    def test_whole_string_quoted_apostrophe_accepted(self):
        """'This\'ll work' wrapped in whole-string double quotes is valid Android — not rejected."""
        source = '<resources><string name="x">"This\'ll work"</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, f"whole-string quoted apostrophe was rejected: {out}")


# ===========================================================================
# check_hardcoded_strings.py
# ===========================================================================

class TestCheckHardcodedStrings(unittest.TestCase):

    def setUp(self):
        self.chs = _load("chs_test", "tools/i18n/check_hardcoded_strings.py")
        self.tmpdir = Path(tempfile.mkdtemp())
        # Point SRC and BASELINE at the temp dir.
        self.chs.SRC = self.tmpdir / "src"
        self.chs.SRC.mkdir()
        self.chs.BASELINE = self.tmpdir / "baseline.txt"
        self.chs.ASSERTION_ALLOWLIST = self.tmpdir / "allowlist.txt"
        self.chs.ROOT = self.tmpdir
        self.chs._ERROR_MESSAGES_FILE = "src/ErrorMessages.kt"

    def _write_kt(self, name, content):
        (self.chs.SRC / name).write_text(content)

    def test_bcp47_tags_safe_but_ui_words_not(self):
        self._write_kt("Locale.kt", 'package x\nval tags = listOf("en-US", "b+sr+Latn", "retry")\n')
        unsafe = {k[1] for k in self.chs._scan()}
        self.assertNotIn("en-US", unsafe)
        self.assertNotIn("b+sr+Latn", unsafe)
        self.assertIn("retry", unsafe)

    def test_select_and_update_prefixes_are_not_sql(self):
        """SQL detection must not hide ordinary copy merely because it starts with a keyword."""
        self.assertFalse(self.chs._is_sql("Update profile set preferences"))
        self.assertFalse(self.chs._is_sql("Select an item from Favorites (optional)"))
        self._write_kt("Labels.kt", '''package x
fun f() {
    Text("Select a channel to preview it here.")
    Text("Update now")
    Text("Update this source's details, or change its auto-refresh setting.")
}
''')
        unsafe = {key[1] for key in self.chs._scan()}
        self.assertIn("Select a channel to preview it here.", unsafe)
        self.assertIn("Update now", unsafe)
        self.assertIn("Update this source's details, or change its auto-refresh setting.", unsafe)

    def test_real_sql_still_safe(self):
        self._write_kt("Database.kt", 'package x\nval q = "SELECT * FROM users WHERE id = :id"\nval u = "UPDATE users SET name = :name WHERE id = :id"\n')
        unsafe = {key[1] for key in self.chs._scan()}
        self.assertNotIn("SELECT * FROM users WHERE id = :id", unsafe)
        self.assertNotIn("UPDATE users SET name = :name WHERE id = :id", unsafe)

    def test_unqualified_query_name_does_not_make_copy_safe(self):
        self._write_kt("Query.kt", 'package x\nfun f() = query("Visible query label")\n')
        self.assertIn("Visible query label", {key[1] for key in self.chs._scan()})

    def test_live_text_not_safe(self):
        """Text("LIVE") must NOT be classified as safe — it's user-facing display text."""
        self._write_kt("PlayerHud.kt", 'package x\nfun f() = Text("LIVE")\n')
        counts = self.chs._scan()
        # "LIVE" should be in the unsafe set
        live_keys = [k for k in counts if "LIVE" in k[1]]
        self.assertTrue(live_keys, "LIVE was incorrectly exempted as a safe token")

    def test_ok_text_not_safe(self):
        self._write_kt("ProfileComponents.kt", 'package x\nval x = "OK"\n')
        counts = self.chs._scan()
        ok_keys = [k for k in counts if k[1] == "OK"]
        self.assertTrue(ok_keys, "OK was incorrectly exempted as a safe token")

    def test_perf_stamp_safe(self):
        self._write_kt("Main.kt", 'package x\nfun f() = Perf.stamp("db-probed")\n')
        counts = self.chs._scan()
        stamp_keys = [k for k in counts if "db-probed" in k[1]]
        self.assertFalse(stamp_keys, "Perf.stamp arg was not exempted")

    def test_suppress_annotation_safe(self):
        self._write_kt("Main.kt", 'package x\n@Suppress("UNCHECKED_CAST")\nfun f() = Unit\n')
        counts = self.chs._scan()
        sup_keys = [k for k in counts if "UNCHECKED_CAST" in k[1]]
        self.assertFalse(sup_keys, "@Suppress arg was not exempted")

    def test_error_messages_needles_safe_but_friendly_not(self):
        """Only .containsAny()/.contains() arguments (needles) are safe; return values are not."""
        kt = '''package x
fun friendlySyncError(raw: String?): String = when {
    raw!!.containsAny("timeout", "timed out") ->
        "The server took too long to respond. Please try again."
    else -> raw
}
fun isTransientSyncError(raw: String?): Boolean = when {
    else -> raw!!.containsAny(
        "timeout", "timed out",
        "Connection refused",
    )
}
private fun String.containsAny(vararg n: String) = n.any { contains(it) }
'''
        self._write_kt("ErrorMessages.kt", kt)
        counts = self.chs._scan()
        contents = {k[1] for k in counts}
        # Needles must NOT be in the baseline
        self.assertNotIn("timeout", contents, "needle 'timeout' was not exempted")
        self.assertNotIn("Connection refused", contents, "multi-line needle 'Connection refused' was not exempted")
        # Friendly message MUST be in the baseline
        self.assertIn("The server took too long to respond. Please try again.", contents,
                        "friendly return-value message was incorrectly exempted")

    def test_json_put_field_name_safe(self):
        """json.put("profileId", id) — the string is a JSON field name, not display text."""
        self._write_kt("Main.kt", 'package x\nimport org.json.JSONObject\nfun f(id: Long) {\n  val j = JSONObject()\n  j.put("profileId", id)\n}\n')
        counts = self.chs._scan()
        self.assertNotIn("profileId", {k[1] for k in counts}, "JSON .put() field name was not exempted")

    def test_json_put_in_apply_block_safe(self):
        """put("version", 14) inside JSONObject().apply { } — no leading dot, still a JSON key."""
        self._write_kt("Main.kt", 'package x\nimport org.json.JSONObject\nfun f() {\n  JSONObject().apply {\n    put("version", 14)\n    put("sections", 3)\n  }\n}\n')
        counts = self.chs._scan()
        contents = {k[1] for k in counts}
        self.assertNotIn("version", contents, "JSON put() in apply block: 'version' was not exempted")
        self.assertNotIn("sections", contents, "JSON put() in apply block: 'sections' was not exempted")

    def test_json_get_string_safe(self):
        """obj.getString("profileId") — the string is a JSON field name."""
        self._write_kt("Main.kt", 'package x\nimport org.json.JSONObject\nfun f(j: JSONObject): String = j.getString("profileId")\n')
        counts = self.chs._scan()
        self.assertNotIn("profileId", {k[1] for k in counts}, "JSON .getString() field name was not exempted")

    def test_json_opt_json_object_safe(self):
        """root.optJSONObject("settings") — the string is a JSON field name."""
        self._write_kt("Main.kt", 'package x\nimport org.json.JSONObject\nfun f(root: JSONObject) = root.optJSONObject("settings")\n')
        counts = self.chs._scan()
        self.assertNotIn("settings", {k[1] for k in counts}, "JSON .optJSONObject() field name was not exempted")

    def test_room_index_column_name_safe(self):
        """Index("sourceId") — Room entity column name, not display text."""
        self._write_kt("Entity.kt", 'package x\nimport androidx.room.Entity\nimport androidx.room.Index\n@Entity(indices = [Index("sourceId"), Index(value = ["contentKey", "profileId"])])\nclass E\n')
        counts = self.chs._scan()
        contents = {k[1] for k in counts}
        self.assertNotIn("sourceId", contents, "Room Index() column name was not exempted")
        self.assertNotIn("contentKey", contents, "Room Index(value=[...]) column name was not exempted")
        self.assertNotIn("profileId", contents, "Room Index(value=[...]) column name was not exempted")

    def test_room_primary_keys_safe(self):
        """primaryKeys = ["profileId", "contentKey"] — Room primary key column names."""
        self._write_kt("Entity.kt", 'package x\nimport androidx.room.Entity\n@Entity(primaryKeys = ["profileId", "contentKey"])\nclass E\n')
        counts = self.chs._scan()
        contents = {k[1] for k in counts}
        self.assertNotIn("profileId", contents, "Room primaryKeys column name was not exempted")
        self.assertNotIn("contentKey", contents, "Room primaryKeys column name was not exempted")

    def test_uri_query_parameter_safe(self):
        """.appendQueryParameter("sourceId", ...) — URI parameter name, not display text."""
        self._write_kt("DeepLink.kt", 'package x\nimport android.net.Uri\nfun f(b: Uri.Builder, id: Long) = b.appendQueryParameter("sourceId", id.toString())\n')
        counts = self.chs._scan()
        self.assertNotIn("sourceId", {k[1] for k in counts}, "URI query parameter name was not exempted")

    def test_key_const_value_safe(self):
        """const val KEY_SOURCE_ID = "sourceId" — the value is a preference/DataStore key."""
        self._write_kt("Worker.kt", 'package x\nclass W {\n  companion object {\n    const val KEY_SOURCE_ID = "sourceId"\n  }\n}\n')
        counts = self.chs._scan()
        self.assertNotIn("sourceId", {k[1] for k in counts}, "const val KEY_... value was not exempted")

    def test_safe_declaration_does_not_exempt_adjacent_literal(self):
        """TAG/KEY declarations exempt only their initializer, not another literal on the line."""
        self._write_kt("Keys.kt", 'package x\nconst val TAG = "Worker"; val label = "LIVE"\nconst val KEY_ID = "profileId"; val title = "Settings"\n')
        unsafe = {k[1] for k in self.chs._scan()}
        self.assertIn("LIVE", unsafe)
        self.assertIn("Settings", unsafe)
        self.assertNotIn("Worker", unsafe)
        self.assertNotIn("profileId", unsafe)

    def test_safe_call_does_not_exempt_adjacent_literal_for_log_or_regex(self):
        """Log/Regex position handling must not fall back to a whole-line exemption."""
        self._write_kt("Patterns.kt", 'package x\nfun f() { Log.i("TAG", "developer ${x ?: "message"}"); val x = "Visible label"\nval r = Regex("[a-z]+"); val y = "Another label" }\n')
        unsafe = {k[1] for k in self.chs._scan()}
        self.assertIn("Visible label", unsafe)
        self.assertIn("Another label", unsafe)
        self.assertNotIn('developer ${x ?: "message"}', unsafe)
        self.assertIn("message", unsafe, "nested log fallback was incorrectly inherited as safe")
        self.assertNotIn("[a-z]+", unsafe)

    def test_nested_log_argument_does_not_exempt_visible_literal(self):
        """Only direct log arguments are safe; a nested formatter call can return UI text."""
        self._write_kt("LogContext.kt", 'package x\nfun f() { Log.w("TAG", makeMessage("Visible copy")); Log.d("TAG", "Developer diagnostic") }\n')
        unsafe = {key[1] for key in self.chs._scan()}
        self.assertIn("Visible copy", unsafe)
        self.assertNotIn("Developer diagnostic", unsafe)

    def test_camelcase_display_text_not_safe(self):
        """A CamelCase word like 'Settings' used as Text() content must NOT be exempted."""
        self._write_kt("Main.kt", 'package x\nfun f() = Text("Settings")\n')
        counts = self.chs._scan()
        self.assertIn("Settings", {k[1] for k in counts}, "CamelCase display text was incorrectly exempted")

    def test_bootstrap_skips_regression_leg(self):
        """--bootstrap must skip the regression leg (no merge-base baseline to compare against)."""
        self._write_kt("Main.kt", 'package x\nval x = "Hello world"\n')
        # Generate the committed baseline so it matches current scan.
        self.chs.cmd_generate(None)
        # Verify with --bootstrap and no --base — should pass (committed == current).
        class Args:
            base = None
            bootstrap = True
        rc = self.chs.cmd_verify(Args())
        self.assertEqual(rc, 0)

    def test_scanner_migration_workflow_freezes_app_tree(self):
        """Version migration must not be able to bootstrap over application-source changes."""
        workflow = (ROOT / ".github/workflows/i18n.yml").read_text()
        self.assertIn('git diff --name-only "$BASE_SHA" HEAD -- app/src/main', workflow)
        self.assertIn("Scanner migrations may not change app/src/main", workflow)

    def test_stale_baseline_detected(self):
        """Deleting committed baseline entries while leaving literals in source must fail."""
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\nval y = "World"\n')
        self.chs.cmd_generate(None)
        # Empty the committed baseline (simulate deletion without extraction).
        self.chs.BASELINE.write_text("# header only\n")
        class Args:
            base = None
            bootstrap = True
        rc = self.chs.cmd_verify(Args())
        self.assertEqual(rc, 1, "stale baseline was not detected")

    def test_regression_against_merge_base(self):
        """A new literal absent from the merge-base baseline must fail (non-bootstrap)."""
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\n')
        self.chs.cmd_generate(None)
        base_file = self.tmpdir / "base.txt"
        base_file.write_text(self.chs.BASELINE.read_text())
        # Add a new literal.
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\nval y = "New literal"\n')
        class Args:
            base = str(base_file)
            bootstrap = False
        rc = self.chs.cmd_verify(Args())
        self.assertEqual(rc, 1, "regression was not detected")

    def test_over_baseline_detected(self):
        """Committed baseline entries not produced by current code must fail."""
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\n')
        self.chs.cmd_generate(None)
        # Add a fake entry to the committed baseline.
        text = self.chs.BASELINE.read_text()
        text += '1\tsrc/Main.kt\tFAKE ENTRY\n'
        self.chs.BASELINE.write_text(text)
        class Args:
            base = str(self.chs.BASELINE)
            bootstrap = True
        rc = self.chs.cmd_verify(Args())
        self.assertEqual(rc, 1, "over-baseline was not detected")

    # --- Position-based invariant: a safe call exempts ONLY its argument, not other literals ---

    def test_json_put_value_literal_not_safe(self):
        """json.put("title", "Visible label") — the value 'Visible label' is user-facing text and
        must NOT be exempted just because a JSON .put() call is on the same line."""
        self._write_kt("Main.kt", 'package x\nimport org.json.JSONObject\nfun f() {\n  val j = JSONObject()\n  j.put("title", "Visible label")\n}\n')
        counts = self.chs._scan()
        unsafe = {k[1] for k in counts}
        self.assertIn("Visible label", unsafe, "JSON .put() value literal was wrongly exempted")
        self.assertNotIn("title", unsafe, "JSON .put() key was not exempted")

    def test_nested_json_argument_is_not_inherited_safe(self):
        """A fallback inside a JSON/log expression is not safe merely because its parent is safe."""
        self._write_kt("NestedJson.kt", 'package x\nimport org.json.JSONObject\nfun f(j: JSONObject) {\n  Log.d("TAG", "payload=${j.optString("label", "Visible fallback")}")\n}\n')
        unsafe = {k[1] for k in self.chs._scan()}
        self.assertIn("Visible fallback", unsafe)
        self.assertNotIn("label", unsafe, "JSON field name should remain a safe direct argument")

    def test_first_argument_must_be_literal_and_json_receiver_verified(self):
        """Only a direct first argument on a verified JSONObject/URI call is safe.

        A key variable followed by a visible value must not make the value safe, and a MutableMap's
        put() must not inherit JSONObject's protocol exemption merely from sharing the method name.
        """
        kt = '''package x
import org.json.JSONObject
fun f(json: JSONObject, builder: android.net.Uri.Builder, key: String,
      values: MutableMap<String, Int>) {
    json.put(key, "Visible title")
    builder.appendQueryParameter(key, "Visible value")
    values.put("Visible category", 1)
}
'''
        self._write_kt("Calls.kt", kt)
        unsafe = {k[1] for k in self.chs._scan()}
        self.assertIn("Visible title", unsafe)
        self.assertIn("Visible value", unsafe)
        self.assertIn("Visible category", unsafe)

    def test_content_shape_is_not_a_safe_category(self):
        """Kebab/snake/path-looking UI copy must remain in the ratchet; key factories are contextual."""
        kt = '''package x
import androidx.datastore.preferences.core.stringPreferencesKey
fun f() {
    Text("sign-in")
    Text("audio-only")
    Text("and/or")
    Text("retry_later")
    Text("retry.later")
    stringPreferencesKey("retry_later")
}
'''
        self._write_kt("Shapes.kt", kt)
        unsafe = {k[1] for k in self.chs._scan()}
        self.assertTrue({"sign-in", "audio-only", "and/or", "retry_later", "retry.later"} <= unsafe)
        # The same spelling is safe only at the explicit DataStore key factory call.
        self.assertEqual(sum(1 for key in self.chs._scan() if key[1] == "retry_later"), 1)

    def test_baseline_escape_round_trip_preserves_backslash_sequences(self):
        """A literal backslash followed by 'n' must not deserialize as a real newline."""
        counts = {
            ("Main.kt", r"literal\nsequence"): 1,
            ("Main.kt", "actual\nnewline\tand\\slash"): 2,
        }
        encoded = self.chs._serialize(counts)
        self.assertEqual(self.chs._parse(encoded), counts)

    def test_kotlin_escape_decoder_keeps_escaped_unicode_distinct(self):
        """\\\\u0041 (literal slash) must not collapse to the runtime escape \\u0041."""
        self.assertEqual(self.chs._decode(r'"\u0041"'), "A")
        self.assertEqual(self.chs._decode(r'"\\u0041"'), r"\u0041")
        self.assertNotEqual(self.chs._decode(r'"\u0041"'), self.chs._decode(r'"\\u0041"'))
        self.assertEqual(self.chs._decode(r'"\n"'), "\n")
        self.assertEqual(self.chs._decode(r'"\\n"'), r"\n")

    def test_json_call_does_not_exempt_adjacent_literal(self):
        """A literal on the same line as a JSON call but NOT its argument must remain unsafe.
        error("geo no match") on a line with .optJSONArray("results") — 'geo no match' is the
        error message (user-facing via exception), 'results' is the JSON key."""
        kt = 'package x\nimport org.json.JSONObject\nfun f(json: String) {\n  val hit = JSONObject(json).optJSONArray("results") ?: return error("geo no match")\n}\n'
        self._write_kt("Weather.kt", kt)
        counts = self.chs._scan()
        unsafe = {k[1] for k in counts}
        self.assertIn("geo no match", unsafe, "error() message was wrongly exempted by adjacent JSON call")
        self.assertNotIn("results", unsafe, "JSON key 'results' was not exempted")

    def test_atomicinteger_get_does_not_exempt_adjacent_literal(self):
        """pageFailures.get() > 0 on a line with a user-facing string — the .get() is
        AtomicInteger.get(), NOT JSON, and the adjacent string must remain unsafe."""
        kt = 'package x\nimport java.util.concurrent.atomic.AtomicInteger\nfun f(pageFailures: AtomicInteger) {\n  val msg = "${pageFailures.get()} portal page(s) failed"\n  Log.i("TAG", msg)\n}\n'
        self._write_kt("Syncer.kt", kt)
        counts = self.chs._scan()
        unsafe = {k[1] for k in counts}
        # The interpolated string (with the ${} hole) must be in the baseline — .get() is not JSON
        self.assertTrue(any("portal page(s) failed" in c for c in unsafe),
                        "user-facing string was wrongly exempted by AtomicInteger.get()")

    def test_nested_interpolation_inner_literal_detected(self):
        """Changing the inner literal of "Movies / ${title ?: "All"}" must change the baseline."""
        self._write_kt("Screen.kt", 'package x\nfun f() = Text("Movies / ${title ?: "All"}")\n')
        lits = list(self.chs._iter_literals('package x\nfun f() = Text("Movies / ${title ?: "All"}")\n'))
        contents = [self.chs._decode(raw) for s, e, raw in lits]
        self.assertIn("All", contents, "nested interpolation inner literal 'All' was not detected")

    def test_nested_interpolation_change_detected(self):
        """Changing 'All' to 'Everything' inside interpolation produces a different scan."""
        src1 = 'package x\nval x = "Movies / ${title ?: "All"}"\n'
        src2 = 'package x\nval x = "Movies / ${title ?: "Everything"}"\n'
        lits1 = {self.chs._decode(raw) for s, e, raw in self.chs._iter_literals(src1)}
        lits2 = {self.chs._decode(raw) for s, e, raw in self.chs._iter_literals(src2)}
        self.assertIn("All", lits1)
        self.assertIn("Everything", lits2)
        self.assertNotIn("All", lits2, "changing inner literal did not change the scan")
        self._write_kt("Screen.kt", src1)
        scan1 = {k[1] for k in self.chs._scan()}
        self._write_kt("Screen.kt", src2)
        scan2 = {k[1] for k in self.chs._scan()}
        self.assertIn("All", scan1)
        self.assertIn("Everything", scan2)
        self.assertNotIn("All", scan2, "baseline scan ignored the nested interpolation literal")

    def test_room_columninfo_named_column_safe(self):
        """ColumnInfo(name = "profileId") is a contextual Room column declaration."""
        kt = '''package x
import androidx.room.ColumnInfo
class E {
    @ColumnInfo(name = "profileId")
    val id: Long = 0
}
'''
        self._write_kt("Entity.kt", kt)
        self.assertNotIn("profileId", {k[1] for k in self.chs._scan()})

    def test_room_index_value_literal_not_safe(self):
        """Index(value = ["col"]) — the column name is safe, but an adjacent display literal is not."""
        kt = 'package x\nimport androidx.room.Entity\nimport androidx.room.Index\n@Entity(indices = [Index("sourceId")])\nclass E {\n  val label = "Settings"\n}\n'
        self._write_kt("Entity.kt", kt)
        counts = self.chs._scan()
        unsafe = {k[1] for k in counts}
        self.assertNotIn("sourceId", unsafe, "Room Index column name was not exempted")
        self.assertIn("Settings", unsafe, "adjacent display text was wrongly exempted")

    def test_uri_param_value_literal_not_safe(self):
        """.appendQueryParameter("sourceId", label) — the value 'label' (a display string) is not safe."""
        kt = 'package x\nimport android.net.Uri\nfun f(b: Uri.Builder) = b.appendQueryParameter("sourceId", "Visible label")\n'
        self._write_kt("DeepLink.kt", kt)
        counts = self.chs._scan()
        unsafe = {k[1] for k in counts}
        self.assertNotIn("sourceId", unsafe, "URI param name was not exempted")
        self.assertIn("Visible label", unsafe, "URI param value literal was wrongly exempted")


# ===========================================================================
# gen_supported_locales.py
# ===========================================================================

class TestGenSupportedLocales(unittest.TestCase):

    def setUp(self):
        self.gen = _load("gen_test", "tools/i18n/gen_supported_locales.py")
        self.tmpdir = Path(tempfile.mkdtemp())
        self.gen.LOCALES_JSON = self.tmpdir / "locales.json"
        self.gen.RES = self.tmpdir / "res"
        self.gen.OUT = self.tmpdir / "SupportedLocales.kt"
        self.gen.ROOT = self.tmpdir  # so OUT.relative_to(ROOT) works

    def _write(self, locales, source_xml="<resources></resources>", translations=None):
        self.gen.LOCALES_JSON.write_text(json.dumps(locales))
        (self.gen.RES / "values").mkdir(parents=True)
        (self.gen.RES / "values/strings.xml").write_text(source_xml)
        for resdir, xml in (translations or {}).items():
            (self.gen.RES / resdir).mkdir(parents=True)
            (self.gen.RES / f"{resdir}/strings.xml").write_text(xml)

    def test_string_array_counted_in_coverage(self):
        """string-array keys must be counted in coverage, matching validate_strings.py."""
        source = '''<resources>
            <string name="a">A</string>
            <string-array name="items"><item>X</item><item>Y</item></string-array>
            <plurals name="songs"><item quantity="one">%1$d song</item><item quantity="other">%1$d songs</item></plurals>
            </resources>'''
        de_xml = '''<resources>
            <string name="a">A</string>
            <string-array name="items"><item>X</item><item>Y</item></string-array>
            </resources>'''  # missing plurals
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)]
        self._write(locales, source, {"values-de": de_xml})
        text, n, nkeys = self.gen._generate()
        # Source has 3 translatable keys: a, items[], songs#
        self.assertEqual(nkeys, 3, f"expected 3 source keys, got {nkeys}")
        # de has 2 of 3 → coverage = round(100*2/3) = 67
        de_entry = [e for e in locales if e["id"] == "de"][0]
        # _generate returns (text, n_entries, n_keys); coverage is embedded in the text
        self.assertIn("coverage = 67", text)

    def test_check_detects_stale(self):
        locales = _full_tier1()
        self._write(locales)
        # Generate and then modify locales.json
        self.gen.cmd_generate()
        locales[0]["englishName"] = "MODIFIED"
        self.gen.LOCALES_JSON.write_text(json.dumps(locales))
        rc = self.gen.cmd_check()
        self.assertEqual(rc, 1, "stale SupportedLocales.kt was not detected")

    def test_check_passes_when_fresh(self):
        locales = _full_tier1()
        self._write(locales)
        self.gen.cmd_generate()
        rc = self.gen.cmd_check()
        self.assertEqual(rc, 0)


# ===========================================================================
# check_pseudo_locales.py
# ===========================================================================

class TestCheckPseudoLocales(unittest.TestCase):

    def setUp(self):
        self.cp = _load("cp_test", "tools/i18n/check_pseudo_locales.py")

    def test_locale_config_extraction(self):
        """Full aapt2 configs like 'en-rGB-w720dp' must extract locale 'en-rGB'."""
        configs = {"en-rGB-w720dp-h1280dp", "en-rXA", "ar-rXB", "ar", "v26", "w720dp"}
        locales = self.cp._locale_configs(configs)
        self.assertIn("en-rGB", locales)
        self.assertIn("en-rXA", locales)
        self.assertIn("ar-rXB", locales)
        self.assertIn("ar", locales)
        self.assertNotIn("v26", locales)
        self.assertNotIn("w720dp", locales)

    def test_debug_leak_detected(self):
        """A locale outside the allowed debug set must be flagged as a leak."""
        configs = {"en-rGB", "en-rXA", "ar-rXB", "ar", "fr-port-mdpi"}
        locales = self.cp._locale_configs(configs)
        leaks = locales - self.cp._ALLOWED_DEBUG
        self.assertIn("fr", leaks)

    def test_release_leak_detected(self):
        """en-rXA in a release config set must be flagged."""
        configs = {"en", "en-rGB", "en-rXA"}
        locales = self.cp._locale_configs(configs)
        leaks = locales - self.cp._ALLOWED_RELEASE
        self.assertIn("en-rXA", leaks)


if __name__ == "__main__":
    unittest.main(verbosity=2)
