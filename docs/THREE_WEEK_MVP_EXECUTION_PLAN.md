# Three-Week MVP Execution Plan

Start date: 2026-10-16  
Target: a deployable IELTS Academic Reading MVP that supports a real learner journey and a controlled administrator content journey.

## Release boundary

The launch candidate includes:

- learner and administrator sign-in with role-based access;
- administrator creation, validation, publication, and preview of manually entered passages and one three-passage test;
- standalone passage practice with the approved `OPTION`, `BLANK`, and `MATCHING` question families;
- full-test practice and 60-minute exam simulation, including server-enforced expiry;
- answer submission, scoring, Academic band conversion for full tests, and answer review;
- reproducible deployment, seeded demonstration data, health checks, and a short test report.

The following are deliberately post-launch unless the core flow finishes early:

- live Merriam-Webster integration (a local vocabulary-note foundation may be retained if inexpensive);
- rich learner analytics, bulk import, spaced repetition, and advanced search/filtering;
- non-essential visual polish beyond an accessible responsive reading interface.

## Execution schedule

| Period | Parallel frontend + backend slice | Exit evidence |
|---|---|---|
| Days 1–2 | Phase 0 closure; Phase 1 decisions and PDM | MVP acceptance scenarios, decision log, relational schema and migration plan approved |
| Days 3–5 | Phase 2/3: design contract, React shell, Spring Boot health, PostgreSQL, then first migration/API/UI wiring | frontend build, API health, database migration, and an integrated route demonstrated locally |
| Days 6–9 | Phase 4/5: sign-in UI + session API, then the smallest admin passage-editor form + draft API | authentication/authorization tests; an admin can create and publish one valid passage from the real UI |
| Days 10–13 | Phase 6/9 core: passage-library UI + published list API, reader UI + answer-save API, then submit/result UI + scoring API | learner completes a standalone passage across all question families, receives correct score and review |
| Days 14–16 | Phase 7: full-test selection/session UI + server-timed session API and finalization | full-test session has stable server-derived deadline, idempotent finalization, and recovery after refresh |
| Days 17–19 | Phase 8/10: vocabulary UI/API if core is stable; integrated critical API/E2E, accessibility, security and defect triage | critical learner/admin journeys pass end-to-end; accessibility pass for principal reader path |
| Days 20–21 | Phase 11 | deployed staging/production candidate, seed demo, backup/rollback rehearsal, setup and test evidence |

## Phase rule

No phase closes on a successful manual click alone. Each feature needs normal, validation, permission, retry/duplicate-request, and boundary checks. A defect record must include reproduction, cause, fix, and regression test before closure.

## Decisions due during Days 1–2

1. `USER_ANSWERS.submitted_answer` encoding: store a canonical, family-specific answer value; identifiers are resolved and validated server-side, while blank answers retain normalized entered text plus the original input where review needs it.
2. Content history: published content used by a completed attempt is immutable. Corrections create a new content version rather than editing historical scoring data.
3. Test passage position: retain the passage's intrinsic IELTS part number and store an explicit display order on `TEST_PASSAGES` for deterministic test composition.
4. Academic band table: place the approved raw-score-to-band mapping in versioned seed/reference data, and record the applied table version on finalized full-test results.

Any change to these proposed defaults must be made before migrations are written.

## Daily operating rhythm

1. Define the day’s frontend flow, API contract, persistence rule, and acceptance cases before implementation.
2. Implement only the smallest end-to-end route required for that slice; do not complete frontend or backend in isolation.
3. Run the frontend, API, and database together, then reproduce every defect before fixing it.
4. Review the code together on request: intent, changed files, execution flow, verification, and deliberate omissions.
5. Update the decision/defect journal and mark the planned-versus-completed status.
6. Keep deployment readiness current; never defer secrets, migrations, or environment configuration to the final day.
