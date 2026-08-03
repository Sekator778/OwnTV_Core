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

    def test_translatable_false_on_plural_or_array_is_rejected(self):
        source = '''<resources>
            <plurals name="songs" translatable="false"><item quantity="one">One</item><item quantity="other">Many</item></plurals>
            <string-array name="items" translatable="false"><item>A</item></string-array>
        </resources>'''
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("translatable='false' on 'songs'", out)
        self.assertIn("translatable='false' on 'items'", out)

    def test_translation_false_plural_or_array_is_rejected(self):
        source = '''<resources>
            <plurals name="songs"><item quantity="one">One</item><item quantity="other">Many</item></plurals>
            <string-array name="items"><item>A</item></string-array>
        </resources>'''
        de_xml = '''<resources>
            <plurals name="songs" translatable="false"><item quantity="one">Ein</item><item quantity="other">Viele</item></plurals>
            <string-array name="items" translatable="false"><item>A</item></string-array>
        </resources>'''
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("translatable='false' must not appear", out)

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
                   "%1$+x", "%1$,e", "%1$#g", "%1$(a", "%1$L", "%1$tJ", "%1$n",
                   "%1$2147483648s", "%1$.2147483648s", "%2147483648$s"]
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

    def test_leading_sentence_fragment_spacing_rejected(self):
        source = '<resources><string name="x"> leading fragment</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("sentence fragment", out)

    def test_metadata_separator_spacing_is_allowed(self):
        source = '<resources><string name="x_separator">  ·  </string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, out)


# ===========================================================================
# check_hardcoded_strings.py
# ===========================================================================

class TestCheckHardcodedStrings(unittest.TestCase):

    def setUp(self):
        self.chs = _load("chs_test", "tools/i18n/check_hardcoded_strings.py")
        self.tmpdir = Path(tempfile.mkdtemp())
        self.chs.SRC = self.tmpdir / "src"
        self.chs.SRC.mkdir()
        self.chs.ROOT = self.tmpdir
        self.chs.BASELINE = self.tmpdir / "baseline.txt"
        self.chs.SAFE_MANIFEST = self.tmpdir / "safe_literals.txt"
        self.chs.SAFE_MANIFEST.write_text(self.chs._serialize_safe({}))

    def _write_kt(self, name, content):
        path = self.chs.SRC / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def _args(self, base=None, bootstrap=True):
        class Args:
            pass
        args = Args()
        args.base = base
        args.bootstrap = bootstrap
        return args

    def test_inventory_is_mechanical_and_occurrence_aware(self):
        self._write_kt("Main.kt", '''package x
// "ignored comment"
val a = "Visible"
val b = "Visible"
val c = "SELECT * FROM channels"
val d = 'x'
''')
        inventory = self.chs._inventory()
        self.assertEqual(inventory[("src/Main.kt", "Visible")], 2)
        self.assertEqual(inventory[("src/Main.kt", "SELECT * FROM channels")], 1)
        self.assertFalse(any("ignored" in text for _, text in inventory))

    def test_inventory_does_not_guess_semantics(self):
        self._write_kt("Main.kt", 'package x\nfun f() { Log.d("TAG", "diagnostic"); Text("Update now") }\n')
        texts = {key[1] for key in self.chs._scan()}
        self.assertEqual(texts, {"TAG", "diagnostic", "Update now"})

    def test_nested_interpolation_literals_are_separate(self):
        source = 'package x\nval x = "Movies / ${title ?: "All"}"\n'
        self._write_kt("Screen.kt", source)
        texts = {key[1] for key in self.chs._inventory()}
        self.assertIn("All", texts)
        self.assertTrue(any("Movies /" in text for text in texts))

    def test_kotlin_escape_decoder_preserves_literal_backslashes(self):
        self.assertEqual(self.chs._decode(r'"\u0041"'), "A")
        self.assertEqual(self.chs._decode(r'"\\u0041"'), r"\u0041")
        self.assertEqual(self.chs._decode(r'"\n"'), "\n")
        self.assertEqual(self.chs._decode(r'"\\n"'), r"\n")

    def test_manifest_round_trips_backslashes_tabs_and_newlines(self):
        counts = {
            ("src/Main.kt", r"literal\nsequence"): 1,
            ("src/Main.kt", "actual\nnewline\tand\\slash"): 2,
        }
        self.assertEqual(self.chs._parse(self.chs._serialize(counts)), counts)
        entries = {key: (count, "technical") for key, count in counts.items()}
        safe, categories, errors = self.chs._parse_safe(self.chs._serialize_safe(entries))
        self.assertFalse(errors)
        self.assertEqual(safe, counts)
        self.assertEqual(set(categories.values()), {"technical"})

    def test_generate_classifies_every_non_safe_literal_as_baseline(self):
        self._write_kt("Main.kt", 'package x\nval a = "Hello"\nval b = "World"\n')
        self.assertEqual(self.chs.cmd_generate(None), 0)
        self.assertEqual(
            set(self.chs._parse(self.chs.BASELINE.read_text())),
            {("src/Main.kt", "Hello"), ("src/Main.kt", "World")},
        )

    def test_explicit_safe_entry_is_removed_from_baseline(self):
        self._write_kt("Main.kt", 'package x\nval tag = "Worker"\nval label = "Settings"\n')
        entries = {("src/Main.kt", "Worker"): (1, "log")}
        self.chs.SAFE_MANIFEST.write_text(self.chs._serialize_safe(entries))
        self.assertEqual(self.chs.cmd_generate(None), 0)
        baseline = self.chs._parse(self.chs.BASELINE.read_text())
        self.assertNotIn(("src/Main.kt", "Worker"), baseline)
        self.assertIn(("src/Main.kt", "Settings"), baseline)

    def test_bootstrap_requires_exact_baseline_plus_safe_inventory(self):
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\nval tag = "TAG"\n')
        self.chs.SAFE_MANIFEST.write_text(
            self.chs._serialize_safe({("src/Main.kt", "TAG"): (1, "log")})
        )
        self.assertEqual(self.chs.cmd_generate(None), 0)
        self.assertEqual(self.chs.cmd_verify(self._args()), 0)

    def test_unclassified_literal_fails_verification(self):
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\n')
        self.assertEqual(self.chs.cmd_generate(None), 0)
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\nval y = "New literal"\n')
        self.assertEqual(self.chs.cmd_verify(self._args()), 1)

    def test_stale_safe_literal_fails_verification_and_generation(self):
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\n')
        self.chs.SAFE_MANIFEST.write_text(
            self.chs._serialize_safe({("src/Main.kt", "Gone"): (1, "technical")})
        )
        self.chs.BASELINE.write_text(self.chs._serialize({("src/Main.kt", "Hello"): 1}))
        self.assertEqual(self.chs.cmd_verify(self._args()), 1)
        self.assertEqual(self.chs.cmd_generate(None), 1)

    def test_unknown_safe_category_is_rejected(self):
        text = self.chs._serialize_safe({}).replace(
            "\n\n", "\n1\tmagic\tsrc/Main.kt\tTAG\n\n", 1
        )
        _, _, errors = self.chs._parse_safe(text)
        self.assertTrue(any("unknown category" in error for error in errors))

    def test_merge_base_ratchet_rejects_baseline_growth(self):
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\n')
        self.assertEqual(self.chs.cmd_generate(None), 0)
        base = self.tmpdir / "base.txt"
        base.write_text(self.chs.BASELINE.read_text())
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\nval y = "New literal"\n')
        self.assertEqual(self.chs.cmd_generate(None), 0)
        self.assertEqual(self.chs.cmd_verify(self._args(str(base), bootstrap=False)), 1)

    def test_classify_safe_moves_literal_out_of_baseline(self):
        self._write_kt("Main.kt", 'package x\nval tag = "Worker"\n')
        self.assertEqual(self.chs.cmd_generate(None), 0)
        class Args:
            path = "src/Main.kt"
            text = "Worker"
            category = "log"
            count = None
        self.assertEqual(self.chs.cmd_classify_safe(Args()), 0)
        self.assertEqual(self.chs._parse(self.chs.BASELINE.read_text()), {})
        safe, categories, errors = self.chs._safe_entries()
        self.assertFalse(errors)
        self.assertEqual(safe[("src/Main.kt", "Worker")], 1)
        self.assertEqual(categories[("src/Main.kt", "Worker")], "log")

    def test_scanner_migration_policy_freezes_app_tree(self):
        workflow = (ROOT / ".github/workflows/i18n.yml").read_text()
        checker = (ROOT / "tools/i18n/check_hardcoded_strings.py").read_text()
        self.assertIn("verify-ci --base-sha", workflow)
        self.assertIn('"diff", "--name-only", base_sha, "HEAD", "--", "app/src/main"', checker)
        self.assertIn("Scanner migrations may not change app/src/main", checker)


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
# check_number_locale.py
# ===========================================================================

class TestCheckNumberLocale(unittest.TestCase):

    def setUp(self):
        self.checker = _load("number_locale_test", "tools/i18n/check_number_locale.py")
        self.tmpdir = Path(tempfile.mkdtemp())
        self.source = self.tmpdir / "Sample.kt"
        self.allowlist = self.tmpdir / "allowlist.txt"
        self.allowlist.write_text("")

    def _check(self, source):
        self.source.write_text(source)
        return self.checker.check([self.source], self.allowlist)[0]

    def test_root_locale_passes_for_static_and_extension_calls(self):
        errors = self._check('''
            val first = String.format(Locale.ROOT, "%d", value)
            val second = "%1$.2f".format(java.util.Locale.ROOT, nested(value, other))
        ''')
        self.assertEqual([], errors)

    def test_missing_and_non_root_locales_fail(self):
        for expression in (
            '"%d".format(value)',
            '"%d".format(Locale.getDefault(), value)',
            '"%d".format(Locale.US, value)',
            '"%d".format(locale, value)',
            'String.format("%d", value)',
        ):
            with self.subTest(expression=expression):
                self.assertTrue(self._check(f"val result = {expression}"))

    def test_reviewed_display_waiver_passes(self):
        self.source.write_text('val result = "%d".format(value)')
        path = self.source.as_posix()
        self.allowlist.write_text(f"DISPLAY\t{path}\t%d\t1\tLocalized value at a final UI renderer\n")
        errors, used = self.checker.check([self.source], self.allowlist)
        self.assertEqual([], errors)
        self.assertEqual({(path, "%d", 1)}, used)

    def test_hex_and_octal_are_mechanically_excluded(self):
        self.assertEqual([], self._check('''
            val hex = "%08x".format(value)
            val octal = String.format("%o", value)
        '''))

    def test_comments_and_strings_with_fake_calls_are_ignored(self):
        self.assertEqual([], self._check(r'''
            // "%d".format(value)
            /* String.format("%f", value) */
            val normal = "fake: \\"%d\\".format(value)"
            val triple = """fake: "%d".format(value)"""
        '''))

    def test_triple_and_interpolated_format_literals_are_scanned(self):
        errors = self._check(r'''
            val first = """count=$value %d""".format(Locale.ROOT, value)
            val second = "count=${value}: %d".format(Locale.ROOT, value)
        ''')
        self.assertEqual([], errors)

    def test_occurrence_distinguishes_identical_literals(self):
        self.source.write_text('''
            val first = "%d".format(Locale.ROOT, one)
            val second = "%d".format(two)
        ''')
        path = self.source.as_posix()
        self.allowlist.write_text(f"DISPLAY\t{path}\t%d\t2\tSecond call is localized display output\n")
        errors, used = self.checker.check([self.source], self.allowlist)
        self.assertEqual([], errors)
        self.assertEqual({(path, "%d", 2)}, used)


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
