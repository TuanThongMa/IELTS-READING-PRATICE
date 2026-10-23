# Data Model Decision Log

Last updated: 2026-10-16

This document records confirmed domain decisions. Items marked `Open` are deliberately unresolved and must not be treated as requirements.

## DM-001 — Shared vocabulary repository

Status: Confirmed

`VOCABULARY_ENTRIES` is a global vocabulary repository shared by all users. A learner does not own the vocabulary entry itself; the learner owns a personal `VOCABULARY_NOTE` that refers to it.

A future physical model should support normalized lookup and prevent unintended duplicate vocabulary entries while preserving words with the same spelling but different senses and pronunciations.

## DM-002 — Vocabulary sense is a ternary combination

Status: Confirmed

The three-way link between `VOCABULARY_ENTRIES`, `VOCABULARY_PHONETICS`, and `VOCABULARY_MEANINGS` is intentional.

Each association row represents one complete vocabulary sense:

```text
vocabulary entry + part of speech + pronunciation + definition
```

Example:

| Entry | Part of speech | Pronunciation | Meaning |
|---|---|---|---|
| project | noun | /ˈprɒdʒekt/ | a planned piece of work |
| project | verb | /prəˈdʒekt/ | to estimate or show something in the future |

For clarity in the logical and physical model, the association should be named `VOCABULARY_SENSES` rather than the generic `Vocabulary_Association`.

Proposed logical attributes:

- `id_vocab_sense`
- `id_vocab_entry`
- `id_vocab_phonetic`
- `id_vocab_meaning`
- `part_of_speech`

The future physical model should prevent duplicate combinations of entry, phonetic, meaning, and part of speech.

Open: whether a sense may exist without a phonetic record depends on the selected dictionary API.

## DM-003 — Selected dictionary meaning in a personal note

Status: Confirmed

When a learner looks up a word, the application displays the available senses from the shared vocabulary repository. The learner selects one sense and saves it in the personal note.

The Spring Boot backend retrieves the selected meaning from the shared vocabulary entry/sense data and copies its definition text into `VOCABULARY_NOTES.selected_definition`.

`selected_definition` is therefore a text snapshot rather than a foreign key to `VOCABULARY_SENSES`. The existing note-to-entry relationship identifies the shared vocabulary entry. If the shared dictionary data changes later, the definition previously saved in the learner's note remains unchanged.

`custom_meaning` remains available for the learner's own interpretation or translation.

## DM-004 — Notes and categories

Status: Confirmed

- A user creates and owns categories.
- A user creates and owns vocabulary notes.
- One vocabulary note may belong to multiple categories.
- One category may contain multiple vocabulary notes.

The future relational model therefore requires a junction table, provisionally named `NOTE_CATEGORIES`.

Ownership and access rules must ensure a note can only be assigned to categories owned by the same user.

## DM-005 — Vocabulary review status

Status: Confirmed

The allowed review states are:

- `NEW`
- `LEARNING`
- `MASTERED`

No spaced-repetition schedule or review-history requirement has been confirmed.

## DM-006 — Dictionary source URL

Status: Confirmed

`VOCABULARY_ENTRIES.source_url` stores the URL associated with the dictionary API source. The actual API provider and response structure will be supplied later.

The URL is not the location in which the learner encountered the word. Passage context remains part of the personal note and its relationship to a passage.

## DM-007 — Related words

Status: Confirmed

`VOCABULARY_RELATED_WORDS` stores synonyms and antonyms associated with a vocabulary meaning.

Allowed relation types:

- `SYNONYM`
- `ANTONYM`

Related words may initially be stored as text because an API result does not necessarily correspond to an existing row in the global vocabulary repository.

The future physical model should prevent duplicate related-word records for the same meaning and relation type.

## DM-008 — User question-type statistics

Status: Confirmed conceptually

One user may have multiple `USER_QUESTION_TYPE_STATS` records, but at most one record for each question type. The physical model should enforce uniqueness for `(user_id, question_type)`.

The record maintains at least:

- `total_answered`
- `total_correct`
- `updated_at`

## DM-009 — PostgreSQL type-selection principles

Status: Preliminary

Exact types will be selected after all CDM sections have been reviewed. Current principles are:

- Passage bodies, question text, explanations, instructions, contextual sentences, and custom meanings require long text storage rather than a 255-character limit.
- IDs, timestamps, status fields, scores, and counters will be defined consistently across the model.
- Flexible question-answer structures may use normalized tables or targeted `JSONB` only after all question types are understood.
- Status and type values require database validation; the choice between PostgreSQL enums and checked character columns remains open.
- Foreign-key optionality must reflect real API and workflow behavior rather than the temporary PowerDesigner defaults.

## DM-010 — Full-test sessions and independent passage attempts

Status: Confirmed

`TEST_SESSIONS` is created only for a full-test activity. An individual passage activity does not create a test session.

`QUIZ_ATTEMPTS` may therefore exist independently and must belong directly to a user and a passage. Its reference to `TEST_SESSIONS` is optional:

```text
standalone passage: user -> quiz attempt -> passage
full test: user -> test session -> multiple quiz attempts -> passages
```

For an attempt inside a full test, the attempt still belongs directly to the same user as the parent session. The model and service layer must prevent an attempt from referencing a session owned by another user.

The full-test timer and full-test lifecycle belong to `TEST_SESSIONS`. Passage-level result information belongs to `QUIZ_ATTEMPTS`.

## DM-011 — Passages are reusable and have a part number

Status: Confirmed

- One reading test contains multiple passages.
- One passage may be reused in multiple reading tests.
- Every passage has a mandatory `part_number`.
- The `READING_TESTS`–`PASSAGES` relationship therefore requires a junction entity, provisionally named `TEST_PASSAGES`.

The current interpretation is that a passage retains the same part number whenever reused. Whether the junction also needs an explicit display position will be settled during PDM design.

## DM-012 — Stored scores and band conversion

Status: Confirmed

The database stores the number of correct answers. The Spring Boot backend converts that number into the appropriate IELTS band score.

`TEST_SESSIONS.total_score` should be renamed to a count-oriented name such as `total_correct`. `QUIZ_ATTEMPTS.score` should likewise be reviewed for a clearer passage-level name such as `correct_answers`.

The current system scope is IELTS Academic Reading only. The backend therefore applies the Academic Reading raw-score-to-band mapping. No test-type discriminator is required for the first release, and no requirement to persist the derived band score has been confirmed.

## DM-012A — Full-test mode and lifecycle

Status: Confirmed

`TEST_SESSIONS` includes `mode` so that a full test can run in practice or exam mode. It also includes `status` to represent the full-test lifecycle. The exact allowed status values will be finalized in the data dictionary.

## DM-013 — Vocabulary-note context snapshot

Status: Confirmed

`VOCABULARY_NOTES.context_sentence` directly stores the textual context in which the learner encountered the selected vocabulary sense. It is a snapshot belonging to that note and does not require a separate context entity.

Each note represents one saved vocabulary occurrence and one selected vocabulary sense. If the same user encounters the same vocabulary entry with another meaning or in another context, the system may create another note referring to the same global entry but to the appropriate sense and context sentence.

A note created from a passage may hold one optional passage reference. A note entered from outside the reading system may have no passage reference and may leave `context_sentence` empty.

Accordingly, the intended passage relationship is:

```text
PASSAGES 1 -> 0..n VOCABULARY_NOTES
VOCABULARY_NOTES -> 0..1 PASSAGE
```

The many-to-many passage-note relationship currently shown in the CDM should be revised unless a later requirement explicitly introduces multi-source notes.

## DM-014 — Approved three-family question model

Status: Confirmed and structurally frozen

The instructor-approved CDM intentionally groups IELTS Reading questions into three implementation families. The application and future PDM must preserve this structure rather than introducing additional question-type tables.

### Common hierarchy

```text
PASSAGES -> QUESTION_GROUPS -> PASSAGE_QUESTIONS
```

- One passage has multiple question groups.
- A question group holds shared `instructions` and `question_type`.
- Every passage question belongs to a question group.
- `PASSAGE_QUESTIONS` holds the common question number, question text, explanation, and creation time.

### Option questions

`OPTION_QUESTIONS` represents selectable answers. It is connected to `PASSAGE_QUESTIONS` through the approved `has_answer_option` association, whose `is_correct` attribute identifies correct options.

This family covers IELTS question forms implemented through selectable choices.

The field semantics are confirmed as follows:

- `answer` stores the option label, such as `A`, `B`, `C`, `TRUE`, `FALSE`, or `NOT GIVEN`.
- `text` stores the content displayed for the option.
- `has_answer_option.is_correct` identifies whether the option is a correct answer for the associated passage question.

### Blank questions

`BLANK_QUESTIONS` represents fill-in responses. It stores the blank text and `answer_blank` for marking.

This family covers IELTS question forms in which the learner enters a word or short textual answer.

### Matching questions

`MATCHING_QUESTIONS` represents matching tasks. Its recursive relationship is intentional and must not be replaced.

A root matching record identifies one matching frame. All item records belonging to that frame carry the root record's `id_matching` through the self-referencing foreign key. For example, if the root has `id_matching = 1`, its child matching items reference ID 1 so the application can render them in the same matching frame.

`answer_label` is retained. The self-reference only groups the matching items; it does not identify the correct match for each item. `answer_label` stores the correct label associated with an item, such as `A`, `B`, `C`, `i`, `ii`, or `iii`.

The intended nullability is:

- Root matching record: parent reference is null and `answer_label` may be null.
- Child matching item: parent reference identifies the root and `answer_label` is required once the content is ready for use.

### Submitted answers

`ANWERS_QUESTION` connects a learner's `QUIZ_ATTEMPT` to a `PASSAGE_QUESTION` and stores `submitted_answer`. The previously recorded naming correction to `USER_ANSWERS` is a naming preference only; it does not change the approved relationship structure.

### Question family values

`QUESTION_GROUPS.question_type` uses exactly these canonical values:

- `OPTION`
- `BLANK`
- `MATCHING`

### Change boundary

This question-and-answer CDM was approved by the project instructor. Subsequent analysis may define field meanings, constraints, PostgreSQL types, validation, and backend behavior, but must avoid structural changes unless the user explicitly reopens the CDM design.

## Naming corrections already identified

- Rename `ANWERS_QUESTION` to `USER_ANSWERS`.
- Rename `Vocabulary_Association` to `VOCABULARY_SENSES`.
- Use consistent singular or plural conventions when the PDM naming standard is finalized.

## DM-015 — Primary dictionary provider

Status: Confirmed for the first implementation

Merriam-Webster Learner's Dictionary API is the primary external vocabulary provider. The Spring Boot backend calls the provider; the frontend never calls it directly and never receives the API key.

The application uses a local-first lookup strategy: query the shared vocabulary repository first and call Merriam-Webster only when suitable cached data is absent. External audio and other optional fields must not block creation of a personal vocabulary note.

Provider responses must be mapped and tested with stored fixtures. Automated tests must not depend on the live service. Attribution/branding and usage limits must be handled according to the provider agreement accepted for the registered key.

## DM-016 — Submitted-answer representation

Status: Confirmed

`USER_ANSWERS.submitted_value` is a canonical text value. Option and matching answers store the selected label, which the server validates against the question in the attempt's content version. Blank answers retain both the learner's original input and a normalized value used for scoring. The client never supplies a correct answer or determines its validity.

## DM-017 — Published-content history

Status: Confirmed

A published passage or reading test referenced by a finalized learner attempt/session is immutable. A correction creates a new draft content version that points to its predecessor. Finalized work retains the exact primary-key version it used, so later corrections cannot silently change historical review or scores.

## DM-018 — Global vocabulary-entry key

Status: Confirmed for MVP

One shared vocabulary entry is uniquely identified by the pair `(language_code, normalized_headword)`. `normalized_headword` is produced by trim and lowercase normalization in the application. Provider-specific IDs remain optional source metadata and may become an additional uniqueness rule when the provider payload has been analysed.

## DM-019 — Academic band-conversion reference data

Status: Confirmed

Academic Reading conversion data is stored in a versioned reference table, and a finalized full-test session records the applied table version. The initial source is the official IELTS scoring guidance. Because that source describes its raw-score thresholds as averages that may vary by test version, the product labels its own complete seed ranges as practice guidance and records their source/version rather than claiming a universal official conversion table.

The approved `Academic Reading Practice Guidance v2` table is:

| Raw correct answers out of 40 | Result |
|---|---|
| 0–10 | `Below 4.0` |
| 11–12 | `4.0` |
| 13–15 | `4.5` |
| 16–17 | `5.0` |
| 18–22 | `5.5` |
| 23–25 | `6.0` |
| 26–29 | `6.5` |
| 30–31 | `7.0` |
| 32–34 | `7.5` |
| 35–36 | `8.0` |
| 37–38 | `8.5` |
| 39–40 | `9.0` |

The table's recorded sources are `https://www.ielts.org/take-a-test/your-results/ielts-scoring-in-detail` and `https://ielts.idp.com/thailand/results/scores/reading`, retrieved on 2026-10-16. It remains practice guidance because the providers note that raw-score thresholds can vary slightly by test version.

## DM-020 — Passage-wide question-number uniqueness

Status: Confirmed for MVP

Question number uniqueness within a passage is checked by the service and publish validator in the same transaction as the content change. The MVP does not duplicate `passage_id` on `PASSAGE_QUESTIONS` solely to add a database unique constraint, and does not introduce a PostgreSQL trigger. API and integration tests must cover concurrent and invalid writes.

## DM-021 — IELTS task variants remain inside three structural families

Status: Confirmed

The persisted structural families remain exactly `OPTION`, `BLANK`, and `MATCHING`. A `QUESTION_GROUPS.task_variant` identifies the compatible IELTS presentation—such as True/False/Not Given, Matching Features, or Table Completion—without introducing a table or statistics family for every public task label.

`QUESTION_GROUPS.answer_input_mode` distinguishes a normal per-question controlled choice, a shared choice pool, and text entry. `candidate_reuse` is explicit data, not a phrase the service attempts to infer from instructions. The approved launch taxonomy excludes Diagram Label Completion until immutable content-asset handling is separately designed.

## DM-022 — Shared matching frame and answer-key association

Status: Confirmed

The recursive `MATCHING_QUESTIONS` model is retained and clarified:

- one `FRAME` root belongs to a matching question group;
- its `CANDIDATE` children carry stable answer labels, display text, and display order;
- each numbered matching `PASSAGE_QUESTION` has exactly one private answer-key association to a candidate in that same frame; and
- the group's `candidate_reuse` rule controls whether a learner may use a candidate for more than one prompt.

The active learner payload receives only candidate label/text and reuse behaviour. Correct-candidate links are available only to the transactional scoring/finalized-review service.

## DM-023 — Review flags are independent learner state

Status: Confirmed

`ATTEMPT_QUESTION_STATES` persists `flagged_for_review` by attempt/question, including an unanswered question. It is intentionally separate from `USER_ANSWERS`, whose submitted value is required only after an answer exists. Clearing a flag may remove the state row; it never changes a saved answer or score.

## DM-024 — Blank-answer and word-limit MVP policy

Status: Confirmed

Each blank question has one canonical answer; scoring normalizes Unicode/case/whitespace and retains the learner's raw value. Multiple acceptable answer variants are outside the launch MVP. The author records any IELTS word-limit instruction in the shared group instructions; the first release does not derive or enforce a second hidden word-limit rule.

Update, 2026-10-23: the project owner approved replacing the one-answer policy with a primary answer plus optional, explicitly authored alternatives. Normalization is Unicode NFC, case-insensitive and whitespace-collapsed; the service rejects equivalent duplicates. Sentence Completion groups store a selected word-limit mode and derive the displayed instruction from that mode. The later learner-scoring implementation must enforce the chosen limit and compare against all accepted answers; authoring alone does not implement scoring. Existing OPTION data is unchanged. Admin evidence is ordinary explanation text for MVP; linked passage anchors remain future work and are distinct from learner highlights.

Word-limit authoring validation: ONE_WORD accepts at most one word and no digit-number; TWO_WORDS accepts at most two words and no digit-number; TWO_WORDS_AND_ONE_NUMBER accepts at most two words and at most one digit-number. A hyphenated alphabetic token counts as one word; digit-only integers count as numbers, while number words such as "three" count as words. Decimal numbers, dates, currency symbols and other unclassified formats are rejected as unsupported, not graded as incorrect. The same policy must be evaluated again before learner grading, especially for unsupported legitimate IELTS answers.

## DM-025 — Personal passage annotations

Status: Confirmed

The reference highlighting is learner-created, not automatic answer evidence. `ATTEMPT_ANNOTATIONS` is owned through a single attempt, stores an exact selected-text quote and UTF-16 start/end offsets against that attempt's immutable passage version, and has no scoring effect.

Annotations are allowed in practice and finalized review, unavailable during an active exam, and invisible to every other learner. Automatic answer-evidence highlighting is out of the launch MVP.

