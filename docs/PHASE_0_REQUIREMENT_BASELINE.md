# Phase 0 — MVP Requirement Baseline

Status: Approved  
Date: 2026-10-16  
Scope: IELTS Academic Reading only

## Release goal

A learner can sign in, complete and review either one published reading passage or a three-passage reading test. An administrator can enter, validate, preview, publish, and assemble the sample content needed for that journey. The release is deployable with repeatable configuration and seed data.

## Glossary

| Term | Meaning |
|---|---|
| Passage | One reusable IELTS Reading content unit, fixed to Parts 1–3 for its published version. |
| Reading test | An ordered composition of three reusable passages. |
| Question group | Shared instructions and one family (`OPTION`, `BLANK`, `MATCHING`) for a set of questions in a passage. |
| Attempt | A learner's work on one passage; it is standalone or belongs to one test session. |
| Test session | The parent lifecycle for a full three-passage test only. |
| Submitted answer | The learner value saved once per attempt and passage question. |
| Finalization | Idempotent server operation that prevents further edits, scores the work, and records review data. |
| Published version | Content that can be selected by learners. Once used in a completed attempt, it is never edited in place. |

## Prioritized backlog

| ID | Priority | Requirement | Measurable outcome |
|---|---|---|---|
| MVP-01 | MVP | Authenticate learner and administrator; block inactive users. | Protected API rejects unauthenticated, inactive, and wrong-role calls. |
| MVP-02 | MVP | Admin manages learner accounts and learner-owned categories remain private. | Cross-user category access is denied. |
| MVP-03 | MVP | Admin creates draft passages, groups, questions, answers, and explanations for the three approved families. | A complete sample passage can be saved and reloaded. |
| MVP-04 | MVP | Admin validates, previews, publishes passages, and composes a three-passage test. | Invalid content cannot be published. |
| MVP-05 | MVP | Learner completes a standalone practice attempt with autosave/reload recovery. | A refresh preserves one attempt and its latest saved answers. |
| MVP-06 | MVP | Learner takes a full test in practice or 60-minute exam simulation. | Refresh never extends the exam deadline. |
| MVP-07 | MVP | Server finalizes, scores, reviews, and converts finalized full tests by Academic band table. | Repeated finalization produces one stable result. |
| MVP-08 | MVP | Release runs from documented environment configuration with demonstration data and health checks. | A clean environment reproduces the demonstrated flow. |
| SHOULD-01 | Should | Learner records personal vocabulary notes and categories. | Note ownership and category ownership are enforced. |
| SHOULD-02 | Should | Learner dashboard shows basic completed-attempt statistics. | Counts derive only from finalized attempts. |
| LATER-01 | Later | Merriam-Webster live lookup, cache refresh, and audio. | No external key or provider dependency blocks launch. |
| LATER-02 | Later | Bulk content import, spaced repetition, advanced analytics, and extra test types. | Each requires a separate approved scope. |

## Acceptance scenarios

### Identity and permissions

1. Given an active learner, when they sign in with correct credentials, then they receive only learner capabilities.
2. Given an inactive user, when they present otherwise valid credentials or a previously issued session, then protected operations are refused.
3. Given learner A and learner B, when A requests B's category, note, attempt, or answer, then the server returns a non-disclosing not-found or forbidden response and makes no change.
4. Given a learner, when they request an administrator endpoint, then the server refuses the request regardless of UI state.

### Content authoring

5. Given an administrator's complete draft passage, when each group, question number, answer key, and family-specific structure is valid, then publishing makes one immutable version visible to learners.
6. Given a draft with missing correct option, blank answer, matching root/item relationship, duplicated question number, or invalid part number, when publish is requested, then it is rejected with field-level validation detail.
7. Given published content used in a finalized attempt, when an administrator needs to correct it, then a new version is created; the completed attempt continues to reference the original version.

### Standalone practice

8. Given a learner selects a published passage in practice mode, when the start operation is retried or the browser refreshes, then exactly one active standalone attempt is returned.
9. Given an active attempt, when a valid answer is autosaved repeatedly, then one answer row is updated rather than duplicated; answers outside the attempt's passage are rejected.
10. Given a finalized attempt, when an answer-save request is received, then no answer is changed and the server reports the invalid lifecycle state.

### Full test and result

11. Given a learner starts an exam-mode full test, when 60 minutes elapse or the deadline is checked after a reload, then the server finalizes once and rejects late answer updates.
12. Given manual submission and automatic expiry race, when both finalization requests arrive, then exactly one final result and one statistics update exist.
13. Given a finalized passage attempt, when the learner opens review, then it sees its own submitted answers, correct answers, explanations, and score but no material from another learner's attempt.
14. Given a finalized full test, when its raw total is scored, then the band derives from the versioned Academic conversion table recorded for that session.

## Risks and controls

| Risk | Impact | Preventive control | Contingency |
|---|---|---|---|
| Scope exceeds three-week target | High | MVP boundary above; daily vertical-slice review | Move Should/Later items out without weakening core test flow. |
| Content copyright/licensing uncertainty | High | Use only owned, licensed, or explicitly permitted demonstration content | Do not publish uncertain content. |
| Timer trusts browser clock | High | Persist server timestamps; calculate expiry server-side | Request-time expiry finalization and audit logs. |
| Historical score changes after edits | High | Immutable published versions once attempted | Clone/correct as a new content version. |
| Credential or API-key disclosure | High | Environment variables only; redact logs; separate profiles | Rotate secret and invalidate affected credentials. |
| External dictionary outage/quota | Medium | Dictionary is post-launch and optional for core practice | Display safe unavailable state; permit manual notes later. |
| Data loss during autosave/retry | High | Idempotent upsert and server lifecycle checks | Preserve last acknowledged save and show save state. |
| Insufficient sample content for demo | Medium | Seed one complete test early in the project | Prioritize authoring sample before UI polish. |

## Phase 0 exit decision

Approved by the project owner on 2026-10-16. The MVP boundary, glossary, and fourteen scenarios are the baseline for traceability and testing. This approval permits Phase 1 PDM choices to become migration-ready; application code generation remains gated by Phase 1 closure.
