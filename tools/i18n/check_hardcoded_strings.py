#!/usr/bin/env python3
"""Hardcoded-string baseline ratchet for OwnTV i18n (docs/internationalization.md 0d).

The plan extracts ~1,600 user-facing Kotlin string literals to Android resources across Phase 1.
This guard turns that extraction into a ratchet rather than a hope: a **generated, occurrence-aware
multiset** of every Kotlin string literal outside a small "safe category" list, checked into
``tools/i18n/hardcoded_baseline.txt``. Each Phase 1 batch deletes occurrences from it; at the end of
Phase 1 the file is empty and the check becomes an absolute "no literals outside safe categories".

Why a baseline and not a UAST lint rule: both approaches need the same safe-category list to be
useful, and the baseline reaches full coverage in a Python script with no lint-module wiring. Revisit
lint only if false positives become the bottleneck.

Two distinct CI failures (see the plan, ".github/workflows/i18n.yml"):
  1. regression  — a literal in the current code is absent from the merge-base baseline.
  2. over-baseline — the committed baseline contains an entry the current code does NOT produce,
     i.e. someone hand-padded the baseline to game the guard.

Identity = file path + normalised content + occurrence count (a multiset per (path, content)). Adding
a SECOND occurrence of an already-baselined ``"Try again"`` in the same file grows the count and
fails (regression), so the duplicate-occurrence test is covered.

Usage:
    python3 tools/i18n/check_hardcoded_strings.py            # regenerate the committed baseline
    python3 tools/i18n/check_hardcoded_strings.py verify \\
        --base   <(git show base:tools/i18n/hardcoded_baseline.txt)>

The script is deliberately conservative about exemptions: a mis-sized safe list that lets real
literals slip through is worse than no guard. ``require`` / ``check`` / ``error`` messages are
baselined by default; a confirmed developer-only assertion is exempt ONLY through the explicit,
reasoned allowlist at ``tools/i18n/assertion_allowlist.txt``.
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app" / "src" / "main" / "java"
BASELINE = ROOT / "tools" / "i18n" / "hardcoded_baseline.txt"
ASSERTION_ALLOWLIST = ROOT / "tools" / "i18n" / "assertion_allowlist.txt"

# --- string-literal extraction -------------------------------------------------

def _iter_literals(src: str):
    """Yield (start, end, raw_text) for every string LITERAL in [src].

    A small stateful scanner so that ``//`` line comments, ``/* */`` block comments and ``'c'`` char
    literals are correctly skipped — a plain regex would match quoted text *inside* comments and
    pollute the baseline. Triple-quoted strings may contain ``"`` and span lines.
    """
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i:i + 2]
        if nxt == "//":
            i = src.find("\n", i)
            if i == -1:
                return
            continue
        if nxt == "/*":
            j = src.find("*/", i + 2)
            i = (j + 2) if j != -1 else n
            continue
        if c == '\'' and i + 2 < n:
            # char literal: 'x' or '\x' ...
            if src[i + 1] == '\\':
                j = src.find("'", i + 2)
                # Java char escapes are at most a handful of chars; bound at i+8 to be safe.
                i = (j + 1) if (j != -1 and j - i <= 8) else i + 1
                continue
            if src[i + 2] == "'":
                i += 3
                continue
            i += 1
            continue
        if src[i:i + 3] == '"""':
            j = src.find('"""', i + 3)
            if j == -1:
                return
            yield i, j + 3, src[i:j + 3]
            i = j + 3
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"' or src[j] == '\n':
                    break
                j += 1
            # Unterminated on this line — skip to avoid hoovering up nothing.
            if j < n and src[j] == '"':
                yield i, j + 1, src[i:j + 1]
                i = j + 1
            else:
                i += 1
            continue
        i += 1


def _decode(raw: str) -> str:
    """Strip quotes and unescape common Kotlin escapes for a normalised comparison key."""
    if raw.startswith('"""'):
        body = raw[3:-3]
    else:
        body = raw[1:-1]
    # Kotlin escapes: \n \t \r \\ \" \$ \' \uXXXX — keep it lossy: we only need a stable key.
    body = (body.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r")
            .replace('\\"', '"').replace("\\'", "'").replace("\\$", "$")
            .replace("\\\\", "\\"))
    body = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), body)
    return body


def _normalize(content: str) -> str:
    # Collapse interior whitespace and trim, so reformatting does not shake the baseline. A literal is
    # the same sentence irrespective of how it was wrapped.
    return re.sub(r"\s+", " ", content).strip()


# --- safe-category detection ---------------------------------------------------

_LOG_CALL = re.compile(r"\b(?:android\.util\.Log|Log|Timber)\.[devwifws]+\s*\(")
_LOG_TAG_DECL = re.compile(r"\b(const\s+)?val\s+\w*(?:TAG|[Tt]ag)\w*\s*(?::\s*\w+\s*)?=\s*\"")
_REGEX_CALL = re.compile(r"\bRegex\s*\(")
_SQL = re.compile(r"\b(SELECT |INSERT INTO |UPDATE |DELETE FROM |CREATE TABLE |CREATE INDEX |ALTER TABLE |DROP TABLE )",
                   re.IGNORECASE)
_MIME = re.compile(r"^[a-z][\w.+-]+/[a-z0-9][\w.+-]*$")
_URL = re.compile(r"^(?:https?|content|file|intent|mailto|tel|ftp|data)://")
# BCP-47 language/region/script tag (en, en-US, zh-Hans, pt-BR, en-rGB …). The Android res qualifier
# form en-rGB is also matched so the locale-runtime constants are safe.
_BCP47 = re.compile(r"^[a-z]{2}(?:-[A-Z][a-z]{3}|-r[A-Z]{2}|-[A-Z]{2})?(?:-[A-Z]{2})?$")
# A JSON object/fragment key written as a literal: "key": or "key" : — translator never sees these.
# This catches inline JSON like "key": value but NOT json.put("key", value) where the literal is
# just the key string (the ": is outside the quotes). The .put() form is caught by _JSON_API below.
_JSON_KEY = re.compile(r'^"[A-Za-z_][\w-]*"\s*:')
# JSON API calls where the first string argument is a field name, not display text:
#   json.put("profileId", id), obj.optString("section"), root.optJSONObject("settings"), etc.
# Also matches bare put("key", ...) inside JSONObject().apply { } blocks where there is no leading dot.
# The prefix (?:^|\W) matches both .method( and bare method( at a statement/call boundary, but NOT
# word_method( (where a word char precedes, e.g. some_custom_put). The string literal is the key —
# a protocol field the translator never sees.
_JSON_API = re.compile(r"(?:^|\W)(?:put|putOpt|get|getOpt|getString|optString|getInt|optInt|getBoolean|optBoolean|getLong|optLong|getDouble|optDouble|getJSONObject|optJSONObject|getJSONArray|optJSONArray|opt|remove|has)\s*\(")
# A snake_case / kebab-case identifier that looks like a preference/DataStore key or protocol field,
# not a sentence: lowercase, digits, underscores/hyphens/dots, no spaces, and not a readable phrase.
# Require at least one underscore/dot/hyphen so a bare word like "Settings" is NOT misclassified.
_IDENT_KEY = re.compile(r"^[a-z][a-z0-9_./-]*[_./-][a-z0-9_./-]*$")
# A filesystem-ish path (contains a slash and no spaces).
_PATH = re.compile(r"^[^\s]*[/\\][^\s]*$")
# File extension or a dotted protocol token like ".mp4", "application/json", "owntv_locale".
_DOTTED = re.compile(r"^(?:\.[a-z0-9]+|[a-z][a-z0-9]*(?:\.[a-z0-9]+)+)$")
# Perf.stamp() call arguments — logcat timing markers, never user-facing text.
_PERF_STAMP = re.compile(r"\bPerf\.stamp\s*\(")
# @Suppress(...) annotation arguments — compiler directive args, never user-facing text.
_SUPPRESS_ANN = re.compile(r"@Suppress\s*\(")
# Room entity annotation arguments: Index("col"), ForeignKey(childColumns = ["col"]), etc.
# These are database column names, not display text.
_ROOM_ANN = re.compile(r"\b(?:Index|ForeignKey|Entity|PrimaryKey|ColumnInfo|Embedded|Relation|Junction)\s*\(")
# Column-name array contexts: childColumns = ["col"], parentColumns = ["col"], columnNames = ["col"].
# Also primaryKeys, indices value arrays — all database column identifiers.
_COL_ARRAY = re.compile(r"\b(?:childColumns|parentColumns|columnNames|columns|value|primaryKeys|indices)\s*=\s*\[")
# URI query parameter APIs: .appendQueryParameter("key", ...), .appendOptionalQueryParameter("key", ...),
# uri.queryLong("key"), uri.getQueryParameter("key") — the string is a URL parameter name.
_URI_API = re.compile(r"\.(?:appendQueryParameter|appendOptionalQueryParameter|getQueryParameter|queryLong|queryString|queryInt|queryBool|queryDouble)\s*\(")
# A const val KEY_... = "..." declaration — the value is a preference/DataStore/Worker key.
_KEY_CONST = re.compile(r"\bconst\s+val\s+KEY_\w+\s*=")
# ErrorMessages.kt comparison needles: string literals passed as arguments to containsAny() or
# .contains() are stable English keys used for matching, never display text. The friendly return
# values (the RHS of ->) are NOT needles and must be extracted to resources in Phase 1.
_ERROR_MESSAGES_FILE = "app/src/main/java/tv/own/owntv/core/util/ErrorMessages.kt"
_NEEDLE_CALL = re.compile(r"\.(?:containsAny|contains)\s*\(")


def _needle_positions(text: str) -> set[int]:
    """Character positions inside .containsAny()/.contains() argument lists in ErrorMessages.kt.

    Multi-line calls (where the opening paren is on a different line than the needle string literals)
    are handled by paren-depth tracking: every character between the opening paren and its matching
    close is recorded. A literal whose start position falls in that range is a comparison needle.
    """
    positions: set[int] = set()
    for m in _NEEDLE_CALL.finditer(text):
        depth = 1
        pos = m.end()
        while pos < len(text) and depth > 0:
            ch = text[pos]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            if depth > 0:
                positions.add(pos)
            pos += 1
    return positions


def _statement_text(src: str, pos: int) -> str:
    """A rough slice from the previous statement separator to the literal position."""
    start = max(src.rfind(";", 0, pos), src.rfind("\n", 0, pos), 0) + 1
    end = src.find("\n", pos)
    if end == -1:
        end = len(src)
    return src[start:end]


def _is_safe(rel_path: str, content: str, stmt: str, line: str, allowlist: set[tuple[str, str]],
             start: int = -1, needles: set[int] | None = None) -> bool:
    norm = _normalize(content)
    # Explicit, reasoned assertion allowlist (developer-only require/check/error) by file+content.
    if (rel_path, norm) in allowlist:
        return True
    # Empty strings are never user-facing text.
    if norm == "":
        return True
    # ErrorMessages.kt: only the comparison NEEDLES (literals inside .containsAny()/.contains() calls)
    # are safe — stable English keys used for matching. The friendly return-value messages are
    # user-facing text that Phase 1 must extract, so they are NOT exempted. Needle positions are
    # pre-computed by _needle_positions() with paren-depth tracking for multi-line calls.
    if rel_path == _ERROR_MESSAGES_FILE and needles is not None and start in needles:
        return True
    # Log tags declared as constants.
    if _LOG_TAG_DECL.search(line):
        return True
    # Arguments to Log./Timber. calls (both the tag and the message are developer-only, never user text).
    if _LOG_CALL.search(stmt):
        return True
    # Regex patterns.
    if _REGEX_CALL.search(stmt):
        return True
    # SQL fragments.
    if _SQL.search(content):
        return True
    # MIME types.
    if _MIME.match(content):
        return True
    # URLs/schemes.
    if _URL.match(content):
        return True
    # BCP-47 language/region/script tags and Android res qualifiers (en, en-US, zh-Hans, pt-BR, en-rGB).
    if _BCP47.match(content):
        return True
    # JSON object keys written inline ("key": value).
    if _JSON_KEY.match(content):
        return True
    # JSON API field-name arguments: json.put("profileId", id), obj.optString("section"), etc.
    # The string literal is a protocol field name, not display text. The statement slice includes
    # the .put( / .optString( / .getJSONObject( call, so a string argument in that position is safe.
    if _JSON_API.search(stmt):
        return True
    # Filesystem-ish paths (contain a slash, no spaces).
    if _PATH.match(content) and " " not in content:
        return True
    # Dotted protocol tokens / file extensions: application/json, .mp4, owntv.db.bak.
    if _DOTTED.match(content):
        return True
    # snake_case / kebab-case / dotted preference or DataStore keys (have a separator, no spaces).
    if _IDENT_KEY.match(content) and " " not in content:
        return True
    # Perf.stamp() arguments — logcat timing markers (db-probed, first-composition, etc.).
    if _PERF_STAMP.search(stmt):
        return True
    # @Suppress(...) annotation arguments — compiler directive args (UNCHECKED_CAST, etc.).
    if _SUPPRESS_ANN.search(line) or _SUPPRESS_ANN.search(stmt):
        return True
    # Room entity annotation arguments: Index("sourceId"), ForeignKey(childColumns = ["sourceId"]).
    # Database column names are never user-facing text.
    if _ROOM_ANN.search(stmt) or _ROOM_ANN.search(line):
        return True
    # Column-name array contexts: childColumns = ["col"], parentColumns = ["col"], primaryKeys = ["col"].
    if _COL_ARRAY.search(stmt) or _COL_ARRAY.search(line):
        return True
    # URI query parameter APIs: .appendQueryParameter("key", ...), uri.queryLong("key").
    if _URI_API.search(stmt):
        return True
    # const val KEY_... = "..." — the value is a preference/DataStore/Worker key.
    if _KEY_CONST.search(line):
        return True
    return False


def _load_assertion_allowlist() -> set[tuple[str, str]]:
    out: set[tuple[str, str]] = set()
    if not ASSERTION_ALLOWLIST.is_file():
        return out
    for raw in ASSERTION_ALLOWLIST.read_text(encoding="utf-8").splitlines():
        s = raw.strip()
        if not s or s.startswith("#"):
            continue
        # Format: <relative path> \t <one-line reason> \t <content>  (tab-separated; content last).
        parts = s.split("\t")
        if len(parts) < 3:
            continue
        rel, _reason, content = parts[0], parts[1], "\t".join(parts[2:])
        out.add((rel, _normalize(content)))
    return out


_GENERATED_MARKER = "// DO NOT EDIT — generated"


def _scan() -> dict[tuple[str, str], int]:
    """Return the multiset {(rel_path, normalised_content): occurrence_count} of unsafe literals."""
    allowlist = _load_assertion_allowlist()
    counts: Counter = Counter()
    for kt in sorted(SRC.rglob("*.kt")):
        rel = kt.relative_to(ROOT).as_posix()
        text = kt.read_text(encoding="utf-8")
        # Skip generated Kotlin (e.g. SupportedLocales.kt) — its literals are catalogue data, not code.
        if _GENERATED_MARKER in text[:120]:
            continue
        lines = text.splitlines()
        # Pre-compute needle positions for ErrorMessages.kt so multi-line containsAny/contains calls
        # are correctly identified (the line-based _statement_text would miss them).
        needles = _needle_positions(text) if rel == _ERROR_MESSAGES_FILE else set()
        for start, end, raw in _iter_literals(text):
            content = _decode(raw)
            line_no = text.count("\n", 0, start)
            line = lines[line_no] if line_no < len(lines) else ""
            stmt = _statement_text(text, start)
            if _is_safe(rel, content, stmt, line, allowlist, start=start, needles=needles):
                continue
            counts[(rel, _normalize(content))] += 1
    return dict(counts)


def _serialize(counts: dict[tuple[str, str], int]) -> str:
    lines = [
        "# DO NOT EDIT by hand — generated by tools/i18n/check_hardcoded_strings.py.",
        "# One tab-separated line per (file, normalised content) with its occurrence count.",
        "# Phase 1 deletes occurrences until this file is empty; the CI guard then becomes absolute.",
        "# Format: <count>\\t<relative path>\\t<content with \\t/\\n escaped>",
        "",
    ]
    for (rel, content), count in sorted(counts.items(), key=lambda kv: (kv[0][0], kv[0][1])):
        esc = content.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
        lines.append(f"{count}\t{rel}\t{esc}")
    return "\n".join(lines) + "\n"


def _parse(text: str) -> dict[tuple[str, str], int]:
    out: Counter = Counter()
    for raw in text.splitlines():
        s = raw.rstrip("\n")
        if not s or s.startswith("#"):
            continue
        parts = s.split("\t", 2)
        if len(parts) < 3:
            continue
        count, rel, esc = parts
        try:
            n = int(count)
        except ValueError:
            continue
        content = esc.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
        out[(rel, content)] = n
    return dict(out)


def _subset(a: dict, b: dict) -> list[str]:
    """Return reasons for every (rel,content) in [a] whose count exceeds [b]; empty means a ⊆ b."""
    reasons = []
    for key, ca in a.items():
        cb = b.get(key, 0)
        if ca > cb:
            rel, content = key
            reasons.append(f"new/extra literal in {rel}: count {ca} > base {cb} :: {content[:80]!r}")
    return reasons


def cmd_generate(_args) -> int:
    counts = _scan()
    BASELINE.write_text(_serialize(counts), encoding="utf-8")
    total = sum(counts.values())
    print(f"baseline written: {BASELINE.relative_to(ROOT)} ({len(counts)} unique, {total} occurrences)")
    return 0


def cmd_verify(args) -> int:
    base = _parse(Path(args.base).read_text(encoding="utf-8")) if args.base else {}
    current = _scan()
    committed = _parse(BASELINE.read_text(encoding="utf-8"))
    fails = 0
    # The regression leg compares the current scan against the merge-base baseline. On the PR that
    # INTRODUCES the baseline file, the merge base has no such file — the workflow passes --bootstrap,
    # which skips this leg (there is no prior baseline to regress against). The other two legs still
    # apply, so on the introducing PR the committed baseline must exactly match the current scan.
    if not args.bootstrap:
        reg = _subset(current, base)
        if reg:
            fails += 1
            print("REGRESSION — current code has literals absent from the merge-base baseline:")
            for r in reg[:50]:
                print("  " + r)
            if len(reg) > 50:
                print(f"  ... and {len(reg) - 50} more")
    over = _subset(committed, current)
    if over:
        fails += 1
        print("OVER-BASELINE — committed baseline has entries the current code does NOT produce:")
        for r in over[:50]:
            print("  " + r)
        if len(over) > 50:
            print(f"  ... and {len(over) - 50} more")
    # The third leg: every literal the current code produces must be IN the committed baseline.
    # Without this a PR can delete every committed baseline entry while leaving all literals in
    # source and still pass (the other two legs only compare against the merge base / current scan).
    # The committed baseline is the ratchet's record of what is still permitted; it must stay a
    # superset of the current scan so shrinking it actually requires deleting a literal from source.
    stale = _subset(current, committed)
    if stale:
        fails += 1
        print("STALE BASELINE — current code has literals absent from the COMMITTED baseline;")
        print("  regenerate the baseline (tools/i18n/check_hardcoded_strings.py generate) only AFTER")
        print("  confirming each new literal was extracted to a resource, not merely added to the file.")
        for r in stale[:50]:
            print("  " + r)
        if len(stale) > 50:
            print(f"  ... and {len(stale) - 50} more")
    if fails == 0:
        if args.bootstrap:
            print("i18n baseline OK (bootstrap): committed baseline exactly matches current scan.")
        else:
            print("i18n baseline OK: current ⊆ merge-base, committed ⊆ current, and current ⊆ committed.")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("generate")
    v = sub.add_parser("verify")
    v.add_argument("--base", help="path to the merge-base baseline file")
    v.add_argument("--bootstrap", action="store_true",
                   help="skip the merge-base regression leg (for the PR that introduces the baseline)")
    args = ap.parse_args()
    if args.cmd == "generate":
        return cmd_generate(args)
    return cmd_verify(args)


if __name__ == "__main__":
    raise SystemExit(main())