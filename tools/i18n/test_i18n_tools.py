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

    def _run(self, res, locales_json):
        self.vs.RES = res
        self.vs.LOCALES_JSON = locales_json
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = self.vs.main()
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

    def test_untranslated_tier1_identical_to_source(self):
        """A Tier 1 translation identical to the source is likely untranslated."""
        source = '<resources><string name="hello">Hello world</string></resources>'
        de_xml = '<resources><string name="hello">Hello world</string></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=True, pickerVisible=True)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("identical to source", out)

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
