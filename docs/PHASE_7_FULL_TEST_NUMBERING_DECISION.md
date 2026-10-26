# Phase 7 — Full-test question numbering

## Confirmed invariant

A published IELTS Academic Reading full test consists of exactly three distinct published passages and exactly 40 visible questions. In full-test mode, those questions must form one contiguous global sequence from **1** through **40**.

The number of questions in each passage is deliberately not hard-coded. Valid examples include:

| Passage 1 | Passage 2 | Passage 3 |
|---|---|---|
| 1–13 (13 questions) | 14–26 (13 questions) | 27–40 (14 questions) |
| 1–14 (14 questions) | 15–27 (13 questions) | 28–40 (13 questions) |
| 1–12 (12 questions) | 13–26 (14 questions) | 27–40 (14 questions) |

Any other distribution is valid only when each range is consecutive and their total is exactly 40.

## Numbering model

`passage_questions.question_number` remains a **local** position in a standalone passage: `1..N`. This preserves standalone practice and makes a passage reusable.

When the passage participates in a full test, the backend calculates its global number from the selected test-passage position and the number of questions in preceding passages:

```text
global question number = passage range start + local question number - 1
```

The administrator does not manually type a range. The authoring UI calculates and displays it after each selected passage, preventing a mismatch between a displayed range and actual question rows.

## Validation before full-test publication

The backend must reject publication unless all checks pass in one validation operation:

1. Exactly three distinct passages are selected, in display positions 1, 2 and 3.
2. Every selected passage is published.
3. Every selected passage has local question numbers exactly `1..N`, without duplicate or missing positions.
4. The derived global ranges begin at 1, continue without a gap or overlap, and end at 40.
5. The total number of actual `passage_questions` is 40.

The UI can show useful validation feedback, but the backend owns the rule. A client request must never be trusted to supply correct counts or ranges.

## Persistence direction for implementation

During Phase 7, `test_passages` should store the selected passage and its display order. The server derives the ranges from immutable local question order while the test is a draft. When a full test is published, the calculated `question_start_number` and `question_end_number` should be persisted with the composition so the published test retains its exact visible numbering. These values are server-calculated, not editable admin input.

The future Flyway migration will use `smallint` for the 1–40 range boundaries, `CHECK` constraints for valid bounds, a unique order per test, foreign keys to the selected passage, and an index on the non-leading `passage_id` foreign key for joins. Cross-row rules such as “the three ranges cover 1–40 without gaps” require transactional service validation because ordinary row-level `CHECK` constraints cannot validate other rows.

## Learner display

- Standalone passage: show its local questions, for example `Questions 1–13`.
- Full test: show the derived test-global range, for example `Questions 14–26` for its second passage.
- The bottom question navigator uses test-global labels `1..40` while keeping the answer linked internally to the original `passage_question_id`.

## Interview note

**Point for interview:** A database `CHECK` is excellent for one-row facts such as a range start being no greater than its end. It cannot, by itself, prove that three rows are consecutive, non-overlapping and sum to 40. Multi-row business invariants need transactional service validation, and must be guarded against concurrent edits when publication is implemented.
