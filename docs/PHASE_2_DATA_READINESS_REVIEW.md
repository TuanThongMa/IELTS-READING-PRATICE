# Phase 2 — Data Readiness Review

Status: Approved  
Date: 2026-10-16  
Scope: Cross-check of the approved Phase 1 PDM, Phase 0 baseline, Phase 2 learner/admin UX, and API contract. No migration or application code is created by this review.

## Review result

The core domain is ready: identity and ownership, passage/test content, standalone attempts, full-test sessions, submitted answers, versioned Academic band guidance, categories, and personal vocabulary notes all have a named owner and a persistence direction.

However, five UX promises in Phase 2 require an explicit persisted representation or scoring rule before implementation. They are listed below as Phase 2 closure gates. Treating them as frontend-only behaviour would lose learner work, disclose an answer key, or make a question impossible to mark consistently.

## Data coverage already sufficient for the MVP

| Capability | Existing data support | Review result |
|---|---|---|
| Authentication and learner/private-resource ownership | `users`, owner foreign keys, composite note/category ownership key | Ready for Phase 3 implementation. |
| Standalone practice autosave | `quiz_attempts`, idempotent `user_answers` uniqueness | Ready, subject to the question-family decisions below. |
| Full-test lifecycle and 60-minute exam mode | `test_sessions` with `started_at`, `expires_at`, status, band-table reference, and per-passage attempts | Ready; expiry remains server calculated. |
| Immutable result/review history | published content version rows and finalized attempts referencing their original IDs | Ready. |
| Result counts and simple dashboard | stored correct/answered totals plus derived incorrect/skipped values | Ready; statistics must derive only from finalized work. |
| Personal categories/notes | `categories`, `vocabulary_notes`, `note_categories`, review status | Ready. Deleting a category removes only junction rows, never notes; the eventual foreign key action must be `ON DELETE CASCADE` from category/note to `note_categories`, not from category to note. |
| Standalone passage publication | passage status/version/published timestamp | Ready. |

## Phase 2 closure gates

### DR-01 — Matching-frame data needs an instructor-compatible mapping

The frozen model names `matching_questions` as a recursive frame, but the current PDM does not yet unambiguously answer all of the reader's questions:

1. Where do the selectable candidate labels and their displayed text live?
2. Which one `passage_question` is each matching prompt scored against?
3. How does a shared frame serve multiple numbered questions without duplicating answer options?

This must be resolved without casually replacing the instructor-approved three-family structure. Before implementation, record one concrete row-level example for a five-question matching group: prompt text, selectable label/text pairs, and the correct label for every question. The resulting mapping may then be represented using the approved recursive rows or a narrowly approved supplementary representation. Until that example is confirmed, `MATCHING` is not implementation-ready.

### DR-02 — `OPTION` must declare its group response pattern without exposing the key

The reference and Phase 2 UI support radio rows and checkbox rows. The backend cannot infer which control to render by counting correct options because the active-attempt payload must not contain the answer key.

The official multiple-answer form can contain several numbered answer slots fed by one shared option pool. To preserve one IELTS raw mark per numbered `passage_question`, use this rule:

- add a family-compatible group `task_variant` and `response_pattern`; use per-question single selection for ordinary multiple choice and a shared pool of unique numbered answer slots for a multiple-answer group;
- the active-reader DTO returns the safe response pattern and candidate labels/text, never the number or identity of correct choices; and
- each answer slot still stores one canonical label in `user_answers.submitted_value`. The service validates the shared-pool uniqueness rule before scoring.

This preserves the approved `user_answers.submitted_value text` decision and avoids turning a two-mark IELTS task into one multi-value database answer.

Decision: approved by the project owner on 2026-10-16. The final group metadata names and matching/pool associations are consolidated in `IELTS_READING_TASK_TYPE_ARCHITECTURE_REVIEW.md`.

### DR-03 — “Flagged for review” needs its own state, including unanswered questions

Phase 2 promises `Unanswered`, `Answered`, and `Flagged for review` in the navigator. A flag can exist even when no answer exists, while `user_answers.submitted_value` is non-null. It cannot safely be hidden in the answer row.

Recommended table:

```text
attempt_question_states
  quiz_attempt_id          FK quiz_attempts
  passage_question_id      FK passage_questions
  flagged_for_review       boolean not null default false
  updated_at               timestamptz not null
  PK (quiz_attempt_id, passage_question_id)
```

The service validates that the question belongs to the attempt's immutable passage version. A row may be removed when the flag becomes false. This is an additive learner-state table; it does not alter the approved question-family structure.

Decision: approved by the project owner on 2026-10-16.

### DR-04 — Full-test publishing must enforce the actual Academic test shape

The approved product baseline is one 60-minute Academic Reading full test and the band table is defined for 40 raw answers. The existing rule "three valid published passages" is necessary but insufficient.

Required publish validation, performed transactionally in the service:

- exactly three published passage versions;
- exactly one of each intrinsic part number: 1, 2, and 3;
- deterministic `test_passages.display_order` 1, 2, 3; and
- exactly 40 answerable `passage_questions` across the three passages.

No new table is required. A standalone published passage remains valid with its own question count and never receives a full-test band.

### DR-05 — Personal learner highlighting is separate from answer evidence

The supplied reference highlight is learner-created. It is not an automatic explanation/evidence marker. Personal highlighting should persist for the same practice attempt and its later review, but has no effect on scoring and is unavailable in an active exam.

Required storage: an additive `attempt_annotations` table holding attempt ownership, a versioned text anchor/range, an exact selected-text quote, and timestamps. The anchor specification must use one documented Unicode indexing convention against the immutable passage version. Automatic answer-evidence highlighting remains out of the launch MVP; finalized review shows an explanation only.

Decision: automatic evidence highlighting is deferred. The project owner clarified on 2026-10-16 that the supplied reader highlight is learner-created; personal highlighting remains in scope and requires the stated learner-owned annotation model.

## Resolved scoring rule

### Blank answers

`blank_questions` has one canonical answer and the saved answer normalizes whitespace/case. The approved first-release rule is one canonical normalized answer per blank; any word limit is visible in the group instructions and is not separately enforced by the server. Multiple accepted variants can be added later with an explicit authoring representation and tests; a delimiter embedded in free text is not a hidden rule.

## Implementation-level safeguards to carry into Phase 3

These do not block Phase 2 closure, but belong in the foundation/authoring work:

- On full-test start, materialize or otherwise lock the exact three published passage versions for that session before a learner can answer; a later content correction must never change its test composition.
- Publish/archival and user activation actions should write a minimal administrator audit event (actor, action, target, timestamp, non-sensitive metadata). This is recommended for launch accountability but does not expose personal vocabulary data to administrators.
- Seed data must include one valid, published 40-question test with Parts 1–3, at least one group in each approved family, an active learner, an active administrator, and the approved Academic Reading Practice Guidance v2 table.
- The live dictionary integration and provider attribution remain post-launch/Phase 8 work; they must not block the core learner journey.

## Closure recommendation

All Phase 2 data-readiness gates closed with project-owner approval on 2026-10-16. The matching-frame association, task-variant taxonomy, `attempt_question_states`, and `attempt_annotations` are incorporated into the approved PDM draft. Diagram Label Completion remains explicitly deferred until a content-asset workflow is approved.
