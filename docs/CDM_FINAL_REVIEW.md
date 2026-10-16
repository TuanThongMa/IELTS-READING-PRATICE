# CDM Final Review

Review date: 2026-10-16

Review scope: the complete PowerDesigner CDM image, the previously supplied CDM XML, and confirmed business decisions.

## Overall result

The CDM covers the four required domain areas:

1. Users, categories, and learner statistics.
2. Reading tests, reusable passages, full-test sessions, and independent passage attempts.
3. Question groups, the approved three-family question model, submitted answers, and scoring.
4. Shared vocabulary entries, senses, personal notes, phonetics, meanings, synonyms, and antonyms.

The model is suitable as the conceptual foundation. The instructor-approved question structure is treated as frozen. The items below are alignment checks to resolve before final PostgreSQL PDM generation.

## Confirmed relationships

### Users

- One user has multiple full-test sessions.
- One user has multiple quiz attempts, including standalone passage attempts.
- One user creates multiple categories and vocabulary notes.
- One user has at most one statistics record per question type; the uniqueness rule will be enforced in the PDM.

### Tests, sessions, attempts, and passages

- A test session exists only for a full test.
- A quiz attempt may exist independently or belong to one full-test session.
- A quiz attempt belongs to one user and one passage.
- A reading test contains multiple passages.
- A passage may be reused in multiple reading tests, so the many-to-many relationship will produce a `TEST_PASSAGES` junction in the relational model.
- Every passage has a mandatory part number.

### Questions and answers

- A passage has question groups.
- A group has passage questions and exactly one of the canonical family values: `OPTION`, `BLANK`, or `MATCHING`.
- Option answers use `OPTION_QUESTIONS` plus `has_answer_option.is_correct`.
- Blank answers use `BLANK_QUESTIONS.answer_blank`.
- Matching tasks use the intentional recursive `MATCHING_QUESTIONS` relationship.
- A submitted answer connects one quiz attempt to one passage question.
- The question-table structure must not be redesigned during PDM work.

### Vocabulary

- Vocabulary entries are shared globally.
- The ternary vocabulary association intentionally binds an entry, pronunciation, meaning, and part of speech into one vocabulary sense.
- A user note refers to a shared entry and records a selected meaning plus an optional custom meaning.
- A note may belong to multiple user-owned categories.
- `context_sentence` remains directly on the note as a snapshot; no context child table is required.
- Related words belong to a meaning and are restricted to `SYNONYM` and `ANTONYM`.

## Alignment items resolved before PDM

### A-01 — Matching answer label is absent from the overview image

Resolution: completed. `MATCHING_QUESTIONS.answer_label` has been restored. It is retained because the recursive foreign key groups items but does not store each item's correct match.

The original XML also contains this attribute.

### A-02 — Passage-to-note cardinality differs from the confirmed note behavior

Resolution: no CDM change required. The user confirmed that the passage-note cardinality in the diagram is already correct; the earlier review misread the PowerDesigner notation.

Confirmed behavior: each vocabulary note is one saved occurrence with one `context_sentence` and at most one source passage. A passage may produce many notes; an externally entered note may have no passage.

```text
PASSAGE 1 -> 0..n VOCABULARY_NOTES
VOCABULARY_NOTE -> 0..1 PASSAGE
```

### A-03 — Full-test session mode is not represented directly

Resolution: completed. `mode` has been added to `TEST_SESSIONS`. Full-test timing and restrictions use the session mode, while `QUIZ_ATTEMPTS.mode` remains available for independent passage attempts.

### A-04 — Full-test lifecycle status is not represented directly

Resolution: completed. `status` has been added to `TEST_SESSIONS`. Exact allowed values will be selected in the data dictionary.

### A-05 — Selected definition representation remains open

Resolution: confirmed as a text snapshot. The backend retrieves the learner's selected meaning from the shared entry/sense data and copies the definition text into `VOCABULARY_NOTES.selected_definition`. No selected-sense foreign key is required for the current model.

### A-06 — Score field names do not express the confirmed meaning

Confirmed behavior: the database stores the number of correct answers and the backend converts it to an IELTS band.

- `TEST_SESSIONS.total_score` semantically means `total_correct`.
- `QUIZ_ATTEMPTS.score` semantically means `correct_answers`.

These may be handled as naming corrections during LDM/PDM generation.

### A-07 — Test type is required if both IELTS variants are supported

Resolution: the current system supports IELTS Academic Reading only. `READING_TESTS` does not require a test-type discriminator for the first release. The backend uses the Academic raw-score-to-band mapping.

## PDM constraints to add without changing the CDM structure

- Unique normalized user email.
- Unique category name per user.
- Unique `(user_id, question_type)` for user statistics.
- Unique reading-test/passage pair in `TEST_PASSAGES`.
- Valid passage part number range.
- Nonnegative counts for total questions, answered questions, and correct answers.
- `total_correct <= total_answered` in question-type statistics.
- Valid values for roles, review status, question type, attempt mode, attempt status, and related-word type.
- Ownership consistency between a full-test session, its attempts, and their user.
- A note may only be assigned to a category owned by the same user.
- Prevent duplicate vocabulary sense combinations and duplicate related words for one meaning/type.
- Validate question-number uniqueness within a passage through database design or backend validation.

## Naming corrections reserved for LDM/PDM

- `ANWERS_QUESTION` -> `USER_ANSWERS`.
- `Vocabulary_Association` -> `VOCABULARY_SENSES`.
- `total_score` -> `total_correct`.
- `score` -> `correct_answers`.
- Apply one consistent singular/plural naming convention to tables and keys.

## Review conclusion

No additional domain area is missing from the conceptual model. Alignment items A-01 through A-07 have been resolved or reserved as naming work for the LDM/PDM. The project can proceed to the data dictionary and PostgreSQL type-selection stage without beginning application code.

