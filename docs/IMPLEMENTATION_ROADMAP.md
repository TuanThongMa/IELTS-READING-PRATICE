# IELTS Academic Reading System — Implementation Roadmap

Last updated: 2026-10-16

## 1. Planning principles

The project is divided into phases that each produce a reviewable artifact or a working business flow. Implementation does not begin until Phase 1 closes the data-model questions that affect persistence.

Every implementation phase follows the same learning loop:

1. Confirm the business rule and acceptance scenarios.
2. Study the relevant technical concept and one or two common failure modes.
3. Design the API, data changes, and interface states before coding.
4. Implement the smallest complete vertical slice.
5. Test normal, invalid, boundary, permission, and failure cases.
6. Record defects, their causes, and the final decision in the project documentation.
7. Demonstrate the flow and close the phase only when its exit criteria pass.

## 2. Target architecture

- Frontend: React 19 + TypeScript + Vite + React Router; communicates only with the Spring Boot REST API. The approved Phase 3 technical foundation is recorded in `PHASE_3_TECHNICAL_FOUNDATION.md`.
- Backend: Spring Boot with thin controllers, focused services, repositories, DTO validation, centralized error handling, and explicit transactions.
- Database: PostgreSQL managed through versioned migrations. Application schema must not be changed manually after the migration workflow begins.
- External dictionary: Merriam-Webster Learner's Dictionary API, called only by the backend. The API key must never be exposed to the browser or committed to source control.
- Data ownership: PostgreSQL is authoritative for application data. Merriam-Webster is an external enrichment source and may be unavailable.

## 3. Phase overview

| Phase | Main outcome | Depends on | Suggested effort |
|---|---|---|---|
| 0 | Scope, terminology, and success criteria are frozen | Current CDM | 2–3 days |
| 1 | Approved LDM/PDM, data dictionary, constraints, and migrations plan | Phase 0 | 5–7 days |
| 2 | Sitemap, user flows, wireframes, design tokens, and API contract conventions | Phase 0 | 5–7 days |
| 3 | Runnable project foundation and local environment | Phases 1–2 | 4–6 days |
| 4 | Authentication, authorization, users, and category ownership | Phase 3 | 5–7 days |
| 5 | Administrator content-authoring and publishing flow | Phase 4 | 8–12 days |
| 6 | Individual-passage practice flow | Phase 5 | 8–10 days |
| 7 | Full-test session and exam timer flow | Phase 6 | 7–10 days |
| 8 | Merriam-Webster lookup and personal vocabulary notes | Phases 4, 6 | 7–10 days |
| 9 | Scoring, review, band conversion, and learner statistics | Phases 6–8 | 6–8 days |
| 10 | Security, reliability, performance, accessibility, and end-to-end QA | Phases 4–9 | 8–12 days |
| 11 | Deployment, seeded demonstration, documentation, and thesis evidence | Phase 10 | 5–7 days |

The estimates are learning-oriented effort, not fixed deadlines. Phases 1 and 2 may overlap after shared terminology is frozen. Implementation phases should otherwise preserve the dependency order.

## 4. Detailed phases

### Phase 0 — Scope and requirement baseline

Objectives:

- Freeze the first-release boundary as IELTS Academic Reading only.
- Create a glossary for test, passage, question group, question family, session, attempt, submitted answer, vocabulary entry, sense, and note.
- Turn the confirmed flows into acceptance scenarios for learner and administrator roles.
- Separate first-release requirements from later ideas such as bulk import, spaced repetition, and additional dictionary providers.

Deliverables:

- Reviewed requirement baseline.
- Prioritized product backlog labelled `MVP`, `Should`, and `Later`.
- Acceptance-scenario catalogue using Given/When/Then or an equivalent format.
- Risk register covering content licensing, dictionary availability, exam timing, and historical-result integrity.

Topics to learn:

- Functional versus non-functional requirements.
- Acceptance criteria and traceability from requirement to test.
- Domain terminology and bounded scope.

Common errors to investigate:

- Expanding scope while implementation is already under way.
- Treating a UI idea as a confirmed business rule.
- Mixing official IELTS rules with custom practice features.
- Leaving ownership and permission rules implicit.

Exit criteria:

- Every MVP function has an actor, precondition, primary flow, failure flow, and measurable expected result.
- Open questions that block the physical data model are explicitly listed.

### Phase 1 — Logical/physical data model and PostgreSQL data dictionary

Objectives:

- Convert the approved CDM to an LDM/PDM without redesigning the frozen three-family question structure.
- Select PostgreSQL types, nullability, defaults, foreign keys, unique constraints, checks, and indexes.
- Define lifecycle values for content, test sessions, attempts, and answers.
- Resolve the remaining encoding rules for submitted answers and content versioning.

Deliverables:

- Final relational diagram.
- Data dictionary for every table and column.
- Constraint and index matrix explaining why each item exists.
- Migration order and rollback/recovery strategy.
- Seed-data plan containing roles, sample content, and Academic band-conversion data.

Topics to learn:

- PostgreSQL keys, `TEXT`, timestamps, checked values/enums, and junction tables.
- Index selection based on real lookup and ownership queries.
- Transaction boundaries and referential integrity.
- Schema migrations and why production schemas must be reproducible.

Common errors to investigate:

- Using `VARCHAR(255)` for passages, instructions, definitions, or explanations.
- Missing indexes on foreign keys used for joins and ownership checks.
- Duplicate data caused by absent composite unique constraints.
- Cascade deletion erasing completed attempts or answer history.
- Storing derived values without deciding their source of truth.
- Applying content edits retroactively to previously completed attempts.

Exit criteria:

- Every relationship and optional field matches a confirmed workflow.
- A fresh PostgreSQL database can eventually be produced entirely from ordered migrations and seed data.
- Historical attempts cannot be silently invalidated by ordinary content edits.

### Phase 2 — UX structure and API contract conventions

Objectives:

- Agree on color palette, typography, responsive targets, sitemap, and main layouts before frontend coding.
- Design complete states for learner and administrator flows.
- Define REST naming, pagination, validation errors, date/time representation, and response conventions.

Required screens/states:

- Sign in and account state.
- Learner dashboard and passage/full-test selection.
- Practice reader, exam reader, navigation, timer, autosave state, and submission confirmation.
- Result summary and question review.
- Vocabulary lookup, sense selection, notes, categories, and review status.
- Admin content list, editor, question-group editor, preview, validation, and publish flow.
- Loading, empty, partial, forbidden, expired, offline, external-API failure, and retry states.

Deliverables:

- Sitemap and role-based navigation map.
- Low-fidelity wireframes, followed by an agreed visual direction.
- Reusable design-token and component inventory.
- Initial OpenAPI contract or endpoint catalogue with representative payloads.

Topics to learn:

- Information architecture, accessible reading layouts, responsive design, and form feedback.
- REST resources, HTTP status codes, DTOs, pagination, and RFC 7807-style errors.

Common errors to investigate:

- Designing only the successful state.
- Exposing database entities directly as API responses.
- Inconsistent validation between frontend and backend.
- Making the reading passage difficult to use on narrow screens.
- Treating a client-side timer as authoritative.

Exit criteria:

- Every MVP use case maps to a screen flow and an API operation.
- The user approves the principal layouts, colors, and typography.

### Phase 3 — Project foundation and local development environment

Objectives:

- Establish the Spring Boot project, frontend project, PostgreSQL environment, migrations, configuration profiles, and automated checks.
- Build only infrastructure and one health-checking vertical slice; do not implement all entities at once.

Deliverables:

- Reproducible local setup instructions.
- Development and test configuration separated from secrets.
- Database migration runner and initial baseline migration.
- Global validation/error response, structured logging, and health endpoints.
- Initial unit/integration test harness and continuous-integration workflow.

Topics to learn:

- Dependency injection, controller/service/repository separation, configuration profiles, and connection pooling.
- Environment variables, secret handling, test databases, and migration lifecycles.

Common errors to investigate:

- Committing passwords or the Merriam-Webster key.
- Using one configuration for development, testing, and deployment.
- Letting ORM auto-update the schema in a way that bypasses migrations.
- Field injection, business logic in controllers, and inconsistent exceptions.
- Tests sharing mutable database state.

Exit criteria:

- A new developer can start the database and both applications from documented steps.
- Automated checks run successfully against a clean database.
- No secret exists in tracked files or browser-delivered configuration.

### Phase 4 — Authentication, authorization, users, and categories

Objectives:

- Implement account authentication, `LEARNER`/`ADMIN` authorization, active-user enforcement, administrator user management, and learner-owned categories.
- Establish the ownership pattern that vocabulary notes and attempts will later reuse.

Deliverables:

- Sign-in/sign-out and current-user flow.
- Password hashing and protected API routes.
- Admin user list, activation/deactivation, and safe role handling.
- Category create/read/update/delete with same-user ownership validation.

Topics to learn:

- Authentication versus authorization, password hashing, sessions/tokens, CSRF/CORS, and object-level authorization.

Common errors to investigate:

- Trusting a `userId` supplied by the frontend instead of the authenticated principal.
- Returning password hashes or internal security fields.
- Checking roles in the UI only.
- Allowing duplicate normalized email addresses or category names.
- Deactivating a user without defining what happens to active sessions.

Exit criteria:

- Learners cannot access administrator functions or another learner's resources.
- Authentication and permission failures have automated tests.

### Phase 5 — Administrator content authoring and publishing

Objectives:

- Implement manual authoring for passages, question groups, questions, answer keys, explanations, and reusable full tests.
- Preserve the approved `OPTION`, `BLANK`, and `MATCHING` structures.
- Introduce draft validation and publishing rules before learners can see content.

Deliverables:

- CRUD for passages and reading tests.
- Reusable test–passage assignment with deterministic order/part validation.
- Editors for all three question families.
- Preview that mirrors the learner rendering.
- Publish validator for missing answers, duplicate question numbers, invalid matching trees, and question-count mismatches.

Topics to learn:

- Aggregate validation, nested forms, transactional writes, optimistic locking, and draft/published workflows.

Common errors to investigate:

- Partially saving a question group and leaving orphan rows.
- N+1 database queries when loading a passage with all questions.
- Recursive matching records with cycles or invalid roots.
- Editing or deleting content already referenced by completed attempts.
- Publishing content with no correct option or multiple correct options when the format permits only one.

Exit criteria:

- An administrator can create, preview, validate, publish, and reuse a complete sample passage.
- A complete three-passage sample test can be assembled and cannot be published in an invalid state.

### Phase 6 — Individual-passage practice

Objectives:

- Deliver the first complete learner activity: select a passage, create a standalone attempt, answer, autosave, submit, and review.
- Support practice mode first; add timed standalone mode only as an explicitly labelled practice feature.

Deliverables:

- Passage catalogue and filters agreed during UX design.
- Attempt lifecycle and idempotent answer upsert.
- Rendering and answer capture for all three question families.
- Progress indicator, autosave feedback, submit confirmation, and reconnect/reload recovery.

Topics to learn:

- State machines, idempotency, debouncing, optimistic UI, transactional scoring, and concurrency control.

Common errors to investigate:

- Creating a new attempt on every refresh.
- Duplicate submitted-answer rows after repeated autosave requests.
- Accepting answers after submission.
- Losing unsaved input during navigation or network interruption.
- Revealing correct answers before the attempt is submitted.

Exit criteria:

- Refreshing or retrying cannot duplicate an attempt or answer.
- The server, not the browser, enforces lifecycle rules.
- A learner can complete and review one passage across all supported question families.

### Phase 7 — Full-test sessions and exam behavior

Objectives:

- Compose three passage attempts under one test session.
- Implement the official 60-minute full-test behavior, server-authoritative expiry, navigation, autosave, and automatic submission.
- Keep full-test practice mode distinct from exam simulation mode.

Deliverables:

- Full-test selection, instructions, start flow, section navigation, and completion summary.
- One session timer derived from server timestamps.
- Automatic submission and recovery after browser refresh or temporary disconnection.
- Session/attempt ownership consistency checks.

Topics to learn:

- Time zones and `Instant`, server clocks, scheduled versus request-time expiry, race conditions, and atomic finalization.

Common errors to investigate:

- Resetting the timer after refresh.
- Trusting a client countdown or local clock.
- Scoring while late autosaves are still in flight.
- Submitting the same session twice when timeout and manual submit happen together.
- Creating attempts whose user differs from the parent session owner.

Exit criteria:

- Closing and reopening the browser does not extend the deadline.
- Manual and automatic submission are idempotent and produce one final result.
- Dictionary lookup and note creation are blocked during exam mode until submission.

### Phase 8 — Merriam-Webster lookup and vocabulary notes

Objectives:

- Integrate Merriam-Webster Learner's Dictionary through the backend.
- Normalize entries, meanings, phonetics, audio metadata, and confirmed sense mappings into the shared repository.
- Let the learner select one definition and save a personal note with context and categories.

Deliverables:

- Backend dictionary client with finite connect/read timeouts and controlled retry behavior.
- Local-first lookup and database caching.
- Provider response mapper with fixture-based tests.
- Sense-selection interface, optional audio playback, custom meaning, context snapshot, categories, and review status.
- Attribution/branding display required by the provider terms.

Topics to learn:

- External HTTP clients, API keys, rate limits, caching, failure isolation, JSON mapping, and attribution/licensing.
- Normalization and deduplication without losing distinct senses.

Common errors to investigate:

- Putting the API key in frontend code or logs.
- Calling Merriam-Webster on every page render instead of checking the local repository first.
- Assuming every result contains audio, examples, or a single pronunciation.
- Blindly pairing every phonetic with every meaning.
- Holding a database transaction open while waiting for the remote API.
- Retrying `4xx` errors or retrying aggressively enough to consume the daily quota.
- Failing note creation because audio playback fails.

Exit criteria:

- Repeated lookup of a cached word does not consume another external request under normal operation.
- External timeout, not-found, quota, malformed payload, and audio failure each have a safe user-visible state.
- A learner can save the same entry in different contexts/senses without corrupting the shared entry.

### Phase 9 — Scoring, review, band conversion, and statistics

Objectives:

- Finalize exact-answer normalization rules for each question family.
- Score attempts and full sessions exactly once, convert full-test raw scores through a versioned Academic band table, and update per-question-type statistics.

Deliverables:

- Tested scoring rules for option, blank, and matching responses.
- Full-test raw score and Academic band result.
- Review view with submitted answer, correct answer, and explanation.
- Learner dashboard statistics based on confirmed completed attempts only.

Topics to learn:

- Pure scoring functions, normalization, rounding, versioned reference data, aggregation, and transaction idempotency.

Common errors to investigate:

- Case/whitespace/punctuation handling that changes correct answers incorrectly.
- Counting the same attempt twice after a retried submission.
- Recalculating historical results with a newly edited answer key or band table.
- Updating cumulative statistics with non-atomic read–modify–write logic.
- Using passage-level partial scores as if they were official full-test bands.

Exit criteria:

- A published scoring test suite covers boundary raw scores and each question family.
- Repeated finalization cannot change cumulative statistics or create duplicate results.

### Phase 10 — Quality, security, performance, and accessibility

Objectives:

- Test the integrated product beyond individual feature correctness.
- Measure important queries and user paths, then fix evidence-backed issues.

Deliverables:

- Unit, repository, API integration, and browser end-to-end test suites.
- Authorization matrix tests and dependency/security scan.
- PostgreSQL query review using realistic seed volume and `EXPLAIN (ANALYZE, BUFFERS)` where needed.
- Accessibility review for keyboard use, focus, contrast, labels, reading text, and timed interactions.
- Backup/restore rehearsal and failure-recovery notes.

Topics to learn:

- Test pyramid, threat modelling, OWASP risks, query plans, connection pools, structured logs, and accessibility standards.

Common errors to investigate:

- Optimizing before measuring.
- Missing indexes discovered only after loading realistic data.
- Logging tokens, passwords, API keys, or full sensitive payloads.
- Relying on hidden buttons rather than backend authorization.
- End-to-end tests that depend on the live Merriam-Webster service.
- Timer and autosave controls unusable with keyboard or screen reader.

Exit criteria:

- Critical user flows pass end-to-end with the external dictionary stubbed.
- No known critical authorization, data-loss, or scoring defects remain.
- The application has a tested backup and restore path.

### Phase 11 — Deployment, demonstration, and thesis evidence

Objectives:

- Deploy a reproducible release, prepare demonstration data, and organize technical evidence for the academic report.

Deliverables:

- Deployment architecture and environment configuration checklist.
- Production migrations, health checks, logs, database backup policy, and rollback procedure.
- Seeded administrator, learner, sample passage, full test, completed result, and vocabulary examples.
- API documentation, setup guide, test report, known limitations, and screenshots/diagrams for the thesis.
- Traceability table linking requirements → design → implementation → test evidence.

Topics to learn:

- Build artifacts, environment promotion, deployment checks, observability, rollback, and technical documentation.

Common errors to investigate:

- Running destructive seed data in the deployed database.
- Applying migrations without a backup or rollback plan.
- Depending on local files, local time zone, or undocumented environment variables.
- Demonstrating against live external data without a fallback.

Exit criteria:

- A clean environment can be deployed from documentation.
- The full learner and administrator scenarios can be demonstrated reliably.
- Limitations and future work are explicitly documented rather than hidden.

## 5. Cross-phase test strategy

Each implemented business rule needs tests at the cheapest effective layer:

- Unit tests: scoring, normalization, state transitions, band conversion, and Merriam-Webster mapping.
- Repository tests: constraints, ownership queries, ordering, recursive matching retrieval, and indexes where relevant.
- API integration tests: authentication, validation, transactions, permissions, idempotency, and failure responses.
- End-to-end tests: a small set of critical learner/admin journeys.
- Contract fixtures: saved Merriam-Webster responses, including missing fields and errors; automated tests must not require the live provider.

Minimum edge-case checklist for every feature:

- Empty and maximum-size values.
- Duplicate requests.
- Unauthorized and cross-user access.
- Refresh/retry/concurrent requests.
- Missing referenced data.
- External dependency timeout or malformed response.
- State change between read and update.

## 6. Documentation maintained throughout the project

- `PROJECT_CONTEXT.md`: current scope and agreed behavior.
- `DATA_MODEL_DECISIONS.md`: confirmed structural and persistence decisions.
- `OPEN_QUESTIONS.md`: unresolved decisions only.
- `DICTIONARY_API_ANALYSIS.md`: provider mapping, limits, and failure behavior.
- This roadmap: phase status, exit evidence, and changes in sequencing.
- Architecture Decision Records: create one for decisions with meaningful alternatives, such as authentication style, content versioning, caching policy, and frontend framework.
- Learning/defect journal: symptom, reproduction, root cause, fix, regression test, and lesson learned.

## 7. Immediate next actions

1. Complete Phase 0 by turning the confirmed flows into an MVP backlog and acceptance scenarios.
2. Start Phase 1 with the data dictionary and resolve submitted-answer encoding, lifecycle values, passage placement, and historical content versioning.
3. In parallel with the non-blocking parts of Phase 1, the user supplies the frontend palette, fonts, and reference interfaces for Phase 2.
4. Register the Merriam-Webster key and store it outside the repository, but postpone integration code until Phase 8.
5. Do not begin entity/controller generation before Phase 1 exit criteria are approved.
