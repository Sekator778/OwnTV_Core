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
SCANNER_VERSION = 5

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
    """Strip quotes and decode Kotlin escapes without collapsing literal backslashes.

    Repeated ``str.replace`` calls are unsafe here: decoding ``\\\\u0041`` to ``\\u0041`` and
    then running a unicode replacement would make it indistinguishable from a source literal
    ``\\u0041``. Decode one escape at a time instead. A doubled backslash consumes both slashes and
    emits one literal backslash; the following ``u``/``n`` is then ordinary text, as it is in
    Kotlin. Unknown escapes are retained losslessly (the Kotlin compiler will report them later).
    """
    if raw.startswith('"""'):
        return raw[3:-3]  # Kotlin raw strings do not process backslash escapes.
    body = raw[1:-1]
    escapes = {
        "b": "\b", "t": "\t", "n": "\n", "r": "\r", "f": "\f",
        "\\": "\\", '"': '"', "'": "'", "$": "$",
    }
    out: list[str] = []
    pos = 0
    while pos < len(body):
        if body[pos] != "\\":
            out.append(body[pos])
            pos += 1
            continue
        if pos + 1 >= len(body):
            out.append("\\")
            pos += 1
            continue
        nxt = body[pos + 1]
        if nxt == "u" and pos + 5 < len(body):
            digits = body[pos + 2:pos + 6]
            if re.fullmatch(r"[0-9a-fA-F]{4}", digits):
                out.append(chr(int(digits, 16)))
                pos += 6
                continue
        decoded = escapes.get(nxt)
        if decoded is not None:
            out.append(decoded)
            pos += 2
        else:
            # Preserve invalid escapes rather than silently dropping the slash. This is both
            # lossless for the scanner and useful when the source is temporarily incomplete.
            out.extend(("\\", nxt))
            pos += 2
    return "".join(out)


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
# SQL is a safe category only when the literal has enough grammar to be a statement. Matching a
# leading keyword alone is not enough: ``"Select a channel"`` and ``"Update now"`` are ordinary UI
# copy. These intentionally conservative expressions may leave a small SQL fragment in the baseline;
# a false negative is reviewable, while a false positive silently hides copy from Phase 1.
_SQL_QUERY_ANNOTATION = re.compile(r"@(?:[A-Za-z_]\w*\.)*Query\s*\(")
# A receiver is required; an unrelated top-level function named query(...) must not inherit the
# database exemption merely from its name.
_SQL_EXEC_CALL = re.compile(r"\.(?:query|rawQuery|execSQL|compileStatement)\s*\(")
# Content-based SQL recognition is deliberately grammar-gated. API-bound SQL (Room @Query and
# database query/execSQL calls) is also marked safe by position below, but these patterns cover
# standalone schema/query constants without treating English that merely contains SQL keywords as
# protocol data.
_SQL_IDENT = r"(?:`[^`]+`|[A-Za-z_][\w$]*)"
_SQL_EXPR = rf"(?:\*|{_SQL_IDENT}(?:\s*\.\s*(?:{_SQL_IDENT}|\*))?(?:\s+AS\s+{_SQL_IDENT})?|[A-Za-z_]\w*\s*\([^;]*\))"
_SQL = (
    # A SELECT list must be made of SQL identifiers/wildcards/functions, not prose such as
    # "Select an item from ..."; the table boundary also rejects trailing parenthetical prose.
    re.compile(
        rf"^\s*SELECT\s+(?:DISTINCT\s+)?{_SQL_EXPR}(?:\s*,\s*{_SQL_EXPR})*"
        rf"\s+FROM\s+{_SQL_IDENT}(?=\s*(?:$|[),;]|(?:WHERE|JOIN|LEFT|RIGHT|INNER|OUTER|GROUP|ORDER|LIMIT|UNION)\b))",
        re.IGNORECASE | re.DOTALL),
    re.compile(rf"^\s*INSERT\s+INTO\s+{_SQL_IDENT}(?:\s*\([^)]*\))?\s+(?:VALUES\s*\(|SELECT\b)", re.IGNORECASE),
    # SET must contain a column assignment, not just the word "set" in a sentence.
    re.compile(rf"^\s*UPDATE\s+{_SQL_IDENT}\s+SET\s+{_SQL_IDENT}\s*=\s*\S", re.IGNORECASE),
    re.compile(rf"^\s*DELETE\s+FROM\s+{_SQL_IDENT}\s+(?:WHERE|IN|USING|RETURNING)\b", re.IGNORECASE),
    re.compile(rf"^\s*CREATE\s+(?:VIRTUAL\s+)?(?:TABLE|INDEX|TRIGGER)\s+(?:IF\s+NOT\s+EXISTS\s+)?{_SQL_IDENT}(?=\s+(?:USING|ON)\b|\s*\()", re.IGNORECASE),
    re.compile(rf"^\s*ALTER\s+TABLE\s+{_SQL_IDENT}\s+(?:ADD\s+(?:COLUMN\s+)?|DROP\s+COLUMN\s+|RENAME\s+(?:TO|COLUMN)\s+)", re.IGNORECASE),
    # A quoted identifier or explicit IF EXISTS clause distinguishes schema SQL from "Drop table X".
    re.compile(rf"^\s*DROP\s+(?:TABLE|INDEX|TRIGGER)\s+(?:IF\s+EXISTS\s+{_SQL_IDENT}|`[^`]+`)", re.IGNORECASE),
)

def _is_sql(content: str) -> bool:
    return any(pattern.search(content) for pattern in _SQL)
# MIME values have a constrained registered top-level type; this avoids classifying visible copy such
# as "and/or" as protocol data merely because it contains a slash.
_MIME = re.compile(
    r"^(?:application|audio|video|image|text|font|multipart|message|model|chemical)/"
    r"[a-z0-9][\w.+-]*$"
)
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
# Identifier/path spelling alone is deliberately NOT enough: Text("retry_later") and
# Text("and/or") are display strings even though they look like persisted/protocol data. The
# contextual calls below are the only automatic key/path exemptions.
_PREF_KEY_CALL = re.compile(
    r"\b(?:string|boolean|int|long|float|double|stringSet)PreferencesKey\s*\("
)
_FILE_PATH_CALL = re.compile(r"\b(?:java\.io\.)?File\s*\(")
_PATH_METHOD_CALL = re.compile(r"\.(?:resolve|resolveSibling|child)\s*\(")
# Perf.stamp() call arguments — logcat timing markers, never user-facing text.
_PERF_STAMP = re.compile(r"\bPerf\.stamp\s*\(")
# @Suppress(...) annotation arguments — compiler directive args, never user-facing text.
_SUPPRESS_ANN = re.compile(r"@Suppress\s*\(")
# A const val KEY_... = "..." declaration — the value is a preference/DataStore/Worker key.
_KEY_CONST = re.compile(r"\bconst\s+val\s+KEY_\w+[ \t]*(?::[ \t]*[A-Za-z_]\w*(?:[<>,.? ]*)[ \t]*)?=[ \t]*")

# Calls whose FIRST syntactic argument is a safe identifier (URI parameter, Room column name, etc.).
# _call_arg_positions() records a position only when the first token after '(' is the literal itself;
# a preceding KEY/name expression therefore cannot accidentally make the second argument safe.
_URI_API = re.compile(r"\.(?:appendQueryParameter|appendOptionalQueryParameter|getQueryParameter|queryLong|queryString|queryInt|queryBool|queryDouble)\s*\(")
# Room: Index("col"), ColumnInfo(name="col"), Index(value=["col"]) — the first string arg is a column.
# ForeignKey(childColumns=["col"]) is handled by _COL_ARRAY below (array position, not call arg).
_ROOM_CALL = re.compile(r"\b(?:Index|ColumnInfo)\s*\(")
# Column-name array contexts: childColumns = ["col"], parentColumns = ["col"], primaryKeys = ["col"].
# These are arrays of column-name literals; _col_array_positions() yields positions inside the [].
_COL_ARRAY = re.compile(
    r"(?:\b(?:childColumns|parentColumns|columnNames|columns|primaryKeys)\s*=\s*|"
    r"\bIndex\s*\(\s*value\s*=\s*)\["
)

# ErrorMessages.kt comparison needles: string literals inside .containsAny()/.contains() calls.
_ERROR_MESSAGES_FILE = "app/src/main/java/tv/own/owntv/core/util/ErrorMessages.kt"
_NEEDLE_CALL = re.compile(r"\.(?:containsAny|contains)\s*\(")

# JSON object/array methods. These names are only safe after _json_call_positions() proves that the
# receiver is a JSONObject/JSONArray (or that the call is inside JSONObject().apply { ... }).
_JSON_METHODS = (
    "put|putOpt|get|has|isNull|remove|opt|optString|optInt|optBoolean|optLong|optDouble|"
    "getString|getInt|getBoolean|getLong|getDouble|getJSONObject|optJSONObject|getJSONArray|optJSONArray"
)
_JSON_API = re.compile(rf"\b(?:{_JSON_METHODS})\s*\(")
_JSON_RETURN_RE = re.compile(
    r"\bfun\s+([A-Za-z_]\w*)\s*\([^)]*\)\s*:\s*(?:org\.json\.)?(JSONObject|JSONArray)\b"
)


def _skip_ws_and_comments(text: str, pos: int) -> int:
    """Return the first non-whitespace token, skipping Kotlin comments."""
    while pos < len(text):
        if text[pos].isspace():
            pos += 1
            continue
        if text.startswith("//", pos):
            end = text.find("\n", pos + 2)
            return len(text) if end == -1 else _skip_ws_and_comments(text, end + 1)
        if text.startswith("/*", pos):
            end = text.find("*/", pos + 2)
            return len(text) if end == -1 else _skip_ws_and_comments(text, end + 2)
        break
    return pos


def _first_arg_literal_position(text: str, paren_pos: int,
                                named_args: set[str] | None = None) -> int | None:
    """Return the opening quote iff the first syntactic argument is a direct string literal.

    Looking for the first quote at depth one is insufficient: in ``put(KEY, "value")`` it finds the
    second argument. The first expression must begin with the quote after comments/whitespace; an
    identifier, nested call, or any other expression means there is no safe literal. A small explicit
    named-argument allowance is used for APIs such as ``ColumnInfo(name = "column")``; callers must
    opt into those names rather than making every ``name = "..."`` expression safe.
    """
    pos = _skip_ws_and_comments(text, paren_pos + 1)
    if pos < len(text) and text[pos] == '"':
        return pos
    if named_args:
        identifier = re.match(r"[A-Za-z_]\w*", text[pos:])
        if identifier and identifier.group(0) in named_args:
            after_name = _skip_ws_and_comments(text, pos + len(identifier.group(0)))
            if after_name < len(text) and text[after_name] == "=":
                value = _skip_ws_and_comments(text, after_name + 1)
                if value < len(text) and text[value] == '"':
                    return value
    return None


def _call_arg_positions(text: str, call_re: re.Pattern,
                        named_args: set[str] | None = None) -> set[int]:
    """Character positions of the FIRST *syntactic* string argument of matching calls.

    Only a literal that starts the argument list is recorded. In particular, a literal in the second
    argument is never treated as the first merely because it is the first quote encountered.
    """
    positions: set[int] = set()
    for m in call_re.finditer(text):
        paren_pos = text.find("(", m.start())
        if paren_pos == -1:
            continue
        literal_pos = _first_arg_literal_position(text, paren_pos, named_args)
        if literal_pos is not None:
            positions.add(literal_pos)
    return positions


_JSON_METHOD_SET = set(_JSON_METHODS.split("|"))
_JSON_TYPES = {"JSONObject", "JSONArray"}


def _previous_nonspace(text: str, pos: int) -> int:
    while pos >= 0 and text[pos].isspace():
        pos -= 1
    return pos


def _identifier_before(text: str, pos: int) -> tuple[int, str] | None:
    """Return the identifier ending immediately before [pos], if any."""
    end = _previous_nonspace(text, pos - 1)
    if end < 0 or not (text[end].isalnum() or text[end] == "_"):
        return None
    start = end
    while start >= 0 and (text[start].isalnum() or text[start] == "_"):
        start -= 1
    return start + 1, text[start + 1:end + 1]


def _matching_open_paren(text: str, close_pos: int) -> int | None:
    depth = 0
    for pos in range(close_pos, -1, -1):
        if text[pos] == ")":
            depth += 1
        elif text[pos] == "(":
            depth -= 1
            if depth == 0:
                return pos
    return None


def _collect_json_return_functions() -> set[str]:
    """Collect JSON-returning function names across the Kotlin source tree."""
    functions: set[str] = set()
    if not SRC.is_dir():
        return functions
    for kt in SRC.rglob("*.kt"):
        functions.update(match.group(1) for match in _JSON_RETURN_RE.finditer(
            kt.read_text(encoding="utf-8")))
    return functions


def _json_type_names(text: str, json_return_functions: set[str] | None = None) -> set[str]:
    """Find variables/parameters with an explicit or constructor-inferred JSON type.

    This intentionally does not assume that every ``put``/``get`` receiver is JSON. A mutable map,
    AtomicInteger, or arbitrary application class must remain outside the protocol exemption.
    """
    names: set[str] = set()
    typed = re.compile(
        r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*:\s*(?:org\.json\.)?(JSONObject|JSONArray)\b"
    )
    parameter = re.compile(
        r"(?:\(|,)\s*([A-Za-z_]\w*)\s*:\s*(?:org\.json\.)?(JSONObject|JSONArray)\b"
    )
    constructor = re.compile(
        r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*(?:org\.json\.)?(JSONObject|JSONArray)\s*\("
    )
    for pattern in (typed, parameter, constructor):
        names.update(m.group(1) for m in pattern.finditer(text))

    # Propagate calls whose declared return type is JSON, e.g. SettingsRepository.exportSettings().
    # This keeps the receiver proof contextual without treating every ``put`` receiver as JSON.
    returns = set(json_return_functions or ())
    returns.update(match.group(1) for match in _JSON_RETURN_RE.finditer(text))
    returned_assignment = re.compile(
        r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*(?:[A-Za-z_]\w*\.)*([A-Za-z_]\w*)\s*\("
    )
    for match in returned_assignment.finditer(text):
        if match.group(2) in returns:
            names.add(match.group(1))

    # Propagate the common ``val child = parent.optJSONObject(...)`` form when parent is already
    # verified, or when the RHS visibly starts from a JSONObject/JSONArray constructor.
    result = re.compile(
        r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*([^;\n]+?)\.\s*"
        r"(?:opt|get)(JSONObject|JSONArray)\s*\("
    )
    changed = True
    while changed:
        changed = False
        for m in result.finditer(text):
            rhs = m.group(2)
            if re.search(r"\b(?:JSONObject|JSONArray)\s*\(", rhs) or any(
                re.search(rf"\b{re.escape(name)}\b", rhs) for name in names
            ):
                if m.group(1) not in names:
                    names.add(m.group(1))
                    changed = True
    return names


def _json_receiver_is_json(text: str, end: int, names: set[str], depth: int = 0) -> bool:
    """Whether the receiver expression ending immediately before a dot is JSON-typed."""
    if depth > 12:
        return False
    pos = _previous_nonspace(text, end - 1)
    while pos >= 0 and text[pos] == "?":  # Kotlin safe-call: json?.optString(...)
        pos = _previous_nonspace(text, pos - 1)
    if pos < 0:
        return False
    if text[pos] == ")":
        open_pos = _matching_open_paren(text, pos)
        if open_pos is None:
            return False
        token = _identifier_before(text, open_pos)
        if token is None:
            return False
        token_start, name = token
        if name in _JSON_TYPES:
            return True  # JSONObject(...).put(...) / JSONArray(...).put(...)
        if name not in _JSON_METHOD_SET:
            return False
        dot = _previous_nonspace(text, token_start - 1)
        return dot >= 0 and text[dot] == "." and _json_receiver_is_json(text, dot, names, depth + 1)
    token = _identifier_before(text, pos + 1)
    return token is not None and token[1] in names | _JSON_TYPES


def _matching_close_brace(text: str, open_pos: int) -> int:
    """Find a Kotlin block's closing brace while ignoring comments and string contents."""
    depth = 0
    pos = open_pos
    while pos < len(text):
        if text.startswith("//", pos):
            end = text.find("\n", pos + 2)
            pos = len(text) if end == -1 else end + 1
            continue
        if text.startswith("/*", pos):
            end = text.find("*/", pos + 2)
            pos = len(text) if end == -1 else end + 2
            continue
        if text.startswith('"""', pos):
            end = text.find('"""', pos + 3)
            pos = len(text) if end == -1 else end + 3
            continue
        if text[pos] == '"':
            _, end = _scan_double_quoted(text, pos, len(text))
            pos = len(text) if end is None else end
            continue
        if text[pos] == "{":
            depth += 1
        elif text[pos] == "}":
            depth -= 1
            if depth == 0:
                return pos
        pos += 1
    return len(text)


def _json_scope_ranges(text: str) -> list[tuple[int, int]]:
    """Ranges for unqualified JSON calls inside JSONObject/JSONArray scope functions."""
    patterns = (
        re.compile(r"(?:org\.json\.)?(?:JSONObject|JSONArray)\s*\([^{}]*\)\s*\.\s*"
                   r"(?:apply|also|run)\s*\{"),
        re.compile(r"\bwith\s*\(\s*(?:org\.json\.)?(?:JSONObject|JSONArray)\s*\([^{}]*\)\s*\)\s*\{"),
    )
    ranges: list[tuple[int, int]] = []
    for pattern in patterns:
        for match in pattern.finditer(text):
            open_pos = text.rfind("{", match.start(), match.end())
            if open_pos >= 0:
                ranges.append((open_pos, _matching_close_brace(text, open_pos)))
    return ranges


def _json_call_positions(text: str, json_return_functions: set[str] | None = None) -> set[int]:
    """Safe first-argument positions for calls proven to operate on org.json objects/arrays."""
    names = _json_type_names(text, json_return_functions)
    scopes = _json_scope_ranges(text)
    positions: set[int] = set()
    for match in _JSON_API.finditer(text):
        paren_pos = text.find("(", match.start())
        if paren_pos == -1:
            continue
        previous = _previous_nonspace(text, match.start() - 1)
        verified = previous >= 0 and text[previous] == "." and _json_receiver_is_json(text, previous, names)
        if not verified and (previous < 0 or text[previous] != "."):
            verified = any(start <= match.start() <= end for start, end in scopes)
        if verified:
            literal_pos = _first_arg_literal_position(text, paren_pos)
            if literal_pos is not None:
                positions.add(literal_pos)
    return positions


def _call_all_arg_positions(text: str, call_re: re.Pattern) -> set[int]:
    """Positions of direct string operands in calls matching [call_re].

    A string inside a nested call is not an argument of the matched call. The old depth-only scan
    treated ``Log.w(TAG, makeMessage("Visible copy"))`` as if the nested literal were a log message
    and exempted it. Track all delimiters and record only quotes at the matched call's direct
    argument level. Nested literals inside a direct interpolated string are handled separately by
    ``_iter_literals``.
    """
    positions: set[int] = set()
    for m in call_re.finditer(text):
        paren_pos = text.find("(", m.start())
        if paren_pos == -1:
            continue
        paren_depth = 1
        bracket_depth = 0
        brace_depth = 0
        pos = paren_pos + 1
        while pos < len(text) and paren_depth > 0:
            if text.startswith("//", pos):
                end = text.find("\n", pos + 2)
                pos = len(text) if end == -1 else end + 1
                continue
            if text.startswith("/*", pos):
                end = text.find("*/", pos + 2)
                pos = len(text) if end == -1 else end + 2
                continue
            if text.startswith('"""', pos):
                end = text.find('"""', pos + 3)
                if end == -1:
                    break
                if paren_depth == 1 and bracket_depth == 0 and brace_depth == 0:
                    positions.add(pos)
                pos = end + 3
                continue
            c = text[pos]
            if c == '"':
                _, end = _scan_double_quoted(text, pos, len(text))
                if end is None:
                    break
                if paren_depth == 1 and bracket_depth == 0 and brace_depth == 0:
                    positions.add(pos)
                pos = end
                continue
            if c == '(':
                paren_depth += 1
            elif c == ')':
                paren_depth -= 1
            elif c == '[':
                bracket_depth += 1
            elif c == ']':
                bracket_depth = max(0, bracket_depth - 1)
            elif c == '{':
                brace_depth += 1
            elif c == '}':
                brace_depth = max(0, brace_depth - 1)
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
    # SQL is content-based but deliberately grammar-gated; a leading SELECT/UPDATE in UI copy is
    # not enough to enter this category.
    if _is_sql(content):
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
    # Identifier/path/file-extension spelling is intentionally NOT a safe category. Those shapes are
    # common in visible copy ("sign-in", "retry_later", "audio-only", "and/or"). The contextual
    # preference/File/resolve positions above are the reviewed proof that such a value is a key/path.
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


def _compute_safe_positions(text: str, rel_path: str,
                            json_return_functions: set[str] | None = None) -> set[int]:
    """All character positions of literals that are safe by CALL ARGUMENT position.

    This is the position-based enforcement of the invariant: a safe call exempts ONLY its string
    argument(s), never every literal on the same line. Aggregates:
      - verified JSONObject/JSONArray API first-arg positions (.put/.getString/.optJSONObject/...)
      - Room @Query SQL operands and database query/execSQL first arguments
      - URI API first-arg positions (.appendQueryParameter/.queryLong/... — first string arg only)
      - DataStore key factory arguments (stringPreferencesKey/etc.)
      - File constructor and path resolver arguments
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
    safe |= _json_call_positions(text, json_return_functions)
    # Room's @Query argument is SQL, including direct string operands joined with Kotlin '+'.
    # Database query/execSQL APIs likewise receive SQL as their first argument. These are API-bound
    # locations, not content/line-level exemptions, so a nested formatter or adjacent UI literal is
    # still scanned.
    safe |= _call_all_arg_positions(text, _SQL_QUERY_ANNOTATION)
    safe |= _call_arg_positions(text, _SQL_EXEC_CALL)
    safe |= _call_arg_positions(text, _URI_API)
    safe |= _call_arg_positions(text, _PREF_KEY_CALL)
    safe |= _call_all_arg_positions(text, _FILE_PATH_CALL)
    safe |= _call_arg_positions(text, _PATH_METHOD_CALL)
    safe |= _call_arg_positions(text, _ROOM_CALL, {"name"})
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


def _scan() -> dict[tuple[str, str], int]:
    """Return the multiset {(rel_path, normalised_content): occurrence_count} of unsafe literals."""
    allowlist = _load_assertion_allowlist()
    json_return_functions = _collect_json_return_functions()
    counts: Counter = Counter()
    for kt in sorted(SRC.rglob("*.kt")):
        rel = kt.relative_to(ROOT).as_posix()
        text = kt.read_text(encoding="utf-8")
        if _GENERATED_MARKER in text[:120]:
            continue
        lines = text.splitlines()
        # Every interpolated literal is a separate occurrence. Do not inherit an outer Log/JSON/
        # Regex exemption: a nested fallback such as optString("label", "Visible fallback") is a
        # distinct literal and must remain visible to the ratchet unless its own syntax proves it safe.
        safe_positions = _compute_safe_positions(text, rel, json_return_functions)
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


def _unescape_serialized(value: str) -> str:
    """Decode the baseline escape format left-to-right.

    Backslashes are escaped before ``\\n``/``\\t`` during serialization. Replacing ``\\n`` first
    therefore turns the literal two-character sequence ``\\\\n`` into a backslash plus a real newline.
    A small state machine preserves that distinction and leaves unknown escapes losslessly intact.
    """
    out: list[str] = []
    pos = 0
    while pos < len(value):
        if value[pos] != "\\" or pos + 1 >= len(value):
            out.append(value[pos])
            pos += 1
            continue
        nxt = value[pos + 1]
        if nxt == "\\":
            out.append("\\")
        elif nxt == "n":
            out.append("\n")
        elif nxt == "t":
            out.append("\t")
        else:
            out.extend(("\\", nxt))
        pos += 2
    return "".join(out)


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
        out[(rel, _unescape_serialized(esc))] = n
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
