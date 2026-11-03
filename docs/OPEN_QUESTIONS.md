# Open Questions

Last updated: 2026-10-16

These questions remain open and should be resolved only when the relevant CDM section, API, or interface requirement is supplied.

## Vocabulary
- Which Merriam-Webster response identifiers and source metadata must be retained for provenance and future refreshes?
- Should phonetic-level source URL, license name, and license URL be persisted for attribution, or managed as application-level provider metadata?

## Test sessions, attempts, and passages

CDM section reviewed: the diagram containing `USERS`, `TEST_SESSIONS`, `QUIZ_ATTEMPTS`, `READING_TESTS`, `PASSAGES`, and `VOCABULARY_NOTES`.

## Content acquisition

- Will reading content remain manually authored, or will a licensed external source be introduced later?
- What fields and validation rules will be required for a future Excel, CSV, or JSON bulk-import template?

## Approved question model semantics

No open question remains for the frozen three-family table structure. Submitted-answer semantics are recorded in DM-016.

## Phase 2 data-readiness gates

Closed on 2026-10-16. The approved matching frame, constrained task-variant taxonomy, review-flag state, one-normalized-answer blank policy, and personal attempt annotations are recorded in `PHASE_1_PDM_DRAFT.md`, `DATA_MODEL_DECISIONS.md`, and `IELTS_READING_TASK_TYPE_ARCHITECTURE_REVIEW.md`.


## Frontend

- The Reading Desk uses the approved reference frame only for layout and interaction; no external brand, logo, mascot, or visual asset is reused. Its implementation token palette remains adjustable without changing the Phase 2 contracts.
- The confirmed administrator scope is `Content + Users` only; administrators do not access private vocabulary notes or categories.
- The confirmed authoring workflow allows a validated passage to publish independently, or to be reused in a full test. Publishing a full test requires exactly three valid published passages.

