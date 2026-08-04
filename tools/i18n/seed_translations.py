#!/usr/bin/env python3
"""MANUAL, ONE-OFF supply-chain tool: drives the Anthropic Message Batches API to seed
the Phase 4a community-translation snapshot (docs/i18n-phase4a-seed-translations.md).

This is the only module under tools/i18n/ that imports ``anthropic``. It is never wired
into CI and never run automatically by Gradle. Only the maintainer, with their own API
credentials, runs the two commands that spend money or touch the network: ``submit`` and
``resume``. Every other subcommand (the ``prepare-*`` pair, ``status``, ``collect`` after
the maintainer has results, ``validate-and-promote``, and ``check``) is safe to run
repeatedly and offline except where it explicitly polls or downloads results for an
already-submitted batch.

Text processing (extraction, tokenization, escaping, offline validation, atomic
promotion) lives in ``tools/i18n/seed_text.py``, which stays stdlib-only.

Durable state for a run lives under ``runs/seed/<run-id>/`` (gitignored). Nothing here
ever writes translation_status entries, generates a localized ``donottranslate.xml``, or
writes a partial locale into ``app/src/main/res``.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "src" / "main" / "res"
LOCALES_JSON = ROOT / "tools" / "i18n" / "locales.json"
GLOSSARY_JSON = ROOT / "tools" / "i18n" / "glossary.json"
RUNS_DIR = ROOT / "runs" / "seed"

MODEL = "claude-opus-4-8"
THINKING = {"type": "adaptive"}
EFFORT = "high"
MAX_KEYS_PER_CHUNK = 40
MAX_FOLLOWUP_ATTEMPTS = 2  # two follow-up attempts after the initial request
PILOT_LOCALES = ["de", "ar", "ja", "tr"]

try:
    from tools.i18n import seed_text as st
except ModuleNotFoundError:
    import seed_text as st  # direct invocation from tools/i18n


# --- response schema (stable across chunks so prompt caching survives) ------------

_STRING_RECORD = {
    "type": "object",
    "properties": {"key": {"type": "string"}, "text": {"type": "string"}},
    "required": ["key", "text"],
    "additionalProperties": False,
}
_PLURAL_QUANTITY_FIELD = {"type": ["string", "null"]}
_PLURAL_RECORD = {
    "type": "object",
    "properties": {
        "key": {"type": "string"},
        "zero": _PLURAL_QUANTITY_FIELD,
        "one": _PLURAL_QUANTITY_FIELD,
        "two": _PLURAL_QUANTITY_FIELD,
        "few": _PLURAL_QUANTITY_FIELD,
        "many": _PLURAL_QUANTITY_FIELD,
        "other": _PLURAL_QUANTITY_FIELD,
    },
    "required": ["key", "zero", "one", "two", "few", "many", "other"],
    "additionalProperties": False,
}
TRANSLATION_RESPONSE_SCHEMA = {
    "type": "object",
    "properties": {
        "strings": {"type": "array", "items": _STRING_RECORD},
        "plurals": {"type": "array", "items": _PLURAL_RECORD},
    },
    "required": ["strings", "plurals"],
    "additionalProperties": False,
}
GLOSSARY_RESPONSE_SCHEMA = {
    "type": "object",
    "properties": {
        "consistentTerms": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"term": {"type": "string"}, "translation": {"type": "string"}},
                "required": ["term", "translation"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["consistentTerms"],
    "additionalProperties": False,
}


# --- hashing and catalogue loading -------------------------------------------------

def _sha256_bytes(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def _payload_hash(params: dict) -> str:
    return _sha256_bytes(json.dumps(params, sort_keys=True).encode())


def source_inventory_hash() -> str:
    h = hashlib.sha256()
    for f in sorted((RES / "values").glob("strings*.xml")):
        if f.name == "donottranslate.xml":
            continue
        h.update(f.name.encode())
        h.update(f.read_bytes())
    return "sha256:" + h.hexdigest()


def locales_json_hash() -> str:
    return _sha256_bytes(LOCALES_JSON.read_bytes())


def glossary_hash() -> str:
    return _sha256_bytes(GLOSSARY_JSON.read_bytes())


def load_catalogue() -> dict[str, dict]:
    data = json.loads(LOCALES_JSON.read_text(encoding="utf-8"))
    return {e["languageTag"]: e for e in data}


def load_glossary() -> dict:
    return json.loads(GLOSSARY_JSON.read_text(encoding="utf-8"))


# --- manifest I/O -------------------------------------------------------------------

def new_run_id() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def _manifest_path(run_id: str) -> Path:
    return RUNS_DIR / run_id / "manifest.json"


def load_manifest(run_id: str) -> dict:
    path = _manifest_path(run_id)
    if not path.is_file():
        sys.exit(f"error: no manifest at {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def save_manifest(run_id: str, manifest: dict) -> None:
    path = _manifest_path(run_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(manifest, indent=2, sort_keys=True), encoding="utf-8")


def new_manifest(run_id: str) -> dict:
    return {
        "schemaVersion": 2,
        "runId": run_id,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "sourceInventoryHash": source_inventory_hash(),
        "localesJsonHash": locales_json_hash(),
        "glossaryHash": glossary_hash(),
        "model": MODEL,
        "thinking": THINKING,
        "effort": EFFORT,
        "stages": {
            "glossary": {"requests": {}, "results": {}},
            "translation": {"requests": {}, "results": {}},
        },
        "retries": {"glossary": {}, "translation": {}},
    }


def verify_hashes(manifest: dict, *, force_stale: bool = False) -> None:
    """Guard submit/promote against source, catalogue, or glossary drift since prepare."""
    drifted = []
    if manifest["sourceInventoryHash"] != source_inventory_hash():
        drifted.append("source values/strings*.xml")
    if manifest["localesJsonHash"] != locales_json_hash():
        drifted.append("tools/i18n/locales.json")
    if manifest["glossaryHash"] != glossary_hash():
        drifted.append("tools/i18n/glossary.json")
    if not drifted:
        return
    if not force_stale:
        sys.exit(
            f"error: run {manifest['runId']} was prepared against a different version of: "
            f"{', '.join(drifted)}. Re-run prepare-* against the current checkout, or pass "
            "--force-stale if the drift is confirmed safe."
        )
    print(f"warning: proceeding with --force-stale despite drift in: {', '.join(drifted)}")


def _run_dir(run_id: str) -> Path:
    return RUNS_DIR / run_id


def _requests_dir(run_id: str, stage: str) -> Path:
    return _run_dir(run_id) / "requests" / stage


def _results_dir(run_id: str, stage: str) -> Path:
    return _run_dir(run_id) / "results" / stage


def _work_dir(run_id: str, locale: str) -> Path:
    return _run_dir(run_id) / "work" / locale


# --- prompt construction -------------------------------------------------------------

def _locale_system_prompt(entry: dict, glossary_translations: dict[str, str] | None) -> str:
    """Byte-identical across every translation request for this locale, so the prompt
    cache (ephemeral, 1h TTL) is reused chunk to chunk."""
    glossary = load_glossary()
    preserve = "\n".join(f"- {p}" for p in glossary["preserveExact"])
    if glossary_translations:
        terms = "\n".join(f"- {term} -> {glossary_translations[term]}"
                           for term in glossary["consistentTerms"] if term in glossary_translations)
    else:
        terms = "(glossary not yet collected for this locale -- placeholder preview only)"
    return f"""You are a professional software localization translator, translating the \
OwnTV Android TV application from source English into {entry['englishName']} \
({entry['endonym']}, BCP-47 tag "{entry['languageTag']}").

OwnTV is an IPTV / Xtream-Codes client for Android TV: playlists, live channels, an \
electronic programme guide, catch-up TV, movies, and series.

Preserve these brand, product, and protocol phrases exactly, byte-for-byte, wherever \
they appear inside a sentence. Never translate or transliterate them:
{preserve}

Use these translations for recurring product terms wherever they appear, for \
consistency across the whole app:
{terms}

Rules:
- Translate only the given text. Some text contains an opaque placeholder token that \
looks like a private-use-area character wrapping "XLF" and a number. Copy every such \
token through completely unchanged, in its original position and exact spelling. Never \
translate, translate around, reword, or omit it.
- Preserve the meaning, register, and sentence structure of the source. Do not add, \
drop, or merge sentences.
- A literal "%" character you author yourself should be written as a plain "%"; it is \
escaped for Android automatically afterward. Do not double it yourself.
- For plural entries, produce a natural, idiomatic translation for every CLDR plural \
quantity requested for this locale, matching {entry['languageTag']}'s own plural rules \
-- do not just reuse the English one/other split.
- If the request includes "previousAttemptErrors", those specific keys failed \
validation last time for the stated reason. Fix exactly that issue and resend only \
those keys.
- Respond only with the structured JSON the response schema requires. Do not add \
commentary."""


def _string_unit_payload(unit: "st.StringSource") -> dict:
    return {"key": unit.key, "kind": "string", "comment": unit.comment, "sourceText": unit.text}


def _plural_unit_payload(unit: "st.PluralSource", required_quantities: list[str]) -> dict:
    by_quantity = {}
    for q in required_quantities:
        text, _ = st.plural_source_text_for_quantity(unit, q)
        by_quantity[q] = text
    return {
        "key": unit.key, "kind": "plurals", "comment": unit.comment,
        "requiredQuantities": required_quantities, "sourceByQuantity": by_quantity,
    }


def _translation_user_message(filename: str, units: dict[str, object], keys: list[str],
                               plural_rule: list[str], retry_errors: dict[str, str] | None = None) -> str:
    items = []
    for key in keys:
        unit = units[key]
        if isinstance(unit, st.StringSource):
            items.append(_string_unit_payload(unit))
        else:
            items.append(_plural_unit_payload(unit, plural_rule))
    payload: dict = {"sourceFile": filename, "items": items}
    if retry_errors:
        payload["previousAttemptErrors"] = dict(retry_errors)
    return json.dumps(payload, ensure_ascii=False, indent=2)


def _glossary_user_message(terms: list[str], retry_errors: dict[str, str] | None = None) -> str:
    payload: dict = {"consistentTerms": terms}
    if retry_errors:
        payload["previousAttemptErrors"] = dict(retry_errors)
    return json.dumps(payload, ensure_ascii=False, indent=2)


def _batch_params(system_prompt: str, user_message: str, schema: dict, max_tokens: int) -> dict:
    return {
        "model": MODEL,
        "max_tokens": max_tokens,
        "thinking": THINKING,
        "system": [{"type": "text", "text": system_prompt,
                    "cache_control": {"type": "ephemeral", "ttl": "1h"}}],
        "messages": [{"role": "user", "content": user_message}],
        "output_config": {"effort": EFFORT, "format": {"type": "json_schema", "schema": schema}},
    }


# --- request registration (offline; never touches the network) --------------------

def _register_request(manifest: dict, run_id: str, stage: str, cid: str, meta: dict, params: dict,
                       *, retry_of: str | None = None) -> None:
    req_dir = _requests_dir(run_id, stage)
    req_dir.mkdir(parents=True, exist_ok=True)
    record = {**meta, "params": params}
    (req_dir / f"{cid}.json").write_text(json.dumps(record, indent=2, ensure_ascii=False), encoding="utf-8")
    manifest["stages"][stage]["requests"][cid] = {
        **{k: v for k, v in meta.items() if k != "params"},
        "payloadHash": _payload_hash(params),
        "batchId": None,
        "retryOf": retry_of,
    }


def build_and_register_glossary_requests(manifest: dict, run_id: str, locales: list[str],
                                          catalogue: dict[str, dict]) -> dict[str, dict]:
    glossary = load_glossary()
    built = {}
    for tag in locales:
        entry = catalogue[tag]
        system = _locale_system_prompt(entry, glossary_translations=None)
        user = _glossary_user_message(glossary["consistentTerms"])
        params = _batch_params(system, user, GLOSSARY_RESPONSE_SCHEMA, max_tokens=8000)
        cid = st.glossary_custom_id(tag)
        meta = {"locale": tag, "terms": glossary["consistentTerms"]}
        _register_request(manifest, run_id, "glossary", cid, meta, params)
        built[cid] = {**meta, "params": params}
    return built


def build_and_register_translation_requests(manifest: dict, run_id: str, locales: list[str],
                                             catalogue: dict[str, dict],
                                             glossary_by_locale: dict[str, dict[str, str]]) -> dict[str, dict]:
    units, order = st.extract_source()
    chunks_by_file = st.chunk_by_file(units, order, MAX_KEYS_PER_CHUNK)
    try:
        from tools.i18n import validate_strings as vs
    except ModuleNotFoundError:
        import validate_strings as vs

    built = {}
    for tag in locales:
        entry = catalogue[tag]
        plural_rule = vs._PLURAL_RULES.get(tag, ["one", "other"])
        system = _locale_system_prompt(entry, glossary_by_locale.get(tag))
        for filename, file_chunks in chunks_by_file.items():
            for seq, keys in enumerate(file_chunks):
                user = _translation_user_message(filename, units, keys, plural_rule)
                params = _batch_params(system, user, TRANSLATION_RESPONSE_SCHEMA, max_tokens=16000)
                cid = st.custom_id(tag, filename, seq)
                meta = {"locale": tag, "filename": filename, "keys": keys}
                _register_request(manifest, run_id, "translation", cid, meta, params)
                built[cid] = {**meta, "params": params}
    return built


# --- token estimate + inspectable preview (heuristic; never a real count_tokens call) --

def _estimate_tokens(text: str) -> int:
    return max(1, len(text) // 4)  # rough English-text heuristic; not a billed count


def _print_summary_and_sample(requests: dict[str, dict], *, show_sample: bool) -> None:
    total_input = total_output_est = 0
    for req in requests.values():
        params = req["params"]
        total_input += _estimate_tokens(params["system"][0]["text"])
        total_input += _estimate_tokens(params["messages"][0]["content"])
        # A translation's output is comparable in size to its source text.
        total_output_est += _estimate_tokens(params["messages"][0]["content"])
    print(f"Requests: {len(requests)}")
    print(f"Estimated input tokens (heuristic, not billed count): ~{total_input:,}")
    print(f"Estimated output tokens (heuristic, not billed count): ~{total_output_est:,}")
    print("Verify actual usage from each collected result's `usage` block before "
          "submitting the remaining locales.")
    if show_sample and requests:
        sample_cid = sorted(requests)[0]
        sample = requests[sample_cid]["params"]
        print(f"\n--- full sample request ({sample_cid}) ---")
        print("[system]")
        print(sample["system"][0]["text"])
        print("\n[user]")
        print(sample["messages"][0]["content"])
        print("--- end sample ---\n")


# --- semantic validation of collected results ---------------------------------------

def _classify_transport(stage: str, result) -> dict:
    """Transport-level classification only: batch result type, stop_reason, JSON
    parseability. A succeeded, parseable request is not automatically usable -- see
    _validate_translation_payload / _validate_glossary_payload for the semantic pass."""
    rtype = result.result.type
    if rtype != "succeeded":
        return {"status": rtype}

    message = result.result.message
    usage = {
        "inputTokens": message.usage.input_tokens,
        "outputTokens": message.usage.output_tokens,
        "cacheCreationInputTokens": getattr(message.usage, "cache_creation_input_tokens", None),
        "cacheReadInputTokens": getattr(message.usage, "cache_read_input_tokens", None),
    }
    raw = {"stopReason": message.stop_reason,
           "content": [{"type": b.type, "text": getattr(b, "text", None)} for b in message.content]}
    if message.stop_reason != "end_turn":
        return {"status": "retryable", "reason": f"stop_reason={message.stop_reason}", "usage": usage, "raw": raw}
    text_blocks = [b.text for b in message.content if b.type == "text"]
    if not text_blocks:
        return {"status": "retryable", "reason": "no text content block", "usage": usage, "raw": raw}
    try:
        payload = json.loads(text_blocks[0])
    except json.JSONDecodeError as e:
        return {"status": "retryable", "reason": f"malformed JSON: {e}", "usage": usage, "raw": raw}
    return {"status": "succeeded", "payload": payload, "usage": usage, "raw": raw}


def _validate_glossary_payload(req_meta: dict, payload: dict) -> tuple[dict[str, str], dict[str, str]]:
    """Return (valid_term_translations, errors_by_term)."""
    requested = req_meta["terms"]
    by_term: dict[str, list[dict]] = {}
    for item in payload.get("consistentTerms", []):
        by_term.setdefault(item.get("term"), []).append(item)

    valid: dict[str, str] = {}
    errors: dict[str, str] = {}
    for term in requested:
        matches = by_term.get(term, [])
        if not matches:
            errors[term] = "missing from model response"
            continue
        if len(matches) > 1:
            errors[term] = f"duplicate term in response ({len(matches)}x)"
            continue
        translation = matches[0].get("translation")
        if not isinstance(translation, str) or not translation.strip():
            errors[term] = "empty or non-string translation"
            continue
        valid[term] = translation
    return valid, errors


def _validate_translation_payload(req_meta: dict, payload: dict, units: dict,
                                   plural_rule: list[str]) -> tuple[dict[str, dict], dict[str, str]]:
    """Return (valid_by_suffixed_key, errors_by_suffixed_key).

    Enforces: the exact requested key set (no extras/duplicates), the correct resource
    kind per key, required plural quantities present, and exact opaque-token parity.
    """
    requested = set(req_meta["keys"])
    string_items: dict[str, list[dict]] = {}
    for item in payload.get("strings", []):
        string_items.setdefault(item.get("key"), []).append(item)
    plural_items: dict[str, list[dict]] = {}
    for item in payload.get("plurals", []):
        plural_items.setdefault(item.get("key"), []).append(item)

    valid: dict[str, dict] = {}
    errors: dict[str, str] = {}

    for suffixed_key in requested:
        is_plural = suffixed_key.endswith("#")
        bare = suffixed_key[:-1] if is_plural else suffixed_key
        unit = units[suffixed_key]
        own_occurrences = (plural_items if is_plural else string_items).get(bare, [])
        other_occurrences = (string_items if is_plural else plural_items).get(bare, [])

        if not own_occurrences:
            errors[suffixed_key] = "missing from model response"
            continue
        if len(own_occurrences) > 1:
            errors[suffixed_key] = f"duplicate key in response ({len(own_occurrences)}x)"
            continue
        if other_occurrences:
            errors[suffixed_key] = "key present under the wrong resource kind"
            continue

        record = own_occurrences[0]
        if not is_plural:
            text = record.get("text")
            if not isinstance(text, str):
                errors[suffixed_key] = "missing or non-string 'text'"
                continue
            try:
                st.check_token_parity(text, list(unit.tokens))
            except st.TokenParityError as e:
                errors[suffixed_key] = str(e)
                continue
            valid[suffixed_key] = {"text": text}
        else:
            by_quantity: dict[str, str] = {}
            failed = False
            for q in plural_rule:
                value = record.get(q)
                if not isinstance(value, str) or not value:
                    errors[suffixed_key] = f"missing required quantity '{q}'"
                    failed = True
                    break
                _, expected_tokens = st.plural_source_text_for_quantity(unit, q)
                try:
                    st.check_token_parity(value, list(expected_tokens))
                except st.TokenParityError as e:
                    errors[suffixed_key] = f"quantity '{q}': {e}"
                    failed = True
                    break
                by_quantity[q] = value
            if not failed:
                valid[suffixed_key] = by_quantity

    for bare_key in string_items:
        if bare_key not in requested:
            errors[f"{bare_key} (unrequested string)"] = "returned but not part of this chunk's request"
    for bare_key in plural_items:
        if (bare_key + "#") not in requested:
            errors[f"{bare_key} (unrequested plural)"] = "returned but not part of this chunk's request"

    return valid, errors


# --- subcommands ---------------------------------------------------------------------

def cmd_prepare_glossary(args: argparse.Namespace) -> int:
    locales = [t.strip() for t in args.locales.split(",") if t.strip()]
    catalogue = load_catalogue()
    unknown = [t for t in locales if t not in catalogue]
    if unknown:
        sys.exit(f"error: unknown locale tag(s) in --locales: {unknown}")
    run_id = args.run_id or new_run_id()
    manifest = load_manifest(run_id) if _manifest_path(run_id).is_file() else new_manifest(run_id)
    built = build_and_register_glossary_requests(manifest, run_id, locales, catalogue)
    save_manifest(run_id, manifest)
    print(f"run-id: {run_id}")
    _print_summary_and_sample(built, show_sample=args.dry_run)
    print(f"Payloads written under runs/seed/{run_id}/requests/glossary/ for inspection.")
    print(f"Next: python3 tools/i18n/seed_translations.py submit --run-id {run_id} --stage glossary")
    return 0


def cmd_prepare_translations(args: argparse.Namespace) -> int:
    locales = [t.strip() for t in args.locales.split(",") if t.strip()]
    catalogue = load_catalogue()
    unknown = [t for t in locales if t not in catalogue]
    if unknown:
        sys.exit(f"error: unknown locale tag(s) in --locales: {unknown}")

    glossary_by_locale: dict[str, dict[str, str]] = {}
    placeholder_used = False
    if args.run_id and _manifest_path(args.run_id).is_file():
        manifest = load_manifest(args.run_id)
        for tag in locales:
            cid = st.glossary_custom_id(tag)
            result = manifest["stages"]["glossary"]["results"].get(cid)
            if result and result.get("status") == "succeeded":
                glossary_by_locale[tag] = result["valid"]
    if not glossary_by_locale:
        # Realistically-sized placeholder so the preview's size/shape resembles the real
        # prompt: use each term as a stand-in for its own (not-yet-collected) translation.
        placeholder_used = True
        placeholder_terms = load_glossary()["consistentTerms"]
        glossary_by_locale = {tag: {t: t for t in placeholder_terms} for tag in locales}

    run_id = args.run_id or new_run_id()
    manifest = load_manifest(run_id) if _manifest_path(run_id).is_file() else new_manifest(run_id)
    built = build_and_register_translation_requests(manifest, run_id, locales, catalogue, glossary_by_locale)
    save_manifest(run_id, manifest)
    print(f"run-id: {run_id}")
    if placeholder_used:
        print("note: no collected glossary found for these locales in this run; the preview "
              "below uses each glossary term as its own placeholder translation so the size "
              "and shape approximate the real prompt. Re-run after 'collect --stage glossary' "
              "for the exact prompt.")
    _print_summary_and_sample(built, show_sample=args.dry_run)
    print(f"Payloads written under runs/seed/{run_id}/requests/translation/ for inspection.")
    print(f"Next: python3 tools/i18n/seed_translations.py submit --run-id {run_id} --stage translation")
    return 0


def _load_anthropic():
    try:
        import anthropic
    except ModuleNotFoundError:
        sys.exit(
            "error: the 'anthropic' package is not installed.\n"
            "This command spends API credit and must only be run by the maintainer, in a "
            "dedicated venv: pip install -r tools/i18n/requirements-seed.txt"
        )
    return anthropic


def cmd_submit(args: argparse.Namespace) -> int:
    """Submit every not-yet-submitted request in a stage (initial or accumulated
    retries) as one new batch. MAINTAINER-ONLY: spends API credit."""
    manifest = load_manifest(args.run_id)
    stage = manifest["stages"][args.stage]
    pending = {cid: r for cid, r in stage["requests"].items() if r["batchId"] is None}
    if not pending:
        sys.exit(f"error: stage '{args.stage}' has nothing pending (every request already "
                 "has a batchId); use 'resume' to poll an in-flight batch")

    verify_hashes(manifest, force_stale=args.force_stale)
    req_dir = _requests_dir(args.run_id, args.stage)
    for cid, r in pending.items():
        payload = json.loads((req_dir / f"{cid}.json").read_text(encoding="utf-8"))
        if _payload_hash(payload["params"]) != r["payloadHash"]:
            sys.exit(f"error: on-disk request {cid} no longer matches its recorded payload "
                     "hash; it may have been hand-edited since prepare")

    anthropic = _load_anthropic()
    from anthropic.types.message_create_params import MessageCreateParamsNonStreaming
    from anthropic.types.messages.batch_create_params import Request

    batch_requests = []
    for cid in sorted(pending):
        payload = json.loads((req_dir / f"{cid}.json").read_text(encoding="utf-8"))
        batch_requests.append(Request(custom_id=cid, params=MessageCreateParamsNonStreaming(**payload["params"])))

    client = anthropic.Anthropic()
    batch = client.messages.batches.create(requests=batch_requests)
    # Persist the batch ID immediately, before any polling, so a crash here is resumable.
    for cid in pending:
        stage["requests"][cid]["batchId"] = batch.id
        stage["requests"][cid]["submittedAt"] = datetime.now(timezone.utc).isoformat()
    save_manifest(args.run_id, manifest)
    print(f"Submitted batch {batch.id} for stage '{args.stage}' ({len(batch_requests)} requests).")
    print(f"Check status: python3 tools/i18n/seed_translations.py status --run-id {args.run_id} --stage {args.stage}")
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    anthropic = _load_anthropic()
    manifest = load_manifest(args.run_id)
    client = anthropic.Anthropic()
    stages = [args.stage] if args.stage else ["glossary", "translation"]
    for stage_name in stages:
        stage = manifest["stages"][stage_name]
        batch_ids = sorted({r["batchId"] for r in stage["requests"].values() if r["batchId"]})
        if not batch_ids:
            print(f"{stage_name}: nothing submitted yet")
            continue
        for bid in batch_ids:
            batch = client.messages.batches.retrieve(bid)
            print(f"{stage_name} batch {bid}: {batch.processing_status} "
                  f"(succeeded={batch.request_counts.succeeded} errored={batch.request_counts.errored} "
                  f"processing={batch.request_counts.processing})")
    return 0


def _queue_retry(manifest: dict, run_id: str, stage: str, cid: str, req_meta: dict,
                  errors: dict[str, str], catalogue: dict[str, dict], units: dict | None) -> None:
    """Build and register a follow-up request scoped to only the failed keys/terms, with
    the exact validation error embedded, up to MAX_FOLLOWUP_ATTEMPTS."""
    root_cid = req_meta.get("retryOf") or cid
    attempt = manifest["retries"][stage].get(root_cid, 0) + 1
    manifest["retries"][stage][root_cid] = attempt
    if attempt > MAX_FOLLOWUP_ATTEMPTS:
        unresolved_path = _run_dir(run_id) / f"{req_meta['locale']}-unresolved.json"
        existing = json.loads(unresolved_path.read_text()) if unresolved_path.is_file() else {"locale": req_meta["locale"], "failed": {}}
        existing["failed"].update({k: v for k, v in errors.items()})
        unresolved_path.write_text(json.dumps(existing, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"{req_meta['locale']}: {cid} exhausted {MAX_FOLLOWUP_ATTEMPTS} follow-up attempts; "
              f"wrote {unresolved_path}")
        return

    retry_cid = f"{root_cid}__retry{attempt}"
    tag = req_meta["locale"]
    entry = catalogue[tag]
    if stage == "glossary":
        terms = list(errors.keys())
        system = _locale_system_prompt(entry, glossary_translations=None)
        user = _glossary_user_message(terms, retry_errors=errors)
        params = _batch_params(system, user, GLOSSARY_RESPONSE_SCHEMA, max_tokens=4000)
        meta = {"locale": tag, "terms": terms}
    else:
        try:
            from tools.i18n import validate_strings as vs
        except ModuleNotFoundError:
            import validate_strings as vs
        keys = list(errors.keys())
        plural_rule = vs._PLURAL_RULES.get(tag, ["one", "other"])
        # Placeholder-only glossary is fine for a retry: caching keys off the same
        # system prompt the original chunk used would require re-deriving it, and a
        # retry is a handful of keys where cache reuse is not the bottleneck.
        glossary = load_glossary()
        glossary_terms = {t: t for t in glossary["consistentTerms"]}
        system = _locale_system_prompt(entry, glossary_translations=glossary_terms)
        user = _translation_user_message(req_meta["filename"], units, keys, plural_rule, retry_errors=errors)
        params = _batch_params(system, user, TRANSLATION_RESPONSE_SCHEMA, max_tokens=16000)
        meta = {"locale": tag, "filename": req_meta["filename"], "keys": keys}
    _register_request(manifest, run_id, stage, retry_cid, meta, params, retry_of=root_cid)
    print(f"{tag}: queued retry {retry_cid} ({len(errors)} key(s), attempt {attempt}/{MAX_FOLLOWUP_ATTEMPTS})")


def cmd_collect(args: argparse.Namespace) -> int:
    """Collect, transport-classify, and semantically validate results for every ended
    batch in a stage. Failed keys are automatically queued as retry requests (up to
    MAX_FOLLOWUP_ATTEMPTS); exhausted ones are written to <locale>-unresolved.json."""
    anthropic = _load_anthropic()
    manifest = load_manifest(args.run_id)
    stage = manifest["stages"][args.stage]
    catalogue = load_catalogue()
    batch_ids = sorted({r["batchId"] for r in stage["requests"].values() if r["batchId"]})
    uncollected = [b for b in batch_ids if any(
        r["batchId"] == b and cid not in stage["results"] for cid, r in stage["requests"].items()
    )]
    if not uncollected:
        print(f"{args.stage}: nothing new to collect")
        return 0

    units = st.extract_source()[0] if args.stage == "translation" else None
    try:
        from tools.i18n import validate_strings as vs
    except ModuleNotFoundError:
        import validate_strings as vs

    client = anthropic.Anthropic()
    results_dir = _results_dir(args.run_id, args.stage)
    results_dir.mkdir(parents=True, exist_ok=True)
    any_incomplete = False

    for bid in uncollected:
        batch = client.messages.batches.retrieve(bid)
        if batch.processing_status != "ended":
            print(f"batch {bid}: still '{batch.processing_status}'; skipping")
            any_incomplete = True
            continue
        for result in client.messages.batches.results(bid):
            cid = result.custom_id  # key only by custom_id -- batch results arrive unordered
            req_meta = stage["requests"].get(cid)
            if req_meta is None or req_meta["batchId"] != bid:
                continue
            transport = _classify_transport(args.stage, result)
            if transport["status"] != "succeeded":
                classified = {**transport, "customId": cid}
                errors = {k: transport.get("reason", transport["status"])
                          for k in (req_meta.get("keys") or req_meta.get("terms"))}
            else:
                payload = transport["payload"]
                if args.stage == "glossary":
                    valid, errs = _validate_glossary_payload(req_meta, payload)
                else:
                    plural_rule = vs._PLURAL_RULES.get(req_meta["locale"], ["one", "other"])
                    valid, errs = _validate_translation_payload(req_meta, payload, units, plural_rule)
                classified = {**transport, "customId": cid, "valid": valid, "errors": errs,
                              "status": "succeeded" if not errs else ("partial" if valid else "retryable")}
                errors = errs
            (results_dir / f"{cid}.json").write_text(json.dumps(classified, indent=2, ensure_ascii=False), encoding="utf-8")
            stage["results"][cid] = classified
            if errors:
                _queue_retry(manifest, args.run_id, args.stage, cid, req_meta, errors, catalogue, units)

    save_manifest(args.run_id, manifest)
    succeeded = sum(1 for r in stage["results"].values() if r["status"] == "succeeded")
    partial = sum(1 for r in stage["results"].values() if r["status"] == "partial")
    retryable = sum(1 for r in stage["results"].values() if r["status"] == "retryable")
    print(f"{args.stage}: {succeeded} fully succeeded, {partial} partial (retry queued), "
          f"{retryable} need a full retry.")
    pending_retries = sum(1 for r in stage["requests"].values() if r["batchId"] is None)
    if pending_retries:
        print(f"{pending_retries} retry request(s) queued; run submit again to send them.")
    return 1 if (any_incomplete or partial or retryable) else 0


def cmd_validate_and_promote(args: argparse.Namespace) -> int:
    """Assemble a locale's collected, validated translation results (across the original
    chunk and any successful retries) into staged Android XML, offline-validate, and
    atomically promote it into app/src/main/res."""
    manifest = load_manifest(args.run_id)
    verify_hashes(manifest, force_stale=args.force_stale)
    tag = args.locale
    catalogue = load_catalogue()
    entry = catalogue.get(tag)
    if entry is None:
        sys.exit(f"error: unknown locale tag '{tag}'")

    units, order = st.extract_source()
    stage = manifest["stages"]["translation"]
    locale_cids = [cid for cid, meta in stage["requests"].items() if meta["locale"] == tag]
    if not locale_cids:
        sys.exit(f"error: no translation requests found for locale '{tag}' in run {args.run_id}")

    unresolved_path = _run_dir(args.run_id) / f"{tag}-unresolved.json"
    if unresolved_path.is_file():
        sys.exit(f"error: {tag} has unresolved keys after retries exhausted -- see {unresolved_path}")

    translated: dict[str, dict] = {}
    for cid in locale_cids:
        result = stage["results"].get(cid)
        if result is None:
            sys.exit(f"error: {tag}: request {cid} has not been collected yet; run collect first")
        for key, value in result.get("valid", {}).items():
            translated[key] = value

    by_file: dict[str, list[str]] = {}
    for key in order:
        by_file.setdefault(units[key].filename, []).append(key)

    staged = _work_dir(args.run_id, tag)
    if staged.exists():
        import shutil
        shutil.rmtree(staged)
    staged.mkdir(parents=True)

    missing: list[str] = []
    for filename, keys in by_file.items():
        entries = []
        for key in keys:
            unit = units[key]
            model_result = translated.get(key)
            if model_result is None:
                missing.append(key)
                continue
            if isinstance(unit, st.StringSource):
                final = st.finalize_translation(model_result["text"], list(unit.tokens), unit.key, unit.envelope)
                entries.append(("string", unit.key, final))
            else:
                payload = {}
                for q, text in model_result.items():
                    _, tokens = st.plural_source_text_for_quantity(unit, q)
                    payload[q] = st.finalize_translation(text, list(tokens), unit.key)
                entries.append(("plurals", unit.key, payload))
        (staged / filename).write_text(st.emit_locale_file(entries), encoding="utf-8")

    if missing:
        sys.exit(f"error: {tag}: {len(missing)} key(s) missing from collected results, first few: {missing[:10]}")

    final_dir = RES / entry["resourceDirectory"]
    try:
        st.promote_locale(tag, staged, final_dir, force=args.force_recovery)
    except (st.SeedValidationError, FileExistsError) as e:
        print(f"error: {e}")
        if isinstance(e, st.SeedValidationError):
            for err in e.errors[:30]:
                print("  " + err)
        return 1
    print(f"{tag}: promoted to {final_dir}")
    return 0


def cmd_resume(args: argparse.Namespace) -> int:
    """Resume any submitted-but-not-fully-collected stage of an existing run, adopting
    the persisted batch ID(s) rather than submitting the same manifest again."""
    manifest = load_manifest(args.run_id)
    rc = 0
    for stage_name, stage in manifest["stages"].items():
        pending = {cid for cid, r in stage["requests"].items() if r["batchId"] and cid not in stage["results"]}
        if not pending:
            continue
        batch_ids = sorted({stage["requests"][cid]["batchId"] for cid in pending})
        print(f"{stage_name}: resuming batch(es) {batch_ids} ({len(pending)} result(s) outstanding)")
        rc = cmd_collect(argparse.Namespace(run_id=args.run_id, stage=stage_name)) or rc
    return rc


def cmd_check(args: argparse.Namespace) -> int:
    return st.cmd_check([t.strip() for t in args.locales.split(",") if t.strip()])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("prepare-glossary", help="build and persist glossary batch requests")
    p.add_argument("--locales", required=True)
    p.add_argument("--run-id", default=None)
    p.add_argument("--dry-run", action="store_true", help="also print one full sample request for review")
    p.set_defaults(func=cmd_prepare_glossary)

    p = sub.add_parser("prepare-translations", help="build translation batch requests from a collected glossary")
    p.add_argument("--locales", required=True)
    p.add_argument("--run-id", default=None)
    p.add_argument("--dry-run", action="store_true", help="also print one full sample request for review")
    p.set_defaults(func=cmd_prepare_translations)

    p = sub.add_parser("submit", help="MAINTAINER ONLY: submit every pending request in a stage (spends credit)")
    p.add_argument("--run-id", required=True)
    p.add_argument("--stage", required=True, choices=["glossary", "translation"])
    p.add_argument("--force-stale", action="store_true")
    p.set_defaults(func=cmd_submit)

    p = sub.add_parser("status", help="poll submitted batch(es) processing status")
    p.add_argument("--run-id", required=True)
    p.add_argument("--stage", choices=["glossary", "translation"], default=None)
    p.set_defaults(func=cmd_status)

    p = sub.add_parser("collect", help="collect, classify, and semantically validate results for ended batches")
    p.add_argument("--run-id", required=True)
    p.add_argument("--stage", required=True, choices=["glossary", "translation"])
    p.set_defaults(func=cmd_collect)

    p = sub.add_parser("validate-and-promote", help="stage, offline-validate, and atomically promote one locale")
    p.add_argument("--run-id", required=True)
    p.add_argument("--locale", required=True)
    p.add_argument("--force-recovery", action="store_true")
    p.add_argument("--force-stale", action="store_true")
    p.set_defaults(func=cmd_validate_and_promote)

    p = sub.add_parser("resume", help="MAINTAINER ONLY: resume any submitted run without resubmitting")
    p.add_argument("--run-id", required=True)
    p.set_defaults(func=cmd_resume)

    p = sub.add_parser("check", help="offline: validate already-generated locale dirs against current source")
    p.add_argument("--locales", required=True)
    p.set_defaults(func=cmd_check)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
