"""Synthetic-only regression cases; never use a learner's export as a fixture."""

from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from analyze_grammar_progress import analyze, ExportError


def fixture():
    logs = []
    details = []
    for log_id, source, exercise, prompt, correct, grade, at in (
        (1, "owner/repo", "a", "choose", False, 1, 100),
        (2, "owner/repo", "a", "type", True, 3, 200),
        (3, "owner/repo", "b", "choose", True, 2, 200),
        (4, "fork/repo", "a", "type", True, 3, 300),
    ):
        logs.append(dict(id=log_id, sourceId=source, stack="grammar", kanji=exercise,
                         mode="Grammar", reading="", grade=grade, atMs=at))
        details.append(dict(reviewLogId=log_id, sourceId=source, stack="grammar", exerciseId=exercise,
                            constructionId="class-" + exercise, relationshipId="contrast",
                            promptType=prompt, response="PRIVATE_TYPED_TEXT" if correct else "", correct=correct))
    return {
        "export_manifest.json": {"formatVersion": 2},
        "review_state.json": [],
        "review_log.json": logs,
        "grammar_responses.json": details,
        "settings.json": {"private-setting": "PRIVATE_SETTINGS_VALUE", "sha:owner/repo@main": "a" * 40},
    }


def session_fixture():
    bundle = fixture()
    bundle["export_manifest.json"]["formatVersion"] = 3
    for log in bundle["review_log.json"]:
        log["sessionId"] = "session-a" if log["sourceId"] == "owner/repo" else None
    bundle["study_sessions.json"] = [
        dict(id="session-a", sourceId="owner/repo", stack="grammar", mode="Grammar", extra=False,
             startedAtMs=50, endedAtMs=300, endReason="completed", answered=4, correct=3),
        dict(id="unfinished", sourceId="owner/repo", stack="grammar", mode="Grammar", extra=True,
             startedAtMs=400, endedAtMs=None, endReason=None, answered=None, correct=None),
    ]
    return bundle


class GrammarAnalysisTest(unittest.TestCase):
    def read(self, bundle, raw=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.zip"
            with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
                for name, value in bundle.items():
                    archive.writestr(name, raw[name] if raw and name in raw else json.dumps(value))
            return analyze(path)

    def test_joined_views_keep_sources_modes_and_exposures_separate_without_private_text(self):
        report = self.read(fixture())
        self.assertEqual(4, report["canonical_review_count"])
        self.assertEqual(3, report["summary"]["distinct_exercises"])
        self.assertEqual(0.75, report["summary"]["correct_rate"])
        self.assertEqual(1, report["summary"]["errors"])
        self.assertEqual(1, report["summary"]["correct_hard"])
        self.assertEqual(1, report["summary"]["empty_responses"])
        owner = next(scope for scope in report["scopes"] if scope["source_id"] == "owner/repo")
        self.assertEqual(["a" * 40], owner["last_synced_commit_hints"])
        self.assertEqual(2, owner["by_construction"][0]["summary"]["answers"])
        self.assertEqual(3, owner["by_relationship"][0]["summary"]["answers"])
        strata = {(row["prompt_type"], row["exposure"]): row for row in report["strata"]}
        self.assertEqual(2, strata["choose", "first_observed"]["answers"])
        self.assertEqual(1, strata["type", "repeated"]["answers"])
        self.assertEqual(1, strata["type", "first_observed"]["answers"])
        self.assertIsNone(strata["choose", "repeated"]["correct_rate"])
        rendered = json.dumps(report)
        self.assertNotIn("PRIVATE_TYPED_TEXT", rendered)
        self.assertNotIn("PRIVATE_SETTINGS_VALUE", rendered)
        self.assertNotIn("private-setting", rendered)

    def test_canonical_time_then_id_order_not_array_order_decides_first_observed(self):
        bundle = fixture()
        expected = self.read(bundle)
        bundle["review_log.json"].reverse()
        bundle["grammar_responses.json"].reverse()
        self.assertEqual(expected, self.read(bundle))
        bundle = fixture()
        bundle["review_log.json"][1]["atMs"] = 100
        expected = self.read(bundle)
        bundle["review_log.json"].reverse()
        self.assertEqual(expected, self.read(bundle))

    def test_v3_sessions_preserve_review_metrics_and_allow_legacy_unassigned_reviews(self):
        expected = self.read(fixture())
        expected["export_format_version"] = 3
        self.assertEqual(expected, self.read(session_fixture()))

    def test_v3_rejects_missing_or_inconsistent_session_evidence(self):
        mutations = [
            lambda b: b.pop("study_sessions.json"),
            lambda b: b["export_manifest.json"].update(formatVersion=2),
            lambda b: b["study_sessions.json"].append(deepcopy(b["study_sessions.json"][0])),
            lambda b: b["review_log.json"][0].pop("sessionId"),
            lambda b: b["review_log.json"][0].update(sessionId="missing"),
            lambda b: b["review_log.json"][3].update(sessionId="session-a"),
            lambda b: b["study_sessions.json"][0].update(mode="WordJpEn"),
            lambda b: b["study_sessions.json"][0].update(extra=1),
            lambda b: b["study_sessions.json"][0].update(endReason="unknown"),
            lambda b: b["study_sessions.json"][0].update(answered=2, correct=2),
            lambda b: b["study_sessions.json"][0].update(correct=5),
            lambda b: b["study_sessions.json"][1].update(answered=0),
            lambda b: b["study_sessions.json"][1].pop("endedAtMs"),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(case=index):
                bundle = session_fixture()
                mutate(bundle)
                with self.assertRaises(ExportError):
                    self.read(bundle)

    def test_v3_mixed_readings_and_borrowed_stacks_share_a_session(self):
        bundle = session_fixture()
        bundle["grammar_responses.json"] = []
        bundle["study_sessions.json"] = [
            dict(id="readings", sourceId="owner/repo", stack="jlpt-n5", mode="KanjiReadings", extra=True,
                 startedAtMs=50, endedAtMs=300, endReason="completed", answered=2, correct=2),
        ]
        bundle["review_log.json"] = [
            dict(id=1, sourceId="owner/repo", stack="jlpt-n4", kanji="x", mode="KanjiOn",
                 reading="a", grade=3, atMs=100, sessionId="readings"),
            dict(id=2, sourceId="owner/repo", stack="jlpt-n5", kanji="x", mode="KanjiKun",
                 reading="b", grade=3, atMs=200, sessionId="readings"),
        ]
        report = self.read(bundle)
        self.assertEqual(2, report["canonical_review_count"])
        self.assertEqual("no_recorded_grammar_reviews", report["grammar_evidence"])

    def test_legacy_and_empty_v2_explicitly_have_no_grammar_evidence(self):
        legacy = {"review_state.json": [], "review_log.json": [], "settings.json": {}}
        report = self.read(legacy)
        self.assertEqual("unavailable_in_legacy_export", report["grammar_evidence"])
        self.assertIsNone(report["summary"]["correct_rate"])
        legacy["review_log.json"] = [dict(sourceId="owner/repo", stack="all", kanji="x",
                                         mode="Quiz", reading="", grade=3, atMs=100)]
        self.assertEqual(1, self.read(legacy)["canonical_review_count"])
        bundle = fixture()
        bundle["review_log.json"] = []
        bundle["grammar_responses.json"] = []
        self.assertEqual("no_recorded_grammar_reviews", self.read(bundle)["grammar_evidence"])

    def test_missing_duplicate_orphan_and_mismatched_links_fail_loudly(self):
        mutations = [
            lambda b: b.pop("grammar_responses.json"),
            lambda b: b["grammar_responses.json"].pop(),
            lambda b: b["review_log.json"].append(deepcopy(b["review_log.json"][0])),
            lambda b: b["grammar_responses.json"].append(deepcopy(b["grammar_responses.json"][0])),
            lambda b: b["grammar_responses.json"][0].update(reviewLogId=99),
            lambda b: b["review_log.json"][0].update(mode="Quiz"),
            lambda b: b["grammar_responses.json"][0].update(sourceId="other/repo"),
            lambda b: b["grammar_responses.json"][0].update(stack="other"),
            lambda b: b["grammar_responses.json"][0].update(exerciseId="other"),
            lambda b: b["review_log.json"][0].update(reading="unexpected"),
            lambda b: b.pop("export_manifest.json"),
            lambda b: (b.pop("export_manifest.json"), b.pop("grammar_responses.json")),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(case=index):
                bundle = fixture()
                mutate(bundle)
                with self.assertRaises(ExportError):
                    self.read(bundle)

    def test_malformed_shapes_types_and_grades_are_not_coerced(self):
        mutations = [
            lambda b: b["export_manifest.json"].update(formatVersion=True),
            lambda b: b["export_manifest.json"].update(formatVersion=3),
            lambda b: b.update({"review_log.json": {}}),
            lambda b: b.update({"review_state.json": [None]}),
            lambda b: b.update({"settings.json": []}),
            lambda b: b["review_log.json"][0].update(id=True),
            lambda b: b["review_log.json"][0].update(grade=0),
            lambda b: b["review_log.json"][0].update(atMs=1.5),
            lambda b: b["review_log.json"][0].update(atMs=-1),
            lambda b: b["grammar_responses.json"][0].update(correct="false"),
            lambda b: b["grammar_responses.json"][0].update(correct=True),
            lambda b: b["grammar_responses.json"][0].update(promptType="future-type"),
            lambda b: b["grammar_responses.json"][0].update(response=None),
            lambda b: b["grammar_responses.json"][0].update(constructionId=""),
            lambda b: b["settings.json"].update({"sha:owner/repo@main": "not-a-commit"}),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(case=index):
                bundle = fixture()
                mutate(bundle)
                with self.assertRaises(ExportError):
                    self.read(bundle)

    def test_duplicate_keys_invalid_json_and_oversize_input_fail(self):
        for raw in ('{"formatVersion":2,"formatVersion":2}', '{"formatVersion":NaN}', "{"):
            with self.subTest(raw=raw), self.assertRaises(ExportError):
                self.read(fixture(), {"export_manifest.json": raw})
        with patch("analyze_grammar_progress.MAX_JSON_BYTES", 10), self.assertRaises(ExportError):
            self.read(fixture())
        with patch("analyze_grammar_progress.MAX_TOTAL_BYTES", 10), self.assertRaises(ExportError):
            self.read(fixture())

    def test_duplicate_archive_member_is_not_silently_collapsed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.zip"
            with zipfile.ZipFile(path, "w") as archive:
                for name, value in fixture().items():
                    archive.writestr(name, json.dumps(value))
                with self.assertWarns(UserWarning):
                    archive.writestr("review_log.json", "[]")
            with self.assertRaisesRegex(ExportError, "Duplicate ZIP"):
                analyze(path)


if __name__ == "__main__":
    unittest.main()
