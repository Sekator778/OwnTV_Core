#!/usr/bin/env python3
"""Hardcoded-string baseline ratchet for OwnTV i18n (docs/internationalization.md 0d).

The plan extracts ~1,600 user-facing Kotlin string literals to Android resources across Phase 1.
This guard turns that extraction into a ratchet rather than a hope: a **generated, occurrence-aware
multiset** of every Kotlin string literal outside a small "safe category" list, checked into
``tools/i18n/hardcoded_baseline.txt``. Each Phase 1 batch deletes occurrences from it; at the end of
Phase 1 the file is empty and the check becomes an absolute "no literals outside safe categories".

**Invariant: a safe category exempts ONLY the literal in the argument position, never every literal
on the same line/statement.** A line like ``json.put("title", "Visible label")`` exempts ``"title"``
(the JSON field name) but NOT ``"Visible label"`` (user-facing text). This is enforced by
position-based argument detection (``_call_arg_positions``), the same technique used for
ErrorMessages.kt comparison needles — every literal whose start position falls inside the first
argument of a safe call is exempted, and no other.

Identity = file path + normalised content + occurrence count (a multiset per (path, content)). Adding
a SECOND occurrence of an already-baselined ``"Try again"`` in the same file grows the count and
fails (regression), so the duplicate-occurrence test is covered.

Usage:
    python3 tools/i18n/check_hardcoded_strings.py generate
    python3 tools/i18n/check_hardcoded_strings.py verify \\
        --base   <(git show base:tools/i18n/hardcoded_baseline.txt)>
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
# Increment only when the literal extraction/safety semantics change. A changed scanner needs one
# explicit baseline migration; ordinary Phase 1 code changes must continue to satisfy the merge-base
# ratchet without a silent escape hatch.
SCANNER_VERSION = 2

# --- string-literal extraction -------------------------------------------------

def _iter_literals(src: str):
    """Yield (start, end, raw_text) for every string LITERAL in [src].

    A small stateful scanner so that ``//`` line comments, ``/* */`` block comments and ``'c'`` char
    literals are correctly skipped — a plain regex would match quoted text *inside* comments and
    pollute the baseline. Triple-quoted strings may contain ``"`` and span lines.

    **Nested quotes inside string interpolation** are handled: when a ``"`` is encountered inside a
    ``${...}`` interpolation expression, the scanner tracks brace depth and treats the interpolated
    ``"..."`` as a separate literal, so ``"Movies / ${title ?: "All"}"`` yields TWO literals:
    ``"Movies / ${title ?: "All"}"`` (the outer, with the interpolation hole) and ``"All"`` (the
    inner). Without this, changing the inner ``"All"`` to ``"Everything"`` would leave the baseline
    unchanged — the old inner literal would never be scanned independently.
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
            # Yield the triple-quoted literal, then scan its interior for nested "..." literals
            # inside ${...} interpolation expressions.
            outer = src[i:j + 3]
            yield i, j + 3, outer
            for ns, ne, nraw in _nested_literals(i, outer):
                yield ns, ne, nraw
            i = j + 3
            continue
        if c == '"':
            j, end = _scan_double_quoted(src, i, n)
            if end is not None:
                outer = src[i:end]
                yield i, end, outer
                for ns, ne, nraw in _nested_literals(i, outer):
                    yield ns, ne, nraw
                i = end
            else:
                i += 1
            continue
        i += 1


def _scan_double_quoted(src: str, i: int, n: int) -> tuple[int, int | None]:
    """Find the end of a ``"..."`` literal starting at [i], respecting escapes and ${} interpolation.

    Returns (j, end) where end is one past the closing quote, or (j, None) if unterminated on this
    line. Inside ``${...}``, braces are tracked so the closing ``}`` is found correctly, and any
    nested ``"..."`` inside the interpolation is skipped (it is yielded separately by _yield_nested).
    """
    j = i + 1
    while j < n:
        c = src[j]
        if c == '\\':
            j += 2
            continue
        if c == '\n':
            return j, None  # unterminated on this line
        if c == '"':
            return j, j + 1
        if c == '$' and j + 1 < n and src[j + 1] == '{':
            # Skip the ${...} interpolation block, tracking brace depth so nested {} are handled.
            # Any "..." inside is a nested literal yielded separately by _yield_nested.
            depth = 1
            j += 2
            while j < n and depth > 0:
                cj = src[j]
                if cj == '\\':
                    j += 2
                    continue
                if cj == '{':
                    depth += 1
                    j += 1
                    continue
                if cj == '}':
                    depth -= 1
                    j += 1
                    continue
                if cj == '"':
                    # Skip a nested "..." inside the interpolation — it's a separate literal.
                    k = j + 1
                    while k < n:
                        ck = src[k]
                        if ck == '\\':
                            k += 2
                            continue
                        if ck == '"':
                            k += 1
                            break
                        k += 1
                    j = k
                    continue
                j += 1
            continue
        j += 1
    return j, None


def _nested_literals(outer_start: int, outer: str):
    """Yield nested ``"..."`` literals inside ``${...}`` interpolation of [outer].

    [outer_start] is the source position of the outer literal's first char; [outer] is the raw text.
    We scan the body (between the quotes) for ``${`` and emit every ``"..."`` found inside the brace
    block as a separate literal with a start position relative to [outer_start]. This makes the
    baseline sensitive to changes in interpolated string values.
    """
    if outer.startswith('"""'):
        body = outer[3:-3]
        body_off = outer_start + 3
    else:
        body = outer[1:-1]
        body_off = outer_start + 1
    i, n = 0, len(body)
    while i < n:
        if body[i] == '$' and i + 1 < n and body[i + 1] == '{':
            depth = 1
            i += 2
            while i < n and depth > 0:
                c = body[i]
                if c == '\\':
                    i += 2
                    continue
                if c == '{':
                    depth += 1
                    i += 1
                    continue
                if c == '}':
                    depth -= 1
                    i += 1
                    continue
                if c == '"':
                    # A nested string literal inside the interpolation — emit it.
                    j = i + 1
                    while j < n:
                        cj = body[j]
                        if cj == '\\':
                            j += 2
                            continue
                        if cj == '"':
                            j += 1
                            break
                        j += 1
                    raw = body[i:j]
                    start = body_off + i
                    end = body_off + j
                    yield start, end, raw
                    i = j
                    continue
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


# --- safe-category detection (position-based) ----------------------------------

_LOG_CALL = re.compile(r"\b(?:android\.util\.Log|Log|Timber)\.[devwifws]+\s*\(")
# Declaration patterns are used positionally below. They deliberately stop at the assignment rather
# than exempting every literal on the declaration's line.
_LOG_TAG_DECL = re.compile(
    r"\b(?:const\s+)?val\s+\w*(?:TAG|[Tt]ag)\w*[ \t]*(?::[ \t]*[A-Za-z_]\w*(?:[<>,.? ]*)[ \t]*)?=[ \t]*"
)
_REGEX_CALL = re.compile(r"\bRegex\s*\(")
_SQL = re.compile(r"\b(SELECT |INSERT INTO |UPDATE |DELETE FROM |CREATE TABLE |CREATE INDEX |ALTER TABLE |DROP TABLE )",
                   re.IGNORECASE)
_MIME = re.compile(r"^[a-z][\w.+-]+/[a-z0-9][\w.+-]*$")
_URL = re.compile(r"^(?:https?|content|file|intent|mailto|tel|ftp|data)://")
# BCP-47 language/region/script tags and Android's b+ resource form. Do not treat every short
# lowercase word as a language tag: ``now``, ``one`` and ``own`` are ordinary UI/protocol words.
# Language-only tags are limited to the app's catalogue; qualified tags retain shape validation.
_BCP47_LANGUAGE_ONLY = {
    "ar", "cs", "da", "de", "en", "es", "fr", "it", "ja", "ko", "nb", "nl", "pl", "pt",
    "ru", "sv", "tr", "zh",
}
_BCP47_QUALIFIED = re.compile(
    r"^(?:[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-(?:[A-Z]{2}|[0-9]{3})|-r[A-Z]{2})"
    r"|b\+[a-z]{2,3}(?:\+(?:[A-Z][a-z]{3}|[A-Z]{2}|[0-9]{3}))*)$"
)

def _is_bcp47(content: str) -> bool:
    return content in _BCP47_LANGUAGE_ONLY or bool(_BCP47_QUALIFIED.fullmatch(content))
# A JSON object/fragment key written as a literal: "key": or "key" : (inline JSON, not .put()).
_JSON_KEY = re.compile(r'^"[A-Za-z_][\w-]*"\s*:')
# A snake_case / kebab-case identifier — preference/DataStore key or protocol field, not a sentence.
_IDENT_KEY = re.compile(r"^[a-z][a-z0-9_./-]*[_./-][a-z0-9_./-]*$")
# A filesystem-ish path (contains a slash and no spaces).
_PATH = re.compile(r"^[^\s]*[/\\][^\s]*$")
# File extension or a dotted protocol token like ".mp4", "application/json", "owntv_locale".
_DOTTED = re.compile(r"^(?:\.[a-z0-9]+|[a-z][a-z0-9]*(?:\.[a-z0-9]+)+)$")
# Perf.stamp() call arguments — logcat timing markers, never user-facing text.
_PERF_STAMP = re.compile(r"\bPerf\.stamp\s*\(")
# @Suppress(...) annotation arguments — compiler directive args, never user-facing text.
_SUPPRESS_ANN = re.compile(r"@Suppress\s*\(")
# A const val KEY_... = "..." declaration — the value is a preference/DataStore/Worker key.
_KEY_CONST = re.compile(r"\bconst\s+val\s+KEY_\w+[ \t]*(?::[ \t]*[A-Za-z_]\w*(?:[<>,.? ]*)[ \t]*)?=[ \t]*")

# Call patterns whose FIRST string argument is a safe identifier (JSON field name, URI parameter,
# Room column name, etc.). These are used with _call_arg_positions() so ONLY the literal at the
# first-argument position is exempted — never other literals on the same line/statement.
_JSON_API = re.compile(r"(?:^|\W|\.)(?:put|putOpt|getOpt|getString|optString|getInt|optInt|getBoolean|optBoolean|getLong|optLong|getDouble|optDouble|getJSONObject|optJSONObject|getJSONArray|optJSONArray|opt|remove|has)\s*\(")
_JSON_GET = re.compile(r"\.(?:get|opt)\s*\(")  # .get("key") / .opt("key") — but NOT AtomicInteger.get()
_URI_API = re.compile(r"\.(?:appendQueryParameter|appendOptionalQueryParameter|getQueryParameter|queryLong|queryString|queryInt|queryBool|queryDouble)\s*\(")
# Room: Index("col"), ColumnInfo(name="col"), Index(value=["col"]) — the first string arg is a column.
# ForeignKey(childColumns=["col"]) is handled by _COL_ARRAY below (array position, not call arg).
_ROOM_CALL = re.compile(r"\b(?:Index|ColumnInfo)\s*\(")
# Column-name array contexts: childColumns = ["col"], parentColumns = ["col"], primaryKeys = ["col"].
# These are arrays of column-name literals; _col_array_positions() yields positions inside the [].
_COL_ARRAY = re.compile(r"\b(?:childColumns|parentColumns|columnNames|columns|primaryKeys)\s*=\s*\[")

# ErrorMessages.kt comparison needles: string literals inside .containsAny()/.contains() calls.
_ERROR_MESSAGES_FILE = "app/src/main/java/tv/own/owntv/core/util/ErrorMessages.kt"
_NEEDLE_CALL = re.compile(r"\.(?:containsAny|contains)\s*\(")


def _call_arg_positions(text: str, call_re: re.Pattern) -> set[int]:
    """Character positions of the FIRST string-literal argument of every call matching [call_re].

    For each match, this finds the opening paren, then scans forward (tracking paren/brace depth)
    to the first ``"`` that begins a string literal at depth 1 inside the call. Only that literal's
    start position is recorded — so ``json.put("title", "Visible label")`` records the position of
    ``"title"`` but NOT ``"Visible label"``. This is the invariant: a safe call exempts ONLY its
    first string argument, never every literal on the same line.
    """
    positions: set[int] = set()
    for m in call_re.finditer(text):
        paren_pos = text.find("(", m.start())
        if paren_pos == -1:
            continue
        depth = 1
        pos = paren_pos + 1
        while pos < len(text) and depth > 0:
            c = text[pos]
            if c == '\\':
                pos += 2
                continue
            if c == '(':
                depth += 1
                pos += 1
                continue
            if c == ')':
                depth -= 1
                pos += 1
                continue
            if depth == 1 and c == '"':
                # Found the first string literal at argument depth — record and stop.
                positions.add(pos)
                break
            pos += 1
    return positions


def _call_all_arg_positions(text: str, call_re: re.Pattern) -> set[int]:
    """Character positions of EVERY string-literal argument of every call matching [call_re].

    Used for .containsAny()/.contains() (ErrorMessages needles) where all string arguments are
    comparison keys, and for Room Index(value=["col", "col"]) where all array elements are column
    names. Each recorded position is the start of a ``"`` literal inside the call at depth ≥ 1.
    """
    positions: set[int] = set()
    for m in call_re.finditer(text):
        paren_pos = text.find("(", m.start())
        if paren_pos == -1:
            continue
        depth = 1
        pos = paren_pos + 1
        while pos < len(text) and depth > 0:
            c = text[pos]
            if c == '\\':
                pos += 2
                continue
            if c == '(':
                depth += 1
                pos += 1
                continue
            if c == ')':
                depth -= 1
                pos += 1
                continue
            if c == '"':
                positions.add(pos)
                # Skip to the end of this literal so we don't re-record inner content.
                k = pos + 1
                while k < len(text):
                    ck = text[k]
                    if ck == '\\':
                        k += 2
                        continue
                    if ck == '"':
                        k += 1
                        break
                    k += 1
                pos = k
                continue
            pos += 1
    return positions


def _col_array_positions(text: str) -> set[int]:
    """Character positions of string literals inside column-name arrays (childColumns=[...], etc.)."""
    positions: set[int] = set()
    for m in _COL_ARRAY.finditer(text):
        bracket_pos = text.find("[", m.start())
        if bracket_pos == -1:
            continue
        depth = 1
        pos = bracket_pos + 1
        while pos < len(text) and depth > 0:
            c = text[pos]
            if c == '[':
                depth += 1
                pos += 1
                continue
            if c == ']':
                depth -= 1
                pos += 1
                continue
            if c == '"':
                positions.add(pos)
                k = pos + 1
                while k < len(text):
                    ck = text[k]
                    if ck == '\\':
                        k += 2
                        continue
                    if ck == '"':
                        k += 1
                        break
                    k += 1
                pos = k
                continue
            pos += 1
    return positions


def _annotation_arg_positions(text: str, ann_re: re.Pattern) -> set[int]:
    """Character positions of string arguments inside an annotation's parens (e.g. @Suppress(...))."""
    return _call_all_arg_positions(text, ann_re)


def _declaration_string_positions(text: str, declaration_re: re.Pattern) -> set[int]:
    """Opening positions of direct string initializers matched by [declaration_re].

    TAG and KEY constants are developer/protocol identifiers, but only their initializer is safe.
    Restricting this to the first quote immediately after ``=`` prevents an unrelated literal on the
    same statement from inheriting the declaration's exemption.
    """
    positions: set[int] = set()
    for m in declaration_re.finditer(text):
        pos = m.end()
        if pos < len(text) and text[pos] == '"':
            positions.add(pos)
    return positions


def _statement_text(src: str, pos: int) -> str:
    """A rough slice from the previous statement separator to the literal position."""
    start = max(src.rfind(";", 0, pos), src.rfind("\n", 0, pos), 0) + 1
    end = src.find("\n", pos)
    if end == -1:
        end = len(src)
    return src[start:end]


def _is_safe(rel_path: str, content: str, stmt: str, line: str, allowlist: set[tuple[str, str]],
             start: int = -1, safe_positions: set[int] | None = None) -> bool:
    norm = _normalize(content)
    # Explicit, reasoned assertion allowlist (developer-only require/check/error) by file+content.
    if (rel_path, norm) in allowlist:
        return True
    # Empty strings are never user-facing text.
    if norm == "":
        return True
    # Position-based safe categories: ONLY the literal at a safe call's argument position is exempt.
    # This is the core invariant — a safe call on the same line does NOT exempt other literals.
    if safe_positions is not None and start in safe_positions:
        return True
    # Log/Regex arguments and TAG/KEY initializers are all handled by exact character positions in
    # _compute_safe_positions. There is intentionally no statement/line-level fallback here: an
    # unrelated literal next to a safe call must remain in the baseline.
    # SQL fragments (content-based: a fragment containing SELECT/INSERT/etc. is SQL).
    if _SQL.search(content):
        return True
    # MIME types.
    if _MIME.match(content):
        return True
    # URLs/schemes.
    if _URL.match(content):
        return True
    # BCP-47 language/region/script tags and Android res qualifiers.
    if _is_bcp47(content):
        return True
    # JSON object keys written inline ("key": value).
    if _JSON_KEY.match(content):
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
    return False


def _load_assertion_allowlist() -> set[tuple[str, str]]:
    out: set[tuple[str, str]] = set()
    if not ASSERTION_ALLOWLIST.is_file():
        return out
    for raw in ASSERTION_ALLOWLIST.read_text(encoding="utf-8").splitlines():
        s = raw.strip()
        if not s or s.startswith("#"):
            continue
        parts = s.split("\t")
        if len(parts) < 3:
            continue
        rel, _reason, content = parts[0], parts[1], "\t".join(parts[2:])
        out.add((rel, _normalize(content)))
    return out


_GENERATED_MARKER = "// DO NOT EDIT — generated"


def _compute_safe_positions(text: str, rel_path: str) -> set[int]:
    """All character positions of literals that are safe by CALL ARGUMENT position.

    This is the position-based enforcement of the invariant: a safe call exempts ONLY its string
    argument(s), never every literal on the same line. Aggregates:
      - JSON API first-arg positions (.put/.getString/.optJSONObject/... — first string arg only)
      - URI API first-arg positions (.appendQueryParameter/.queryLong/... — first string arg only)
      - Room Index()/ColumnInfo() first-arg positions (column name)
      - Room column-name array positions (childColumns=[...], primaryKeys=[...])
      - Perf.stamp() first-arg positions
      - @Suppress() all-arg positions
      - Regex() first-arg positions
      - Log./Timber. all-arg positions (tag + message are both developer-only)
      - TAG and KEY constant initializers
      - ErrorMessages.kt .containsAny()/.contains() all-arg positions (comparison needles)
    """
    safe: set[int] = set()
    safe |= _call_arg_positions(text, _JSON_API)
    safe |= _call_arg_positions(text, _JSON_GET)
    safe |= _call_arg_positions(text, _URI_API)
    safe |= _call_arg_positions(text, _ROOM_CALL)
    safe |= _col_array_positions(text)
    safe |= _call_arg_positions(text, _PERF_STAMP)
    safe |= _annotation_arg_positions(text, _SUPPRESS_ANN)
    safe |= _call_arg_positions(text, _REGEX_CALL)
    safe |= _call_all_arg_positions(text, _LOG_CALL)
    safe |= _declaration_string_positions(text, _LOG_TAG_DECL)
    safe |= _declaration_string_positions(text, _KEY_CONST)
    if rel_path == _ERROR_MESSAGES_FILE:
        safe |= _call_all_arg_positions(text, _NEEDLE_CALL)
    return safe


def _expand_nested_safe_positions(text: str, safe_positions: set[int]) -> set[int]:
    """Propagate a safe argument boundary to literals nested inside that argument.

    Kotlin permits string literals inside ``${...}``. If the outer literal is a Log/Regex/JSON-key
    argument, its nested default value is part of the same developer/protocol argument; treating it
    as user-facing would reintroduce a false positive merely because the scanner became interpolation
    aware. User-facing outer literals have no safe position, so their nested UI text remains unsafe.
    """
    spans = list(_iter_literals(text))
    safe = set(safe_positions)
    for outer_start, outer_end, _ in spans:
        if outer_start not in safe_positions:
            continue
        for nested_start, nested_end, _ in spans:
            if outer_start < nested_start and nested_end <= outer_end:
                safe.add(nested_start)
    return safe


def _scan() -> dict[tuple[str, str], int]:
    """Return the multiset {(rel_path, normalised_content): occurrence_count} of unsafe literals."""
    allowlist = _load_assertion_allowlist()
    counts: Counter = Counter()
    for kt in sorted(SRC.rglob("*.kt")):
        rel = kt.relative_to(ROOT).as_posix()
        text = kt.read_text(encoding="utf-8")
        if _GENERATED_MARKER in text[:120]:
            continue
        lines = text.splitlines()
        safe_positions = _expand_nested_safe_positions(text, _compute_safe_positions(text, rel))
        for start, end, raw in _iter_literals(text):
            content = _decode(raw)
            line_no = text.count("\n", 0, start)
            line = lines[line_no] if line_no < len(lines) else ""
            stmt = _statement_text(text, start)
            if _is_safe(rel, content, stmt, line, allowlist, start=start, safe_positions=safe_positions):
                continue
            counts[(rel, _normalize(content))] += 1
    return dict(counts)


def _serialize(counts: dict[tuple[str, str], int]) -> str:
    lines = [
        "# DO NOT EDIT by hand — generated by tools/i18n/check_hardcoded_strings.py.",
        f"# scanner-version: {SCANNER_VERSION}",
        "# One tab-separated line per (file, normalised content) with its occurrence count.",
        "# Phase 1 deletes occurrences until this file is empty; the CI guard then becomes absolute.",
        "# Format: <count>\\t<relative path>\\t<content with \\t/\\n escaped>",
        "",
    ]
    for (rel, content), count in sorted(counts.items(), key=lambda kv: (kv[0][0], kv[0][1])):
        esc = content.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
        lines.append(f"{count}\t{rel}\t{esc}")
    return "\n".join(lines) + "\n"


def _scanner_version(text: str) -> int | None:
    for line in text.splitlines():
        if line.startswith("# scanner-version:"):
            try:
                return int(line.split(":", 1)[1].strip())
            except ValueError:
                return None
    return None


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
    base_text = Path(args.base).read_text(encoding="utf-8") if args.base else ""
    base = _parse(base_text) if args.base else {}
    current = _scan()
    committed_text = BASELINE.read_text(encoding="utf-8")
    committed = _parse(committed_text)
    fails = 0
    committed_version = _scanner_version(committed_text)
    if committed_version != SCANNER_VERSION:
        fails += 1
        print(f"BASELINE VERSION — committed baseline scanner-version {committed_version!r} "
              f"does not match checker version {SCANNER_VERSION}; regenerate it.")
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
