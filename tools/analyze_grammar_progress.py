"""Local, aggregate-only readout of an Enso Nihongo progress ZIP (Python stdlib)."""

import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import sys
import zipfile


MAX_JSON_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 32 * 1024 * 1024
LEGACY_ENTRIES = {"review_state.json", "review_log.json", "settings.json"}
V2_ENTRIES = LEGACY_ENTRIES | {"export_manifest.json", "grammar_responses.json"}
LIMITATIONS = [
    "These are recorded reviews, not all screen answers: successful practice confirmations are not logged.",
    "First-observed means first in this export for (source, stack, exercise), not first-ever learning.",
    "Construction and relationship views reuse the same events; do not add their sample sizes.",
    "Recorded IDs are not global concepts; an ID may be reused across sets within a source.",
    "An accepted-answer class does not demonstrate mastery of each individual form.",
    "Hard is a grade, not an exported guessed flag; empty responses are a give-up proxy.",
    "Choose/type, repeated exposure and visible English confound accuracy comparisons.",
    "Small, self-selected author/reviewer samples cannot establish learning effectiveness.",
    "Cached sync commits are export-time hints, not per-event content revisions; branches share source identity.",
    "The report omits response text and general settings, but still contains content/source IDs. Keep it private.",
]


class ExportError(ValueError):
    """An incomplete or inconsistent export, not an empty evidence set."""


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ExportError("Duplicate JSON object key")
        result[key] = value
    return result


def _nonfinite(_value):
    raise ExportError("Non-finite JSON number")


def _load(path):
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = {entry.filename for entry in entries}
        if len(names) != len(entries):
            raise ExportError("Duplicate ZIP entry")
        if names not in (LEGACY_ENTRIES, V2_ENTRIES):
            raise ExportError("Expected the three legacy files or all five v2 files")
        if sum(entry.file_size for entry in entries) > MAX_TOTAL_BYTES:
            raise ExportError("Export exceeds the 32 MiB total JSON limit")
        result = {}
        total = 0
        for entry in entries:
            if entry.flag_bits & 1 or entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                raise ExportError("Encrypted or unsupported ZIP compression")
            if entry.file_size > MAX_JSON_BYTES:
                raise ExportError("Export exceeds the 16 MiB per-file JSON limit")
            with archive.open(entry) as member:
                raw = member.read(MAX_JSON_BYTES + 1)
            total += len(raw)
            if len(raw) > MAX_JSON_BYTES or total > MAX_TOTAL_BYTES:
                raise ExportError("Inflated JSON exceeds the export size limits")
            try:
                result[entry.filename] = json.loads(
                    raw.decode("utf-8"), object_pairs_hook=_unique_object, parse_constant=_nonfinite
                )
            except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as error:
                raise ExportError(f"{entry.filename}: invalid UTF-8 JSON") from error
        return result


def _integer(row, key, minimum=0, maximum=2**63 - 1):
    value = row.get(key)
    if type(value) is not int or not minimum <= value <= maximum:
        raise ExportError(f"Invalid integer field: {key}")
    return value


def _text(row, key, empty=False):
    value = row.get(key)
    if not isinstance(value, str) or (not empty and not value.strip()):
        raise ExportError(f"Invalid text field: {key}")
    return value


def _rows(bundle, name):
    value = bundle[name]
    if not isinstance(value, list) or any(not isinstance(row, dict) for row in value):
        raise ExportError(f"{name}: expected an array of objects")
    return value


def _join(bundle, version):
    rows = _rows(bundle, "review_log.json")
    logs = {}
    for row in rows:
        for key in ("sourceId", "stack", "kanji", "mode"):
            _text(row, key)
        _text(row, "reading", empty=True)
        _integer(row, "grade", 1, 4)
        _integer(row, "atMs")
        if version == 1:
            if row["mode"] == "Grammar":
                raise ExportError("Legacy export contains Grammar reviews without their details")
            continue
        log_id = _integer(row, "id", 1)
        if log_id in logs:
            raise ExportError("Duplicate canonical review ID")
        logs[log_id] = row
    if version == 1:
        return len(rows), []
    grammar_ids = {key for key, row in logs.items() if row["mode"] == "Grammar"}
    details = {}
    for row in _rows(bundle, "grammar_responses.json"):
        log_id = _integer(row, "reviewLogId", 1)
        if log_id in details:
            raise ExportError("Duplicate grammar response link")
        if log_id not in grammar_ids:
            raise ExportError("Orphan response or response linked to a non-Grammar review")
        log = logs[log_id]
        for detail_key, log_key in (("sourceId", "sourceId"), ("stack", "stack"), ("exerciseId", "kanji")):
            if _text(row, detail_key) != log[log_key]:
                raise ExportError(f"Grammar response identity mismatch: {detail_key}")
        if log["stack"] != "grammar" or log["reading"] != "":
            raise ExportError("Invalid Grammar stack or reading identity")
        for key in ("constructionId", "relationshipId"):
            _text(row, key)
        if row.get("promptType") not in ("choose", "type") or type(row.get("correct")) is not bool:
            raise ExportError("Invalid grammar prompt type or correctness")
        _text(row, "response", empty=True)
        if (log["grade"] == 1) == row["correct"]:
            raise ExportError("Grammar correctness contradicts the canonical grade")
        details[log_id] = row
    if set(details) != grammar_ids:
        raise ExportError("A canonical Grammar review is missing its response detail")
    seen = set()
    events = []
    for log_id in sorted(grammar_ids, key=lambda key: (logs[key]["atMs"], key)):
        log, detail = logs[log_id], details[log_id]
        identity = (log["sourceId"], log["stack"], log["kanji"])
        events.append({
            key: detail[key] for key in
            ("sourceId", "stack", "exerciseId", "constructionId", "relationshipId", "promptType", "correct")
        } | {
            "grade": log["grade"],
            "empty": detail["response"] == "",
            "exposure": "repeated" if identity in seen else "first_observed",
        })
        seen.add(identity)
    return len(logs), events


def _metrics(events):
    total = len(events)
    correct = sum(event["correct"] for event in events)
    return {
        "answers": total, "correct": correct, "errors": total - correct,
        "correct_rate": round(correct / total, 6) if total else None,
        "hard": sum(event["grade"] == 2 for event in events),
        "correct_hard": sum(event["grade"] == 2 and event["correct"] for event in events),
        "empty_responses": sum(event["empty"] for event in events),
        "distinct_exercises": len({(e["sourceId"], e["stack"], e["exerciseId"]) for e in events}),
    }


def _view(events):
    return {
        "summary": _metrics(events),
        "strata": [
            {"prompt_type": prompt, "exposure": exposure, **_metrics([
                event for event in events if event["promptType"] == prompt and event["exposure"] == exposure
            ])}
            for prompt in ("choose", "type") for exposure in ("first_observed", "repeated")
        ],
    }


def _group(events, key):
    groups = defaultdict(list)
    for event in events:
        groups[event[key]].append(event)
    return [{"recorded_id": identity, **_view(rows)} for identity, rows in sorted(groups.items())]


def _commit_hints(settings, source):
    commits = set()
    for key, value in settings.items():
        if key == f"sha:{source}" or key.startswith(f"sha:{source}@"):
            if not isinstance(value, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", value):
                raise ExportError("settings.json: invalid cached sync commit")
            commits.add(value.lower())
    return sorted(commits)


def analyze(path):
    bundle = _load(path)
    version = 1
    if "export_manifest.json" in bundle:
        manifest = bundle["export_manifest.json"]
        if not isinstance(manifest, dict) or _integer(manifest, "formatVersion", 2, 2) != 2:
            raise ExportError("Unsupported export manifest")
        version = 2
    _rows(bundle, "review_state.json")
    settings = bundle["settings.json"]
    if not isinstance(settings, dict):
        raise ExportError("settings.json: expected an object")
    total, events = _join(bundle, version)
    scopes = defaultdict(list)
    for event in events:
        scopes[(event["sourceId"], event["stack"])].append(event)
    return {
        "export_format_version": version,
        "grammar_evidence": (
            "unavailable_in_legacy_export" if version == 1 else
            "recorded_reviews" if events else "no_recorded_grammar_reviews"
        ),
        "canonical_review_count": total,
        **_view(events),
        "scopes": [
            {
                "source_id": source, "stack": stack,
                "last_synced_commit_hints": _commit_hints(settings, source),
                **_view(rows),
                "by_construction": _group(rows, "constructionId"),
                "by_relationship": _group(rows, "relationshipId"),
            }
            for (source, stack), rows in sorted(scopes.items())
        ],
        "limitations": LIMITATIONS,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export", type=Path, help="Local progress ZIP; never extracted or uploaded")
    args = parser.parse_args()
    try:
        report = analyze(args.export)
    except (ExportError, OSError, zipfile.BadZipFile) as error:
        parser.exit(2, f"Cannot analyze export: {error}\n")
    print(json.dumps(report, indent=2, sort_keys=True, ensure_ascii=True))


if __name__ == "__main__":
    main()
