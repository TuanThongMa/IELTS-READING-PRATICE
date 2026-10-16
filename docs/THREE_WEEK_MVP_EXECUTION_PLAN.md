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

| Period | Phases and focus | Exit evidence |
|---|---|---|
| Days 1–2 | Phase 0 closure; Phase 1 decisions and PDM | MVP acceptance scenarios, decision log, relational schema and migration plan approved |
| Days 3–5 | Phase 2 and Phase 3 | wireframes/API catalogue; Spring Boot, frontend, PostgreSQL, migrations, health endpoint, automated baseline checks |
| Days 6–9 | Phase 4 and the essential part of Phase 5 | authentication/authorization tests; an admin can publish one valid passage and compose a test |
| Days 10–13 | Phase 6 and Phase 9 core | learner completes a standalone passage across all question families, receives correct score and review |
| Days 14–16 | Phase 7 | full-test session has stable server-derived deadline, idempotent finalization, and recovery after refresh |
| Days 17–19 | Phase 10 | critical API/E2E tests, accessibility pass for principal reader path, security and defect triage |
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

1. Define the day’s vertical slice and acceptance cases before implementation.
2. Implement only the smallest end-to-end route required for that slice.
3. Run automated checks and reproduce every defect before fixing it.
4. Update the decision/defect journal and mark the planned-versus-completed status.
5. Keep deployment readiness current; never defer secrets, migrations, or environment configuration to the final day.
