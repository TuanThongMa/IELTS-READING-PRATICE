# Phase 1 — PostgreSQL Physical Model Draft

Status: Approved design baseline; vocabulary/highlight tables implemented in migration V11
Date: 2026-10-26
Source: approved CDM semantics and `DATA_MODEL_DECISIONS.md`

## Conventions and decisions

- Tables and columns use unquoted lowercase `snake_case`.
- Surrogate primary keys are `bigint generated always as identity`; junction tables use a composite primary key when the relationship itself is the identity.
- Textual content uses `text`; all stored instants use `timestamptz`; counters use nonnegative `integer`; a band uses `numeric(2,1)`.
- Controlled values use `text` plus named `check` constraints. This is easier to evolve safely than PostgreSQL enums during the short MVP cycle.
- Every foreign-key column that supports joins, ownership checks, or delete checks receives an index unless covered by a composite primary/unique index.
- `created_at` is immutable. `updated_at` is set by the application on every change; no database trigger is needed for the MVP.
- API DTOs never expose password hashes, internal audit data, or provider credentials.

### Lifecycle values

| Area | Allowed values |
|---|---|
| `users.role` | `LEARNER`, `ADMIN` |
| content `status` | `DRAFT`, `PUBLISHED`, `ARCHIVED` |
| `question_groups.question_type` | `OPTION`, `BLANK`, `MATCHING` |
| `question_groups.task_variant` | Family-compatible launch values: `MULTIPLE_CHOICE_SINGLE`, `MULTIPLE_CHOICE_MULTIPLE`, `TRUE_FALSE_NOT_GIVEN`, `YES_NO_NOT_GIVEN`, `MATCHING_INFORMATION`, `MATCHING_HEADINGS`, `MATCHING_FEATURES`, `MATCHING_SENTENCE_ENDINGS`, `SENTENCE_COMPLETION`, `SUMMARY_COMPLETION`, `NOTE_COMPLETION`, `TABLE_COMPLETION`, `FLOW_CHART_COMPLETION`, `SHORT_ANSWER` |
| `question_groups.answer_input_mode` | `PER_QUESTION_CHOICE`, `SHARED_CHOICE_POOL`, `TEXT_ENTRY` |
| `matching_questions.node_kind` | `FRAME`, `CANDIDATE` |
| `test_sessions.mode` / standalone `quiz_attempts.mode` | `PRACTICE`, `EXAM` |
| session/attempt `status` | `IN_PROGRESS`, `SUBMITTED`, `EXPIRED`, `ABANDONED` |
| `vocabulary_notes.review_status` | `NEW`, `LEARNING`, `MASTERED` |
| `vocabulary_related_words.relation_type` | `SYNONYM`, `ANTONYM` |

`EXPIRED` is only a server-created final state. A submitted or expired resource is immutable to answer writes. Standalone exam mode, if added, has an explicit `time_limit_seconds`; a full exam derives its deadline from the parent session's `expires_at`.

`task_variant` describes a recognised IELTS presentation within an approved structural family; it does not create another persisted `question_type`. `answer_input_mode` tells the active reader how to render a group without disclosing answer keys. `DIAGRAM_LABEL_COMPLETION` is deliberately excluded from the launch check because it needs a separately approved immutable asset workflow.

### Draft question numbering decision (2026-10-22)

Administrators no longer enter question numbers. The backend derives consecutive passage-local numbers from group display order and the submitted question order, and renumbers draft questions transactionally when groups are added or changed. Migration V4 normalizes existing draft numbers in their previous group/question order; published content is not renumbered. The author must check manually written question ranges in instructions after numbering changes. Full-test cross-passage display numbering is a later feature and is not yet implemented.

### Content-history rule

Each published row is a content version. A correction creates a new draft row with `replaces_*_id` pointing to its predecessor; it never changes a published row referenced by a finalized attempt/session. A new version receives a new primary key, so historical attempts retain exact question and answer-key data without a JSON snapshot.

### Submitted-answer encoding

`user_answers.submitted_value` is canonical text:

- `OPTION`: option label (`A`, `TRUE`, `NOT GIVEN`, etc.), validated against the current question's selectable option labels. In a `MULTIPLE_CHOICE_MULTIPLE` shared pool, each numbered answer slot remains one `passage_question` and stores one label; the service validates cross-slot reuse rules before scoring;
- `BLANK`: learner-entered text after Unicode/whitespace/case normalization defined by the scoring rule; the original display input is retained in `submitted_raw_value`;
- `MATCHING`: selected answer label, validated against the matching frame belonging to the question.

The scoring service resolves all labels through the attempt's immutable content version; it never trusts a client-supplied correct answer or foreign identifier.

## Data dictionary

Key: `PK` primary key; `FK` foreign key; `NN` not null; `UQ` unique.

### Identity and learner data

| Table | Columns | Constraints / notes |
|---|---|---|
| `users` | `user_id bigint PK`; `email text NN`; `email_normalized text NN`; `password_hash text NN`; `display_name text NN`; `role text NN`; `is_active boolean NN default true`; `created_at timestamptz NN`; `updated_at timestamptz NN` | `uq_users_email_normalized`; role check. Normalization is lowercase/trimmed in application before storage. |
| `categories` | `category_id bigint PK`; `user_id bigint NN FK users`; `name text NN`; `name_normalized text NN`; `created_at`; `updated_at` | UQ `(user_id, name_normalized)` and `(user_id, category_id)` for composite ownership FK; name length 1–80. |
| `vocabulary_notes` | `vocabulary_note_id bigint PK`; `user_id bigint NN FK users`; `vocabulary_entry_id bigint NN FK vocabulary_entries`; `passage_id bigint FK passages`; `selected_text text NN`; `selected_definition text NN`; `custom_meaning text`; `context_sentence text NULL`; `review_status text NN default 'NEW'`; `created_at`; `updated_at` | One saved occurrence per note. The selected meaning is a text snapshot, not an FK; original passage spelling is preserved in `selected_text`. Context is optional for a word entered outside a passage. UQ `(user_id, vocabulary_note_id)` supports ownership validation. |
| `note_categories` | `user_id bigint NN`; `vocabulary_note_id bigint NN`; `category_id bigint NN`; `created_at` | PK `(user_id, vocabulary_note_id, category_id)`; composite FKs to note/category owner pairs prevent cross-user assignment. Deleting a category removes only its junction rows. |
| `user_question_type_stats` | `user_question_type_stat_id bigint PK`; `user_id bigint NN FK users`; `question_type text NN`; `total_answered integer NN default 0`; `total_correct integer NN default 0`; `updated_at timestamptz NN` | UQ `(user_id, question_type)`; type check; `0 <= total_correct <= total_answered`. Derived data updated only during idempotent finalization. |

To support the composite foreign keys above, `vocabulary_notes` has UQ `(note_id, user_id)` and `categories` has UQ `(category_id, user_id)` in addition to their primary keys.

### Shared vocabulary repository

| Table | Columns | Constraints / notes |
|---|---|---|
| `vocabulary_entries` | `vocabulary_entry_id bigint PK`; `word text NN`; `normalized_word text NN`; `language_code text NN default 'en'`; `provider text NN`; `provider_entry_id text`; `source_url text NN`; `fetched_at timestamptz NN`; `created_at`; `updated_at` | UQ `(language_code, normalized_word)` provides one shared cached headword per normalized spelling. |
| `vocabulary_entry_search_terms` | `language_code text`; `normalized_term text`; `vocabulary_entry_id bigint NN FK vocabulary_entries`; `created_at` | Composite PK maps inflections/stems returned by the provider to the shared entry, so searches for a known form can reuse cached data. One term may map to multiple entries. |
| `vocabulary_phonetics` | `vocabulary_phonetic_id bigint PK`; `phonetic_text text nullable`; `audio_url text nullable`; `provider_audio_id text`; `created_at` | At least IPA text or an audio URL is required; a sense can also have no phonetic FK when the provider omits both. |
| `vocabulary_meanings` | `vocabulary_meaning_id bigint PK`; `definition_text text NN`; `example text`; `provider_definition_id text`; `created_at` | Definition belongs to a sense through `vocabulary_senses`; example is optional. |
| `vocabulary_senses` | `vocabulary_sense_id bigint PK`; `vocabulary_entry_id bigint NN FK vocabulary_entries`; `vocabulary_phonetic_id bigint FK vocabulary_phonetics`; `vocabulary_meaning_id bigint NN FK vocabulary_meanings`; `part_of_speech text NN`; `provider_entry_id text`; `display_order integer NN default 1`; `created_at` | Implements the approved ternary association. Cache writes lock the shared entry row and check the provider entry id before inserting senses, preventing duplicate provider data while allowing pronunciation to be absent. |
| `vocabulary_related_words` | `vocabulary_related_word_id bigint PK`; `vocabulary_meaning_id bigint NN FK vocabulary_meanings`; `relation_type text NN`; `related_word text NN`; `normalized_related_word text NN`; `created_at` | UQ `(vocabulary_meaning_id, relation_type, normalized_related_word)`; relation-type check. |

### Authoring content and approved question model

| Table | Columns | Constraints / notes |
|---|---|---|
| `passages` | `passage_id bigint PK`; `title text NN`; `body text NN`; `part_number smallint NN`; `status text NN default 'DRAFT'`; `version_number integer NN default 1`; `replaces_passage_id bigint FK passages`; `created_by_user_id bigint NN FK users`; `published_at timestamptz`; `created_at`; `updated_at` | Part check `1..3`; content-status check; version positive. Published requires `published_at`; drafts must not have it. |
| `question_groups` | `question_group_id bigint PK`; `passage_id bigint NN FK passages`; `display_order integer NN`; `question_type text NN`; `task_variant text NN`; `answer_input_mode text NN`; `candidate_reuse boolean NN default false`; `instructions text NN`; `presentation_template text`; `word_limit_mode text`; `created_at`; `updated_at` | UQ `(passage_id, display_order)`; positive order; canonical type, task-variant, and input-mode checks. Sentence Completion stores its selected word-limit mode. Service validates family-compatible settings. `presentation_template` is a constrained template, not arbitrary client HTML. |
| `passage_questions` | `passage_question_id bigint PK`; `question_group_id bigint NN FK question_groups`; `question_number integer NN`; `question_text text NN`; `explanation text`; `created_at`; `updated_at` | UQ `(question_group_id, question_number)`. Service validates global uniqueness of a visible question number inside its passage in the same write transaction. |
| `option_questions` | `option_question_id bigint PK`; `answer_label text NN`; `option_text text NN`; `created_at` | A controlled choice is attached through the approved association table; label/text are not globally unique. The same choice rows may be reused for a group-level shared pool. |
| `question_answer_options` | `passage_question_id bigint NN FK passage_questions`; `option_question_id bigint NN FK option_questions`; `is_correct boolean NN default false`; `display_order integer NN` | PK `(passage_question_id, option_question_id)`; UQ `(passage_question_id, display_order)`. It supports `OPTION` and a `BLANK` choice-bank variant. Service requires family-compatible correct rows; a shared pool has the same ordered candidates across its answer slots and enforces its reuse rule. |
| `blank_questions` | `blank_question_id bigint PK`; `passage_question_id bigint NN FK passage_questions`; `blank_text text NN`; `answer_blank text NN`; `created_at`; `updated_at` | UQ `(passage_question_id)`; stores the primary answer and text after the blank. For Sentence Completion, `passage_questions.question_text` is the text before the blank. |
| `blank_answer_variants` | `blank_answer_variant_id bigint PK`; `blank_question_id bigint NN FK blank_questions`; `answer_text text NN`; `answer_normalized text NN` | UQ `(blank_question_id, answer_normalized)`; the service also rejects alternatives equivalent to the primary answer. |
| `matching_questions` | `matching_question_id bigint PK`; `question_group_id bigint NN FK question_groups`; `parent_matching_question_id bigint`; `node_kind text NN`; `candidate_text text`; `answer_label text`; `display_order integer NN` | Implemented by V6 using the approved recursive model. Admin creates one `FRAME` per group and `CANDIDATE` children with stable labels. The composite parent FK ensures both nodes share a group; the authoring service creates only one level. |
| `matching_question_answers` | `question_group_id bigint NN`; `passage_question_id bigint PK`; `matching_question_id bigint NN` | Composite FKs require the prompt and candidate to belong to the same group. The authoring service only selects `CANDIDATE` nodes. Learner-facing DTOs and publication validation remain future work and must not expose the answer key during an active attempt. |
| `reading_tests` | `reading_test_id bigint PK`; `title text NN`; `description text`; `status text NN default 'DRAFT'`; `version_number integer NN default 1`; `replaces_reading_test_id bigint FK reading_tests`; `created_by_user_id bigint NN FK users`; `published_at timestamptz`; `created_at`; `updated_at` | Same content lifecycle rule as passages. Publish validation requires exactly three published passage versions: one each with intrinsic part number 1, 2, and 3, ordered 1–3, with exactly 40 answerable passage questions in total. |
| `test_passages` | `reading_test_id bigint NN FK reading_tests`; `passage_id bigint NN FK passages`; `display_order smallint NN`; `created_at` | PK `(reading_test_id, passage_id)`; UQ `(reading_test_id, display_order)`; order must be 1..3. Passage's intrinsic part number is retained; display order gives deterministic composition. |

### Attempts, answers, and score reference data

Implementation note (V7): standalone practice currently creates `quiz_attempts` and `user_answers` only. An `active_marker` is true for an in-progress attempt and null for finalized attempts; unique `(user_id, passage_id, active_marker)` enforces one active attempt while permitting future historical attempts. A short user-row lock serializes start/resume. Full-test sessions, finalization, scores, annotations and flags remain planned and are not implemented by V7.

| Table | Columns | Constraints / notes |
|---|---|---|
| `academic_band_tables` | `academic_band_table_id bigint PK`; `version text NN`; `source_reference text NN`; `effective_from date NN`; `retired_at date`; `created_at` | UQ `version`; range rows below must cover the approved score domain before the table is activated. |
| `academic_band_ranges` | `academic_band_range_id bigint PK`; `academic_band_table_id bigint NN FK academic_band_tables`; `min_raw_score smallint NN`; `max_raw_score smallint NN`; `band_score numeric(2,1)`; `result_label text NN` | UQ `(academic_band_table_id, min_raw_score)`; `min <= max`; a numeric band is nonnegative when present; no overlap/gap is service-validated during table activation. `band_score` is null only for the explicit `Below 4.0` range. |
| `test_sessions` | `test_session_id bigint PK`; `user_id bigint NN FK users`; `reading_test_id bigint NN FK reading_tests`; `mode text NN`; `status text NN default 'IN_PROGRESS'`; `started_at timestamptz NN`; `expires_at timestamptz`; `submitted_at timestamptz`; `total_answered integer NN default 0`; `total_correct integer NN default 0`; `band_score numeric(2,1)`; `band_result_label text`; `academic_band_table_id bigint FK academic_band_tables`; `created_at`; `updated_at` | Exam mode requires expiry at `started_at + 60 minutes`; finalized status requires submitted timestamp. Below-4 results retain a null numeric score and the `Below 4.0` label. UQ `(test_session_id,user_id)` enables attempt ownership FK. |
| `quiz_attempts` | `quiz_attempt_id bigint PK`; `user_id bigint NN`; `test_session_id bigint`; `passage_id bigint NN FK passages`; `mode text`; `status text NN default 'IN_PROGRESS'`; `started_at timestamptz NN`; `time_limit_seconds integer`; `submitted_at timestamptz`; `total_answered integer NN default 0`; `correct_answers integer NN default 0`; `created_at`; `updated_at` | Composite FK `(test_session_id,user_id)` to `test_sessions`; all counts nonnegative and correct <= answered. A standalone attempt requires `mode`; a session attempt has `mode` and `time_limit_seconds` null and derives them from the parent session. `status`, start, and submission timestamps remain per attempt for passage-level recovery/result data. UQ `(test_session_id, passage_id)` where session is not null; partial UQ `(user_id, passage_id)` for active standalone attempts prevents refresh duplicates. |
| `user_answers` | `user_answer_id bigint PK`; `quiz_attempt_id bigint NN FK quiz_attempts`; `passage_question_id bigint NN FK passage_questions`; `submitted_value text NN`; `submitted_raw_value text`; `created_at`; `updated_at` | UQ `(quiz_attempt_id, passage_question_id)` provides idempotent upsert. Service verifies the question belongs to the attempt passage and attempt is mutable. |
| `attempt_question_states` | `quiz_attempt_id bigint NN FK quiz_attempts`; `passage_question_id bigint NN FK passage_questions`; `flagged_for_review boolean NN default false`; `updated_at timestamptz NN` | PK `(quiz_attempt_id, passage_question_id)`. It persists a review flag even when the learner has not submitted an answer. Service validates question/attempt passage ownership; rows may be removed when the flag is cleared. |
| `reading_highlights` | `reading_highlight_id bigint PK`; `user_id bigint NN FK users`; `passage_id bigint NN FK passages`; `start_offset integer NN`; `end_offset integer NN`; `selected_text text NN`; `prefix_context text NN`; `suffix_context text NN`; `created_at timestamptz NN` | Persistent user-owned highlight for one immutable passage version, reused when the learner returns or starts another practice attempt. Offsets use JavaScript/Java UTF-16 indexing; the service verifies the exact selected text against the passage. Overlaps are rejected. Highlights never affect scoring and cannot be changed during an active full test. |

## Constraint and index matrix

| Object | Constraint/index | Reason |
|---|---|---|
| `users` | UQ `email_normalized` | prevents duplicate identities regardless of case/space normalization. |
| ownership tables | indexes on `categories.user_id`, `vocabulary_notes.user_id`, `note_categories.category_id`, `user_question_type_stats.user_id` | supports private-resource queries and delete checks. |
| content hierarchy | indexes on all child FKs; `question_groups(passage_id, display_order)`; `passage_questions(question_group_id, question_number)` | efficient passage render in deterministic order. |
| answer option junction | index `question_answer_options(option_question_id)` | supports FK integrity and content editor retrieval. |
| matching hierarchy | indexes `(question_group_id, parent_matching_question_id)` and `(question_group_id, matching_question_id)` on answers; UQ `(question_group_id, answer_label)` | Loads the shared candidate hierarchy and enforces unique candidate labels within each group. |
| tests | indexes `test_passages.passage_id`, `test_sessions(user_id, status, started_at desc)` | learner dashboard and test composition. |
| active standalone attempts | partial UQ index on `(user_id, passage_id)` where `test_session_id is null and status = 'IN_PROGRESS'` | prevents duplicate attempts after refresh/retry without blocking historical attempts. |
| session attempts/answers | indexes `quiz_attempts(user_id, status, started_at desc)`, `user_answers(passage_question_id)`, `attempt_question_states(passage_question_id)`, `reading_highlights(user_id, passage_id, start_offset)` | owner retrieval, finalization joins, navigator state, persistent highlights, and FK checks. |
| vocabulary lookup | UQ `(language_code, normalized_headword)`; index `vocabulary_senses(vocabulary_entry_id, display_order)` | local-first term lookup and ordered senses. |

The normal learner-reader query starts from a known passage/test and therefore uses hierarchy/foreign-key indexes. Dashboard queries filter by equality (`user_id`, `status`) before timestamp ranges, matching the composite-index order. Add no speculative indexes until query plans are measured against seed volume.

## Migration order and recovery

1. Establish migration metadata and a baseline with extensions only if required by an approved feature.
2. Create independent reference/identity tables: `users`, band tables, vocabulary base tables.
3. Create content roots: `passages`, `reading_tests`, then their child/junction question tables.
4. Create learner-owned tables and composite ownership keys.
5. Create `test_sessions`, `quiz_attempts`, `user_answers`, attempt question states, and annotations.
6. Add non-PK indexes after tables/constraints, then transactional seed data: roles, two demo users, one complete published test, and one approved band table.
7. Verify a clean database migration, rollback on a disposable database, and backup restoration before any deployed environment migration.

Migrations are forward-only once shared. A faulty deployed migration is corrected by a new migration; it is never edited in place. Data-destructive changes require a tested backup, an explicit recovery migration or restoration procedure, and a staging rehearsal. DDL avoids `add constraint if not exists`, which PostgreSQL does not support.

## Approved decisions and seed-data caveat

The project owner approved the following on 2026-10-16:

1. Canonical submitted values: labels for `OPTION`/`MATCHING`; normalized plus raw text for `BLANK`.
2. Clone-on-correction immutable content versions, rather than mutating published material or storing a JSON snapshot.
3. Global vocabulary uniqueness by `(language_code, normalized_headword)` for the MVP.
4. A versioned Academic conversion table sourced from official IELTS guidance.
5. Passage-wide question-number uniqueness checked transactionally in the service/publish validator, rather than by a PostgreSQL trigger in the MVP.
6. A constrained group `task_variant` taxonomy maps common IELTS Reading labels onto the three approved structural families; diagram-label content is deferred until an immutable asset workflow is approved.
7. Matching uses one recursive candidate frame per group, with a private per-question answer-key association and an explicit candidate-reuse rule.
8. `Flagged for review` is persisted independently of answers. Personal highlights are private to a user and passage version, survive leaving/reopening the passage, and are blocked during an active exam.

The seed table will cite the [IELTS scoring guidance](https://www.ielts.org/take-a-test/your-results/ielts-scoring-in-detail) and the [IDP Academic Reading conversion table](https://ielts.idp.com/thailand/results/scores/reading), retrieved on 2026-10-16. The project owner approved `Academic Reading Practice Guidance v2`: `0–10 -> Below 4.0`, `11–12 -> 4.0`, `13–15 -> 4.5`, `16–17 -> 5.0`, `18–22 -> 5.5`, `23–25 -> 6.0`, `26–29 -> 6.5`, `30–31 -> 7.0`, `32–34 -> 7.5`, `35–36 -> 8.0`, `37–38 -> 8.5`, and `39–40 -> 9.0`. The sources describe raw-score thresholds as averages that can vary slightly by test version. Consequently, every product conversion table is labelled as a versioned practice guidance table, not as an immutable official marking rule.
