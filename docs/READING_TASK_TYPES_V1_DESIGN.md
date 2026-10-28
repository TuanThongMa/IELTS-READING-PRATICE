# Reading Task Types — v1 Design

**Status:** Approved and implemented in the first pass on 2026-10-28. Manual end-to-end validation remains.  
**Scope:** Multiple Choice (Multiple Answers), Table Completion, Flow-chart Completion, Short Answer.  
**Deferred:** Diagram Label Completion (no approved image/asset source yet).

## 1. Existing foundation

- `question_groups.task_variant` already permits all four variants in the V3 migration.
- `question_groups.presentation_template` already exists as text and can hold versioned JSON for structured reading layouts.
- `blank_questions` and `blank_answer_variants` already store a text answer and accepted alternatives.
- `option_questions` and `question_answer_options` already support linking the same option record to multiple numbered questions.
- Attempt answers are stored one string per numbered question. Passage and full-test numbering count each answer box as one question.
- The current word-limit constraint and `AnswerTextPolicy` support up to two words plus one number. Short Answer and some completion tasks need an IELTS-style three-words-and/or-a-number option.

## 2. Recommended behavior by type

### Multiple Choice — Multiple Answers

The admin enters the shared instruction/prompt, a shared option bank (A, B, C…), and the numbered answer boxes. The admin selects the correct **set** of options; the number of correct options must equal the number of answer boxes. Each numbered box still stores one label. The learner sees the shared option bank and one choice control per question number. The learner may assign the selected labels in any order, but the same label cannot earn credit twice.

For example, if Questions 1 and 2 have the accepted set `{C, E}`, both `Q1=C, Q2=E` and `Q1=E, Q2=C` earn two marks. `Q1=C, Q2=C` earns at most one mark. This reflects the official IELTS sample answer key, which accepts the two letters in either order.

The existing choice tables can represent a shared bank by linking the same option records to every question in the group. Each question stores the same correct option set. No new answer endpoint is needed; the answer remains a single label per numbered box.

### Table Completion

The admin sets instructions and the word limit, then builds a table with a caption, column headings, static text cells, and answer cells. Each answer cell creates or links to one numbered blank. The learner sees the actual table and types into the blank in its cell.

### Flow-chart Completion

The admin sets instructions and the word limit, then builds an ordered sequence of steps. A step can contain static text and numbered blanks. The learner sees the steps connected vertically with arrows and types into each blank.

### Short Answer

The admin enters the instruction, prompt for each numbered question, correct answer, accepted alternatives, and word limit. The learner enters one text answer per numbered question. It uses the existing blank-answer storage and case/whitespace normalization.

For all three text-entry types, add `NO MORE THAN THREE WORDS AND/OR A NUMBER` to the supported word-limit policy. The validator counts up to three words plus one numeric token and rejects other token formats, matching the existing policy approach.

## 3. Structured template contract

Use `question_groups.presentation_template` as versioned JSON. Template references are zero-based indexes into the group's ordered question list, so passage-wide renumbering does not invalidate them. Do not store user-authored HTML.

```json
{
  "version": 1,
  "kind": "TABLE",
  "caption": "",
  "columns": ["Stage", "Description"],
  "rows": [
    [
      [{"text": "Initial stage"}],
      [{"text": "The process begins with "}, {"questionIndex": 0}]
    ]
  ]
}
```

`FLOW_CHART` uses `{ "version": 1, "kind": "FLOW_CHART", "caption": "", "steps": [[{"text":"..."}, {"questionIndex":0}]] }`. Each step's parts render in order; the UI adds arrows between steps. A template must reference every question exactly once, use valid question indexes, and have consistent table row widths. Plain text is rendered as text, never as HTML.

## 4. Admin authoring form

- Keep the existing question-group form and automatic passage numbering.
- Add one task-specific editor after selecting the variant:
  - Multiple Answers: instruction/prompt, shared options, answer-box count, and correct-option set.
  - Table: caption, column headings, editable rows/cells, and an “answer blank” control inside a cell.
  - Flow-chart: editable ordered steps and an “answer blank” control inside a step.
  - Short Answer: prompt and answer fields per numbered question.
- Show a live preview and the resulting question-number range before saving.
- Keep word-limit validation and accepted-answer alternatives visible beside each blank.

## 5. Learner and review UI

- Multiple Answers: instruction and shared choice bank once, followed by one clearly numbered answer selector per answer box. Prevent duplicate labels from being selected in the UI; the server remains authoritative for scoring.
- Table Completion: semantic table with inline answer inputs in the correct cells.
- Flow-chart Completion: vertical sequence of step cards with arrows and inline answer inputs.
- Short Answer: numbered prompt rows with text inputs.
- Review pages reuse each task layout and show the learner answer, accepted answer(s), status, and explanation without altering the original template.
- Full-test mode continues to use the same task components inside each passage pane; one blank/answer box remains one question in the 40-question total.
- Responsive behavior: preserve table readability with horizontal scrolling when needed; stack flow-chart and short-answer content vertically on smaller screens. Desktop remains the primary layout.

## 6. API and persistence

- Keep the existing admin question-group endpoint and attempt answer endpoints.
- Add `presentationTemplate` to admin and reading group DTOs for Table/Flow-chart groups.
- Store each table/flow-chart blank in `blank_questions`; store accepted alternatives in `blank_answer_variants`.
- Store short-answer prompts in `passage_questions.question_text`, with answers in the existing blank tables.
- Store one Multiple Answers option bank in `option_questions` and link it to each numbered answer box via `question_answer_options`; score the group as an unordered set and award no more than one mark per distinct selected label.
- Flyway V18 expands the allowed `word_limit_mode` before the new three-word limit is used. Structured templates use the existing `presentation_template` text field as versioned JSON; no new relational family was added.
- The reading-detail, full-test-detail, passage-result, and full-test-result contracts must all carry the presentation template so answering and review render consistently.

## 7. Implementation notes and remaining validation

- Admin authoring supports shared answer choices for Multiple Choice (Multiple Answers), table rows/cells, ordered flow-chart steps, and Short Answer prompts. Table/flow blanks reference the ordered group questions by zero-based index so passage renumbering does not break the layout.
- Every table/flow answer blank maps to one numbered question. Server validation requires each question index to appear exactly once, validates table row width, and rejects unsupported template versions or malformed parts. The client renders the template as React text and semantic HTML; it does not interpret authored HTML.
- Multiple Choice (Multiple Answers) stores one selected label per numbered box. Scoring compares the group set without depending on the order and credits each correct label at most once.
- The same learner component is used for passage practice, full-test practice, and both review screens. Build checks passed; manual admin/practice/full-test scenarios remain before marking this feature fully verified.
- Diagram Label Completion remains deferred until a durable image/asset source and authoring rule exist.

## Reference

- Official IELTS Academic Reading sample tasks: <https://ielts.org/cdn/Sample-tests/ielts-academic-reading-sample-tasks-2023.pdf>
- The official sample's Questions 1–2 instruct candidates to choose two letters and the key accepts the answers in either order.
