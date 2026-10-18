# Grammar introductions: phased contract

Design for [#48](https://github.com/stop-cran/kanji-cards-android/issues/48), 2026-10-18.
**Implementation boundary:** Phase A established this contract and its reviewed pilot. Phase B implements optional
JLPT metadata, source-scoped cumulative intake/exact browsing and epoch-1 cache refresh. Phase C implements group metadata,
opt-in focus controls, evidence, persistent activation accounting and epoch-2 cache refresh, initially with recommendation scores zero.
Phase D enables the authored scores and their local explanation on the same machinery. Pause after each reviewed phase;
pilot-off selection remains random inside the eligible level scope.

These are explicit, conservative pilot heuristics, not validated mastery measures or an adaptive curriculum.
They do not change FSRS, exercise identities, grading, accepted alternatives or choose/type presentation.

## Scope and controls

Store one optional integer `jlpt` (1..5) on each exercise. Omission means unclassified; explicit null,
strings, booleans, fractions and out-of-range values are invalid. Do not synthesize cumulative tags.
The source-scoped introduction setting defaults to `all`; other values are `unclassified` and `n5`..`n1`.
`nL` matches an annotated exercise exactly when `jlpt >= L`. `all` includes unclassified content;
`unclassified` selects only omitted levels. There is no hidden inclusion of unclassified or harder material.

Browse controls are independent: All, Unclassified, or an **exact** level. Label an introduction setting,
for example, "New grammar: N3 and easier"; label browsing "Browse: N3 only". Counts refer to matching
exercises, not all exercises in files with at least one match. Existing installations retain All.

**User decision:** Practice more applies the same level predicate to unseen exercises. Its already-started
exercises remain eligible at any level, including early reviews, and it retains its existing overdue/new/
soonest-due ordering and recording. Focus, relation ranking and guided prerequisite checks do not apply
there. Explain that it is unguided extra practice within the new-item level scope, not the ordinary intake
budget or guided progression. Extra practice still contributes recorded introductions to shared accounting.

An exercise is started using the existing queue predicate: its state exists and its phase is not New.
All still-imported started exercises remain available for due reviews regardless of level or group gates.
Review availability, relative due ordering/interleaving and daily/backlog accounting stay unchanged.
Only new ordinary introductions consume the ordinary remaining allowance; choosing a group consumes none.
Filtered/blocked pools may underfill that allowance. Never fill the remainder with out-of-scope exercises.

The Phase C guided setting is source-scoped, off by default. Off uses random selection within the level scope;
off + All preserves current ordinary behavior. Switching either setting never resets exercise progress.
Scope/control changes apply to the next session; an existing question/article/back navigation retains its
session snapshot and grading. A stale session cannot mutate the focus cursor of a newer session.

JLPT classifies the grammar needed for the visible task, including its required contrast, not the hardest
word/kanji or every optionally accepted formulation. N5 grammar need not use exclusively N5 words.
Existing English translations, selective readings and comprehensible authoring remain necessary.
Vocabulary-aware assistance and clickable exercise words are separate follow-ups.

## Additive metadata

Keep the content/manifest schema at v1. Older readers already ignore unknown fields. In addition to
exercise `jlpt`, introduce an optional set field `introductionGroups`, default `[]`.
An explicit null is invalid. Definitions have this shape:

```json
{
  "id": "temporal-sequence",
  "label": "Before and after an event",
  "facets": [
    {"id": "before", "label": "The main event is earlier"},
    {"id": "after", "label": "The main event is later"}
  ],
  "members": [
    {"exerciseId": "mae-snack-cleaning", "facetId": "before", "exampleKey": "snack-cleaning"},
    {"exerciseId": "ato-snack-cleaning", "facetId": "after", "exampleKey": "snack-cleaning"}
  ],
  "next": [
    {"groupId": "temporal-stages", "importance": 3, "reason": "Extend relative timing to immediate action phases"}
  ],
  "requires": []
}
```

This abbreviated shape example needs the additional members in the pilot record to satisfy the coverage
rule below; it is not a complete importable group.

Group IDs are unique throughout one source. Facet IDs are unique within a group and identify a particular
learning function, not mastery of every form sharing a spelling. Their stable identity is
`(sourceId, groupId, facetId)`. A recommendation's identity is `(sourceId, fromGroupId, toGroupId)`;
it is distinct from the existing response-detail relationship ID.

In v1, a definition's members reference exercises **in its declaring set**. One set can declare several
groups, but one exercise can belong to at most one authored group. Cross-set recommendations/prerequisites
use the source-global group IDs. This is the explicit cross-set mapping needed by the pilot; merging one
group's membership across files or pooling similarly named facets across groups is not supported.
Use separate groups and explicit links instead of guessing that local construction IDs are equivalent.

Every group/facet/member/reference ID is a nonblank `[a-z0-9-]+` identifier. Labels/reasons are nonblank.
Facets and members are nonempty and unique; each facet has at least two distinct `exampleKey` values.
Each member names an existing exercise and facet. `exampleKey` groups duplicated/near-duplicate contexts
for evidence, not spelling variants or presentations. A paired context can share a key across facets,
but success on one facet does not establish the other. All members of one facet target the same local
construction/answer class; a multi-use local construction may be split into more specific facets.
Authors must mint new exercise/group/facet identities when their tested meaning changes.

`next` and `requires` default to empty arrays; explicit null, duplicate/self links, malformed references,
or invalid importance are errors. Importance is 1 (useful), 2 (strong), or 3 (important), authored and
reviewed rather than inferred from frequency or JLPT. `next` is a directed recommendation, not a prerequisite;
cycles there are permitted. `requires` is an array of source-global group-ID **strings**, for example
`["synthetic-basics"]` in a synthetic fixture that also defines that group. It has no per-element
importance/reason fields; objects, numbers and other non-string elements are invalid.
All named groups must have supported familiarity for guided
ordinary introductions. Prerequisite cycles and missing required groups are errors, never bypassed.
The pilot declares **no genuine prerequisites**; synthetic cases exercise their handling.

Check local shape/IDs before cross-file resolution. Invalid new fields reject their grammar file through
existing import diagnostics. Reject unknown fields inside the new group/facet/member/recommendation
objects so a misspelled prerequisite or priority cannot silently become an absent optional annotation.
This does not impose a new blanket unknown-field rule on legacy grammar objects.
Duplicate source-global groups or prerequisite cycles reject all involved
declaring files; required-reference resolution repeats to a fixed point. A missing optional `next` target
only drops that link with a diagnostic. Valid older/unannotated files remain valid.
If any grammar-file rejection or invalid prerequisite metadata affects the snapshot, block *guided new
introductions* with an explicit content-error reason until a clean sync or explicit opt-out. Do not silently
reinterpret rejected annotations as valid legacy groups. Still-available due content and its history remain
usable under the existing sync/partial-import rules; no progress tables are cleared.
The diagnostic belongs to the **accepted content snapshot** and is stored in the same replacement transaction.
A rejected download (including shrink protection) leaves the last-known-good snapshot and its diagnostics unchanged;
a failed update is not evidence that the retained content has become invalid.

### Compatibility groups

Valid exercises without authored membership use virtual groups keyed
`(sourceId, "legacy", setId, relationshipId)`, with target IDs as facets. These are labeled
"Unguided contrast: <set / relationship>", not asserted to be canonical constructions or prerequisites.
Do not merge same-spelled IDs across sets. They have no relation edges or familiarity-based prerequisite
credentials. Invalid annotations never enter this path.

Within a compatibility group, rotate among targets and introduce at most two unseen exercises per target
per activation, subject to the daily allowance. Once that quota or its eligible pool is exhausted, release
the focus at the next ordinary session without declaring familiarity. This avoids an unannotated singleton
permanently blocking progression. Compatibility groups are available through exploration, an explicit
focus choice, or fallback when no authored group can introduce new material.

## Familiarity and coverage

Use only canonical recorded Grammar events joined one-to-one to their response details for the current
source. An event must match an imported member's exercise ID, target and relationship. Old events have
no set ID or per-event content revision: stable exercise meaning and unambiguous current mapping are
required; conflicting/unmappable evidence is unknown and explained, never guessed or rewritten.
These mappings are a compatibility assumption, not proof of historical wording. Authoritative per-answer
provenance is out of scope. No private pilot data is required to tune these rules.

For **each authored facet**, evaluate the rolling interval `[now - 21 days, now]`. Within that interval:

1. Find the latest Again/incorrect or Hard event for that facet. Only positive events strictly after it
   can support familiarity. Hard counts as exposure but not confident recall; it is a grade, not a separate
   guessed flag. Report inconsistent grade/verdict pairs instead of treating them as positive.
2. Positive means `correct=true` with Good/Easy (3/4). Require positives from at least **two distinct
   example keys**, at least **one typed positive**, and at least **24 elapsed hours** between the earliest
   and latest qualifying positives. Multiple same-key attempts cannot supply the second context.
3. All facets must satisfy that predicate for the group to have **supported familiarity**. This is not
   complete mastery, a probability, a JLPT certification, or knowledge of every accepted equivalent.

The 21-day window is the pilot's simple evidence-expiry/decay rule; no second FSRS or fitted weights.
Each canonical event appears once in a facet's evidence set. Shared contexts across facets do not become
independent sample counts in a summed group score: familiarity is a conjunction, not an accuracy average.
Future-dated events are excluded with a clock/evidence diagnostic. Successful practice-only confirmations
are absent and cannot count. Practice more and pilot-off events can contribute if their mapping is valid.
Evidence is source-wide, including previously learned material outside the current introduction level.

Minimum *introduction coverage* is separately two started example keys per facet. An incorrect introduction
can satisfy exposure coverage but not familiarity. Selecting/opening a group without an answer satisfies
neither. Evidence expiry or a later error can remove supported familiarity without changing any SRS state.

## Focus lifecycle and selection

An authored group is coherent for guided intake only when every facet has at least two usable example
keys. Usable means currently imported and either started or within the new-item scope. It must also have
at least one eligible unseen member and meet all true prerequisites. Thus a scope cannot split an inseparable
contrast into a misleading partial lesson. Explain a partially eligible group's blocked facets; its valid
exercises remain available through pilot-off or unguided Practice more within their level scope.

Keep one active group across ordinary sessions. Per activation, introduce at most **two new exercise IDs
per facet**, counting recorded first introductions, not re-asks. Select facets with the fewest introductions
in this activation first; within them prefer never-exposed example keys, then remaining unseen members.
Tie-break by the stable hash rule below. Stop at the ordinary allowance; do not open a second group to
fill one session's new-item queue. Old due reviews remain mixed normally.

At the next ordinary-session start:

- Continue an eligible active focus if it has remaining introduction quota and does not yet have both
  minimum coverage and supported familiarity.
- Release an authored focus when it has minimum coverage **and** supported familiarity, or has no eligible
  unseen members. Exhaustion alone never declares familiarity.
- If its per-activation quota is used but unseen members remain and familiarity is insufficient, keep the
  focus and show "Review this group, or change focus"; do not dump the rest of its exercises.
- "Change focus" can choose another eligible group without claiming that this one is learned. "Revisit"
  explicitly starts a fresh bounded activation, including additional examples of weak/familiar groups.
  Neither bypasses level scope or guided prerequisites. If no alternative qualifies, explain that outcome.
  A fresh, uncommitted activation may introduce its bounded examples even if the group is already familiar;
  normal familiarity-based release applies after commitment. Otherwise an explicit familiar revisit would release before its first question.
- Revalidate after sync/scope changes. Clear a removed/incoherent/prerequisite-ineligible focus with a
  specific explanation, retain its review history and select another eligible group when allowance permits.
- Disabling the pilot clears only the active cursor; automatic-selection accounting and SRS history remain.
  Re-enabling starts a newly validated focus. A source switch loads that source's separate cursor.

Phase C established the rules below with all recommendation scores zero. Phase D enables the authored scores;
both use the same candidate provider and focus state, not two queues.

### Automatic group choice and bounded exploration

The next automatic activation ordinal starts at **1**. Only its first *recorded new introduction* commits
that ordinal and increments it. Previewing Home, opening/abandoning a session, zero allowance or a due-only
session cannot advance it. Manual activations do not advance it.

On ordinary automatic slots, prefer eligible authored groups; use compatibility groups if none qualify.
Within that pool prefer groups without supported familiarity if any exist, then rank by recommendation
score: the **maximum**, not sum, of incoming importance from supported familiar groups (zero if none).
That prevents duplicate links or overlapping evidence from accumulating artificial priority.

Every **third** automatic activation is exploration. Consider authored and compatibility groups together,
then form the **exploration pool**: use groups without supported familiarity if any are eligible,
otherwise use all eligible groups. Prefer score-zero groups within that pool; use ordinary score ordering
within the pool only when it contains no score-zero candidate. A familiar score-zero group does not
displace an eligible unfamiliar related group. Never widen level/prerequisite eligibility.

For ties, sort ascending by the lowercase hex SHA-256 of UTF-8
`sourceId + "\\n" + automaticOrdinal + "\\n" + groupId`; these separators denote actual LF characters.
For virtual group IDs use `legacy:<setId>:<relationshipId>`; authored IDs cannot contain `:`.
Within-group ties append LF, facet ID, LF, exercise ID to the same input; a manual activation uses its
own persisted monotonically increasing selection nonce in place of the automatic ordinal.
This is deterministic tie variation, not cryptographic secrecy. Exercise count never enters group ranking.

**Bound:** each block of three committed automatic activations contains an exploration selection on its
third slot. If its familiarity-prioritized exploration pool contains a score-zero candidate at that
selection, that slot must select one. This does not promise to select every globally eligible score-zero
group: a familiar one is outside the pool while any unfamiliar group is eligible.
There can be at most two intervening automatic non-exploration activations between these opportunities.
This is not a wall-clock guarantee, a promise about manual choices, or a requirement to pick unavailable
content. Manual moves and abandoned uncommitted selections do not consume an exploration slot.
An active focus is not interrupted merely because a different candidate becomes available.

The local readout identifies the current group and its recommendation priority (0..3, maximum not sum),
and shows the strongest contributing authored links with source group/facet IDs, reasons, positive-context
counts, typed counts and elapsed spans. These source groups need supported familiarity, not eligibility for
more new material: an exhausted or out-of-scope learned group can still support a recommendation. Evidence
expiry or a later Again/Hard removes its support on the next calculation. No score or link confers familiarity
on the destination, bypasses prerequisites, or changes the active session snapshot.

## Persistence, integration and deployment

Phase B stores source-scoped level/browse preferences and a **parser epoch 1** marker.
Phase C adds group decoding/validation and **epoch 2**. Pre-Phase-B caches have epoch 0: typed reserialization
in `toGrammarEntities` dropped previously unknown fields, and both SHA and contentVersion can skip import.
An epoch mismatch bypasses those two up-to-date shortcuts, **not** the existing bad-content/shrink safeguards.
The launch-check throttle must not report an outstanding epoch as up to date; offline archive fallback backoff still applies.
Write the source's new epoch only after the existing atomic content replacement succeeds. Failed/offline
refresh retains the old usable cache and an outstanding epoch for later refresh; do not clear content or progress.
Changing supported content metadata also regenerates its manifest/contentVersion as usual.
Phase D needs no further parser epoch, content/manifest change, cursor format or database migration:
epoch 2 already retains the validated recommendation metadata.

An outstanding epoch is **metadata unavailable**, not evidence that cached omissions were authored.
The source's last successfully imported epoch determines which new-intake operations are safe:

| Successful epoch | Available new intake while refresh is pending |
| --- | --- |
| 0 (legacy typed cache) | Unguided All only, in ordinary sessions or Practice more; no level-dependent or guided introductions |
| 1 (level-aware cache) | Unguided intake/Practice more using any selected level scope; no guided introductions until epoch 2 |
| 2 (group-aware cache) | Normal scope/group rules, including valid omissions and the separate invalid-metadata diagnostics |

All started, still-imported due/extra reviews remain eligible in every row. Do not silently switch a
restricted scope to All or turn the pilot off: keep the selected settings, block only the affected new
intake, and explain "Sync needed for level-filtered new material" or "Sync needed for guided introductions".
Practice more is always unguided, so All remains safe there at epoch 0 even if ordinary guided intake is
blocked. Exact-level/Unclassified browse counts are likewise unavailable until epoch 1, not misleadingly
zero or populated from erased fields. Only a successful level/group-aware import permits treating missing
metadata as genuinely unclassified/unannotated. After refresh, recompute eligibility without resetting SRS.

Phase C adds one Room cursor row per source in the additive v8 -> v9 migration:
automatic ordinal, monotonically increasing selection nonce, and optional active group/origin/token,
reserved ordinal, committed flag and introduced exercise IDs for that activation. It is an intake cursor,
not another schedule. Updating it on a recorded introduction shares the existing `recordGrammar` transaction
with state/log/detail writes. Only a matching session focus token may update it; stale-session answers still
use their original exercise/SRS identity but cannot advance or consume another focus's quota.
A repeated answer cannot commit an activation twice. A crash cannot leave half of an activation/event write.
Malformed persisted cursor data is reported rather than silently treated as a fresh learner.

Preview functions are pure: Home must not reserve a focus, advance counters or record evidence. Starting
an ordinary session with new allowance reserves its chosen focus. Filtered random/Practice more selection
never manipulates that cursor. Re-read current state at session start, so a preview is not a second queue.
Preserve session snapshots, article/back behavior and the existing single-user session model.

Build the ordinary queue from all available started IDs plus **only the selected new IDs** using the
existing queue builder. Its shuffle cannot reintroduce excluded candidates; preserve due urgency/noise
and interleaving. Use the same candidate calculation for home counts and empty-state explanations.
One askable exercise is enough; its authored options do not need a second exercise as a distractor pool.
Extra-practice availability uses all started IDs plus all scope-matching unseen IDs, without guided gates.

Export v2's five members and canonical/detail identity remain unchanged. The new Room cursor is local
selection state, not added review evidence; document its omission from progress ZIPs and ordinary Android
backup behavior. The local decision readout contains IDs, policy/reason, aggregate evidence counts/times
and rank inputs only, not raw typed responses. No telemetry, dashboards or private-export publication.

## Bounded pilot and executable acceptance cases

The exact proposed classifications/members and inspected sources are in the content repository's
`docs/grammar-introductions-pilot.json`, based on content commit
`c2a98448ca7f7c5f77c199743b3d5ec21d851c97`. This is a design record, not imported runtime metadata.

| Group | Existing exercises | Proposed level | Purpose |
| --- | ---: | --- | --- |
| `temporal-sequence` | 6, before/after | N4 | Contrast including the N4 after-event pattern |
| `temporal-stages` | 9, before/during/just-after | N4 | Related next step; immediate phases, not identical event-order meanings |
| `expectation-obligation` | 4, hazu/beki | N3 | Required N3 contrast; disconnected exploration candidate |

Only `temporal-sequence -> temporal-stages` has a recommendation, importance 3; no prerequisite.
The relation is a reviewed editorial teaching choice supported by their temporal subject matter, not an
institutional recommendation of this learning order or proof of transfer. The four remaining exercises in
`formal-nouns-pilot` and the other untouched content demonstrate unclassified/compatibility access.
There are no claimed N5/N2/N1 pilot classifications; synthetic fixtures cover all five levels. At N4 the
pilot has 15 classified candidates, at N3/N2/N1 19, and at N5 zero. All still includes the entire corpus.

These are implementation acceptance cases, not claims that Phase A has executed a selector:

| Case | Required result |
| --- | --- |
| All 25 `(selectedLevel, exerciseLevel)` pairs | Match iff exerciseLevel >= selectedLevel; null only in All/Unclassified |
| N4 scope with previously started N3 exercise | New N3 excluded; its due and extra-practice reviews retained |
| One eligible unseen exercise; zero eligible exercises | Correct start/count or explicit empty reason, no two-exercise gate |
| Two context keys, typed Good, positives at t and t+24h | Facet supported if all positives are within 21 days and after its last Again/Hard |
| Only choice positives, one context key, or span <24h | Not supported; coverage and familiarity remain distinct |
| Later Again/Hard; events expire; future timestamp | Recompute conservative status; no SRS reset; report future-clock evidence |
| New introductions after group activation | Per-facet quota 2; retries do not consume it; no second group in that session |
| Quota used but insufficient familiarity | Review/change-focus state, not more unbounded unseen exercises |
| Familiar A recommends unfamiliar B at 3; unfamiliar unrelated C at 0 | B wins a comparable ordinary slot; C wins exploration when both qualify for its pool |
| Familiar score-zero A and unfamiliar related B at 3 are eligible | B is the exploration pool; familiar A does not displace it |
| Automatic slots 1..6 with score-zero choices in each exploration pool | Slots 3 and 6 are score-zero exploration; manual/abandoned starts consume none |
| Add 100 unseen examples to one candidate group | Its group score/tie rank does not improve |
| Same local target ID in two sets; duplicate ownership | No inferred evidence pooling; invalid authored ownership reported |
| Missing next target vs missing/cyclic requires | Optional edge warning vs explicit guided-intake block; no silent legacy conversion |
| `requires: ["synthetic-basics"]` vs object/number elements | String reference resolves in its synthetic source; wrong element types are rejected |
| One scoped facet lacks two usable keys | Group blocked with reason; scope not broadened; out-of-scope due reviews retained |
| Scope/pilot/source changes, sync removal/reintroduction | Cursor revalidated, no reset of exercise history or another source's cursor |
| Crash after answer, repeat callback, stale focus token | One atomic event/activation commit; stale token cannot update a new focus |
| Cache epoch mismatch with unchanged SHA/manifest | Reimport after successful sync without bypassing content safety or clearing progress |
| Epoch 0 offline with N4/Unclassified selected or guided enabled | Affected new intake blocked; no erased-field classification/legacy fallback; started reviews retained |
| Epoch 0 All Practice more; epoch 1 N4 Practice more | Safe unguided new intake allowed; neither enables ordinary guided intake before epoch 2 |

The implementation phases must turn these into focused pure-policy and integration cases and verify
the actual stated boundaries. Revisit these heuristics after each reviewed phase, not by silently changing
thresholds while implementing. No efficacy claim follows from deterministic selection or a smoke test.

### Retain/revise decision and limits

Retain the bounded, opt-in pilot and the agreed evidence/quota/exploration thresholds. Functional acceptance
supports the selection and persistence contract, not optimal learning order or improved retention. Do not
expand classifications, invent prerequisites or tune thresholds from these synthetic cases.

The pilot still has only three authored groups/19 classified exercises and one editorial recommendation;
other valid exercises retain the compatibility path. Stable historical exercise meaning remains an authoring
assumption, not per-answer provenance. No exploration guarantee promises every group, and no physical-device
or real-learner efficacy claim follows from local tests and bounded emulator checks. Revisit heuristics only
as an explicitly scoped follow-up with appropriate evidence; preserve the unguided opt-out.
