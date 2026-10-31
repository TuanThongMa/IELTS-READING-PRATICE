# IELTS Academic Reading Task-Type Architecture Review

Status: Approved Phase 2 closure design  
Date: 2026-10-16  
Scope: Map official IELTS Academic Reading task types to the approved three-family question architecture without adding a fourth family.

## Evidence reviewed

Implementation update (2026-10-28): Sentence Completion stores explicitly authored answer alternatives and a selected word-limit mode. Learner save endpoints enforce the selected mode, grading rechecks it and compares against all accepted answers, and the learner UI gives inline feedback. Older one-answer or instruction-only statements below describe superseded launch decisions.

The official IELTS Academic format specifies three sections, 40 questions, and 60 minutes. It lists multiple choice; True/False/Not Given; Yes/No/Not Given; matching information, headings, features, and sentence endings; sentence completion; summary/note/table/flow-chart completion; diagram label completion; and short answer questions. It also states that multiple choice can require more than one answer, matching rules can permit reuse only when stated, and text-entry tasks enforce an instruction-level word limit.

Sources consulted 2026-10-16:

- https://ielts.org/take-a-test/test-types/ielts-academic-test/ielts-academic-format-reading
- https://www.ielts.org/take-a-test/preparation-resources/sample-test-questions/academic-test
- https://takeielts.britishcouncil.org/what-is-ielts/how-it-works/test-format/reading

## Conclusion

The approved structural families remain sufficient:

```text
OPTION     — select a controlled answer
MATCHING   — assign one shared candidate to a numbered prompt
BLANK      — enter a word, number, or short text into a labelled gap
```

The system must not create a table for every public IELTS label. Instead, retain the three persisted `question_type` values and add a constrained `task_variant` to the **question group**. The variant controls authoring labels, rendering, instruction validation, and later statistics labels; it never changes the family-level score storage.

## Official type-to-family mapping

| Official task type | Family | `task_variant` | Data/rendering rule | MVP decision |
|---|---|---|---|---|
| Multiple choice — one answer | `OPTION` | `MULTIPLE_CHOICE_SINGLE` | One controlled choice per numbered question. | Include. |
| Multiple choice — more than one answer | `OPTION` | `MULTIPLE_CHOICE_MULTIPLE` | A group-level shared option pool fills several numbered answer slots; each slot remains one scored `passage_question`. The group declares whether a label may be reused. | Include. |
| True / False / Not Given | `OPTION` | `TRUE_FALSE_NOT_GIVEN` | A fixed three-label choice set per prompt. | Include. |
| Yes / No / Not Given | `OPTION` | `YES_NO_NOT_GIVEN` | A fixed three-label choice set per prompt. | Include. |
| Matching information | `MATCHING` | `MATCHING_INFORMATION` | Prompt statements map to labelled passage sections; reuse is author-configured. | Include. |
| Matching headings | `MATCHING` | `MATCHING_HEADINGS` | Paragraph/section prompts map to a shared heading pool; reuse is disabled. | Include. |
| Matching features | `MATCHING` | `MATCHING_FEATURES` | Prompt statements map to people/things/features. The supplied screenshot is this form. | Include. |
| Matching sentence endings | `MATCHING` | `MATCHING_SENTENCE_ENDINGS` | Sentence beginnings map to a shared ending pool; reuse is disabled. | Include. |
| Sentence completion | `BLANK` | `SENTENCE_COMPLETION` | One typed response per question; the selected group word limit is enforced by the API before saving and checked again during scoring. | Include. |
| Summary / note completion from text | `BLANK` | `SUMMARY_COMPLETION` or `NOTE_COMPLETION` | A safe group presentation template renders labelled blanks; each remains a normal scored question. | Include. |
| Table / flow-chart completion | `BLANK` | `TABLE_COMPLETION` or `FLOW_CHART_COMPLETION` | The same blank answers appear inside a semantic table/ordered flow template. | Include after the template renderer is defined. |
| Summary/table/flow-chart from a word list | `BLANK` | one of the completion variants plus `CHOICE_BANK` input | A shared controlled word bank is shown while the answer remains a blank-family answer. Reuse rule is author-configured. | Include after shared choice-pool reuse is implemented. |
| Diagram label completion | `BLANK` | `DIAGRAM_LABEL_COMPLETION` | A labelled image plus textual answer list; image needs durable asset metadata and alt text. | Defer from launch unless the asset/template rule below is approved. |
| Short answer | `BLANK` | `SHORT_ANSWER` | A short typed word/number answer; any word limit is displayed in the shared instructions, not separately enforced in the launch MVP. | Include. |

## Matching frame — resolved interpretation of the supplied screenshot

The screenshot contains a single shared candidate pool (people) and four numbered prompts. It should be authored and rendered as one matching group, not as four unrelated multiple-choice questions.

```text
Question group
  question_type: MATCHING
  task_variant: MATCHING_FEATURES
  instructions: "Match each statement with the correct person."
  candidate_reuse: true | false   <- author records the actual instruction

Shared candidate pool
  A  Gordon Rugg
  B  Roger Bacon
  C  William Newbold
  ...

Numbered prompts / scored questions
  31  The number of times ...
  32  Unlike some other ...
  33  The person who wrote ...
  34  It is likely that ...

Answer key (not returned to an active attempt)
  31 -> [one candidate label]
  32 -> [one candidate label]
  33 -> [one candidate label]
  34 -> [one candidate label]
```

The UI may offer click-to-fill and optional drag-to-fill as in the reference. Drag is never the only method: every candidate is also selectable by keyboard with a labelled radio/select control. The server stores and scores the stable label (`A`, `B`, …), not a display name that an editor can later correct.

### Required PDM clarification for matching

The current recursive `matching_questions` concept is retained as the shared frame/candidate hierarchy, but it needs one explicit group association and one explicit answer-key association:

- root frame belongs to one `question_group`;
- child candidate holds `answer_label`, display text, and display order;
- each matching `passage_question` references exactly one correct candidate from the same frame; and
- the group stores `candidate_reuse` and its `task_variant`.

This is a clarification of the approved recursive matching model, not a replacement of the `MATCHING` family. The active-attempt DTO exposes candidate labels/text and reuse rule, but never the correct-candidate association.

## Minimal metadata additions before implementation

| Location | Addition | Why it is required |
|---|---|---|
| `question_groups` | `task_variant text NN` constrained to the family-compatible values above | Preserves the three-family model while allowing authoring, reader rendering, and useful later statistics. |
| `question_groups` | controlled interaction settings: candidate/choice reuse and, where applicable, presentation/input mode | The wording of an IELTS instruction is not reliable machine-readable validation. |
| `question_groups` | `presentation_template text` nullable | Renders a summary, notes, table, or flow-chart with explicit question placeholders instead of relying on fragile HTML copied into `question_text`. |
| `question_groups` | `answer_input_mode` (`TEXT_ENTRY` or `SHARED_CHOICE_POOL`) and shared instruction text | Text-entry tasks use one canonical normalized answer per blank; any word limit remains visible instructional text. Word-bank completion remains in the BLANK family. |
| Matching frame | root-group association, labelled candidates, and a per-question correct-candidate association | Supports the shared pool shown in the screenshot and avoids duplicating choices. |
| `content_assets` (only if diagram labels launch) | immutable object key/URL, MIME type, alt text, created timestamp; group references it | A diagram cannot be represented safely by an arbitrary browser URL. |
| `attempt_question_states` | flagged-for-review state per attempt/question | Confirmed learner feature, including unanswered questions. |
| `reading_highlights` | personal user highlight anchored to a published passage version | A highlight is learner-owned, never part of the answer key, and persists when the learner returns to that passage. |

## Personal highlight rule

The yellow highlights in the supplied reader references are **learner annotations**, not automatic evidence from the system. They therefore have these rules:

- available in practice and finalized review, unavailable during an active exam;
- owned only by the learner and scoped to one attempt, so a new attempt starts clean;
- no effect on correct/incorrect scoring and never visible to another learner;
- anchored against the exact immutable passage version plus an exact selected-text quote, so a render can detect a broken anchor rather than highlighting the wrong text; and
- visually distinct from any future teacher/evidence annotation.

Automatic answer-evidence highlighting is out of the launch MVP. This does not remove personal highlighting.

## Release boundary

Launch the broad Reading experience with all controlled-choice, matching, and typed-completion forms above. The approved group metadata and matching-frame clarification are now part of the PDM/API contract. Diagram label completion is the sole named official form deferred by default because it adds durable image/alt-text/template authoring; it can be promoted only after a small asset workflow is approved during implementation.

## Matching interaction implementation note — 2026-10-31

The learner UI uses one assignment operation for drag-and-drop, click-to-select, and the narrow-screen select control. Every path submits the same stable candidate label through the existing answer callback and autosave contract. The selected-answer state is derived from the attempt answers, so clearing or replacing an answer also returns the previous candidate to the bank. The interface disables already-used candidates when reuse is off; the backend remains authoritative for candidate labels and reuse validation.

**Điểm phỏng vấn:** Drag-and-drop is a progressive enhancement, not a separate business path. Reusing one assignment function avoids having mouse, keyboard, and mobile interactions produce different persisted values. Frontend availability state improves feedback, while backend validation remains necessary because a client can be bypassed or modified.
