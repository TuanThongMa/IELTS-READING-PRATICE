# Matching Answer Interaction — Proposed UI

**Status:** Approved by the owner on 2026-10-31; shared learner controls are implemented and the frontend build passes. Manual interaction validation remains.
**Scope:** Matching Information, Matching Headings, Matching Features, and Matching Sentence Endings in passage and full-test practice.

## Goal

Use the supplied reference's scan pattern: prompt on the left, numbered answer slot on the right, and candidate chips below. Support mouse drag, click, keyboard, and touch-sized layouts. Keep the existing candidate labels, `candidateReuse` rule, answer autosave endpoint, and review contracts.

The supplied example's content is Matching Sentence Endings. Its interaction layout also fits Matching Headings, Information, and Features, while each type keeps its own prompt and candidate content.

## Visual direction

- Use the existing global CSS variables from `frontend/src/styles/global.css`: `--bg-page`, `--surface`, `--border`, `--text`, `--text-secondary`, `--primary`, `--primary-hover`, `--primary-soft`, `--primary-soft-text`, `--success`, `--success-bg`, `--danger`, and `--danger-bg`. Do not add duplicate palette values or component-local theme colors.
- Use `var(--font-ui)` (Be Vietnam Pro) for controls and labels; use `var(--font-reading)` (Source Serif 4) only for passage text and existing reading-style content.
- Never rely on color alone to describe assignment or save state. During an active attempt, use the primary selection treatment for assigned answers so it does not imply correctness; reserve semantic green/red feedback for submitted results.
- Keep the question pane visually quiet. The choice token is the memorable affordance; avoid animation except a brief drop/assignment response.

## Desktop layout

Keep the current reader split and question pane width. Inside a Matching group, show the shared instruction followed by rows with the prompt on the left and its numbered answer slot on the right. Put the wrapping candidate bank below the rows, matching the supplied example. Long prompts and answers may wrap without obscuring the question number or slot.

```text
┌ Matching Sentence Endings ──────────────────────────────────────────┐
│ Complete each sentence with the correct ending.                     │
│                                                                     │
│ At times when they were relaxed, the firefighters usually ...       │
│                                                   31 [Answer slot]   │
│ The researchers noted that when firefighters were stressed ...     │
│                                                   32 [Answer slot]   │
│                                                                     │
│ [Ending A] [Ending B] [Ending C] [Ending D] [Ending E] ...          │
└─────────────────────────────────────────────────────────────────────┘
```

Use a compact number marker and a clearly bordered answer slot. An assigned slot displays both the candidate label and text; long values may wrap, with the full value available to assistive technology. Render candidates as outlined chips, not as a table. Implement the colors through the existing CSS variables above.

## Interaction and accessibility

- Drag a candidate chip to a numbered slot; valid targets receive a visible primary outline. Dropping assigns the candidate and calls the existing answer-save callback.
- Clicking a candidate marks it as selected. Clicking a slot assigns it; pressing Enter or Space on a focused candidate selects it, then Enter or Space on a focused slot assigns it.
- A selected candidate is announced in a short instruction/live region. Focus indicators remain visible. Use real buttons and labelled answer slots; dragging is an enhancement, never the only path.
- An assigned slot has a clear button. Dropping a free candidate on an occupied slot replaces the old answer and returns the old candidate to the bank.
- When `candidateReuse` is false, assigned candidates are visibly marked and disabled in the bank until cleared. When true, candidates stay available and can fill more than one slot.
- Keep the existing explicit “Bỏ chọn” behavior and autosave timing. Show the current save/error indicator rather than adding a second status system.
- In review, do not show drag affordances. Continue showing the learner's answer, expected answer, status, and explanation in the current review layout.

## Responsive behavior

At narrow widths, stack each prompt above its numbered slot and use the existing labelled select control for its answer. Keep the desktop candidate-bank drag interaction out of the touch-sized critical path. Preserve passage/full-test bottom-bar navigation and its active-question emphasis.

## Data and API

No database or API changes are proposed. Use `candidateReuse`, each candidate's stable answer label, each question ID, the existing `onAnswer(questionId, value)` callback, and the existing answer autosave endpoint. The server continues to enforce allowed labels and reuse rules.

## Acceptance checks

- Drag, click-to-select, and keyboard assignment all write the same stable label and autosave.
- Duplicate assignment is prevented when reuse is disabled; reuse is allowed when enabled.
- Replacing and clearing answers restores the correct bank state.
- Assignment works in standalone and full-test practice without shifting the reader layout or moving the page when selecting a question.
- Passage result and full-test review remain unchanged and show learner/correct answers clearly.
- Focus, selected, assigned, disabled, saving, and error states remain understandable without color alone.
