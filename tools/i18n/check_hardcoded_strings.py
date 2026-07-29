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
# A JSON object/fragment key: "key": or "key" : — translator never sees these.
_JSON_KEY = re.compile(r'^"[A-Za-z_][\w-]*"\s*:')
# A snake_case / kebab-case identifier that looks like a preference/DataStore key or protocol field,
# not a sentence: lowercase, digits, underscores/hyphens/dots, no spaces, and not a readable phrase.
# Require at least one underscore/dot/hyphen so a bare word like "Settings" is NOT misclassified.
_IDENT_KEY = re.compile(r"^[a-z][a-z0-9_./-]*[_./-][a-z0-9_./-]*$")
# A filesystem-ish path (contains a slash and no spaces).
_PATH = re.compile(r"^[^\s]*[/\\][^\s]*$")
# File extension or a dotted protocol token like ".mp4", "application/json", "owntv_locale".
_DOTTED = re.compile(r"^(?:\.[a-z0-9]+|[a-z][a-z0-9]*(?:\.[a-z0-9]+)+)$")
# A single ALL_CAPS or ALL_LOWER token with no spaces and <= 32 chars — enum/constant-style names,
# logcat stamps, Perf.stamp markers, @Suppress args. Excludes anything with a space (a sentence).
_TOKEN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,31}$")
# An exception/comparison needle in ErrorMessages.kt: a phrase used for *matching*, not display. We
# cannot know intent from content alone, so this is handled by file-scoped detection below.
_ERROR_MESSAGES_FILE = "app/src/main/java/tv/own/owntv/core/util/ErrorMessages.kt"


def _statement_text(src: str, pos: int) -> str:
    """A rough slice from the previous statement separator to the literal position."""
    start = max(src.rfind(";", 0, pos), src.rfind("\n", 0, pos), 0) + 1
    end = src.find("\n", pos)
    if end == -1:
        end = len(src)
    return src[start:end]


def _is_safe(rel_path: str, content: str, stmt: str, line: str, allowlist: set[tuple[str, str]]) -> bool:
    norm = _normalize(content)
    # Explicit, reasoned assertion allowlist (developer-only require/check/error) by file+content.
    if (rel_path, norm) in allowlist:
        return True
    # Empty strings are never user-facing text.
    if norm == "":
        return True
    # ErrorMessages.kt: every string literal there is a stable comparison needle (see docs/i18n.md,
    # "The ErrorMessages English-needle caveat"). Translating one silently breaks classification.
    if rel_path == _ERROR_MESSAGES_FILE:
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
    # JSON object keys ("key":).
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
    # A bare token (no spaces, <= 32 chars, identifier-only): enum/constant names, Perf.stamp markers,
    # @Suppress args, logcat tags. This is the broadest category — it keeps dev-only identifiers out
    # of the baseline without needing a per-token allowlist. A user-facing *sentence* always has a
    # space or punctuation this regex rejects, so real display text is never misclassified as safe.
    if _TOKEN.match(content) and " " not in content and not _looks_like_sentence(content):
        return True
    return False


def _looks_like_sentence(content: str) -> bool:
    """Heuristic: does this look like user-facing text rather than an identifier?

    A token with interior mixed case AND a vowel pattern suggests a word, not a constant. But the
    strongest signal is a space or sentence-ending punctuation, already excluded by the caller. The
    remaining risk is a single CamelCase word like "Settings" — that is NOT safe (it is display text),
    so require ALL_CAPS or ALL_LOWER for the bare-token category. A mixed-case token is left in the
    baseline for human review.
    """
    has_upper = any(c.isupper() for c in content)
    has_lower = any(c.islower() for c in content)
    return has_upper and has_lower  # CamelCase like "Settings" → not a safe identifier token


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
        for start, end, raw in _iter_literals(text):
            content = _decode(raw)
            line_no = text.count("\n", 0, start)
            line = lines[line_no] if line_no < len(lines) else ""
            stmt = _statement_text(text, start)
            if _is_safe(rel, content, stmt, line, allowlist):
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
        print("i18n baseline OK: current ⊆ merge-base, committed ⊆ current, and current ⊆ committed.")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("generate")
    v = sub.add_parser("verify")
    v.add_argument("--base", help="path to the merge-base baseline file")
    args = ap.parse_args()
    if args.cmd == "generate":
        return cmd_generate(args)
    return cmd_verify(args)


if __name__ == "__main__":
    raise SystemExit(main())