# Phase 2 — UX Structure and API Contract

Status: Approved  
Date: 2026-10-16  
Scope: MVP learner and administrator flows; IELTS Academic Reading only

## 1. Design direction — The Reading Desk

The product's primary job is helping a learner read a demanding text and make reliable answering decisions under practice or exam conditions. The visual language therefore takes cues from a calm annotated reading desk: generous reading measure, a quiet side margin for progress and feedback, and a disciplined question panel. It must not look like a generic analytics dashboard or a gamified language app.

### Approved reference-frame decision

The project owner supplied reference captures on 2026-10-16 and approved their **interaction and layout frame** as the implementation reference for the learner area:

- reading selection: a left-side practice filter with a content-card catalogue and visible completion progress;
- active reader: compact top status bar, optional left tool rail, resizable passage/question panes, and answer controls alongside the passage;
- finalized review: answer status and explanation can appear in the question pane while the passage remains visible;
- dashboard: a simple tabbed record by completed attempt, question family, and passage.

Implementation will retain this frame where it improves familiarity, while applying this project's Reading Desk tokens, typography, Vietnamese copy, responsive/accessibility behaviour, and confirmed exam/privacy rules. It does not reuse the reference system's name, logo, mascot, cover artwork, user data, or other branded assets. When the reference frame conflicts with a confirmed business rule, the project rule wins—for example, no correct-answer colouring or explanation before finalization, and no dictionary/note tools in an active exam session.

### Approved visual tokens — light mode

The project owner approved this palette and typography on 2026-10-22. It supersedes the earlier token proposal. Only light mode is in scope; do not implement a dark theme or theme switcher.

| CSS token | Value | Use |
|---|---|---|
| `--bg-page` | `#F8FAFC` | Page background |
| `--surface` | `#FFFFFF` | Content surface |
| `--border` | `#E2E8F0` | Subtle dividers and borders |
| `--text` | `#1E293B` | Primary text |
| `--text-secondary` | `#475569` | Secondary text |
| `--primary` | `#2563EB` | Primary actions |
| `--primary-hover` | `#1D4ED8` | Primary action hover |
| `--primary-soft` | `#DBEAFE` | Soft primary background |
| `--primary-soft-text` | `#1D4ED8` | Text on soft primary background |
| `--on-primary` | `#FFFFFF` | Text on primary action |
| `--success` / `--success-bg` | `#15803D` / `#DCFCE7` | Success and correct answers |
| `--danger` / `--danger-bg` | `#B91C1C` / `#FEE2E2` | Errors and incorrect answers |
| `--warning` / `--warning-bg` | `#B45309` / `#FEF3C7` | Warnings |
| `--highlight` | `#FEF9C3` | Learner-created highlights |
| `--selection` | `#BFDBFE` | Active text selection |
| `--radius` | `8px` | Default corner radius; adapt where useful |

Use `Be Vietnam Pro`, then `Noto Sans`, `system-ui`, `sans-serif` for interface text, questions and controls. Use `Source Serif 4`, then `Georgia`, `serif` for passage text. The earlier 19px desktop size, 1.75 line-height and 72-character measure are starting points to validate when the reader is designed, not fixed requirements. Use sentence case and clear Vietnamese copy.

The subtle border token alone is not sufficient for input boundaries or focus indicators: select and test stronger component-specific borders and visible keyboard focus states during design. Do not rely on colour alone to communicate correct, incorrect, or other statuses. Design each screen with the owner before coding its frontend, including layout, responsive behaviour, loading, empty and error states. Apply the project's `frontend-design` skill when building the frontend. Existing foundation CSS still contains the previous tokens and must be updated when the associated interface design is approved.

### Layout principles

- Desktop reader: 12-column grid; passage occupies 7 columns and question work occupies 5. The question panel is independently scrollable only after its heading remains visible.
- Passage text is left aligned; question forms are left aligned and numbered because their order is meaningful.
- Use the primary blue for primary actions; reserve danger and warning colours for meaningful states. Practice mode intentionally has no countdown visual.
- Cards are used only for a contained decision (start mode, publish confirmation, or result summary), not as the default container for every piece of content.
- Motion is limited to a save-state change, drawer open/close, and submit confirmation; `prefers-reduced-motion` removes nonessential transitions.

### Design self-review

The reading margin carries progress, the passage uses a literary face, and the question panel is a work surface. Use colour and decorative elements deliberately so they do not distract from close reading.

## 2. Sitemap and role navigation

```text
Public
├── Sign in
└── Access unavailable

Learner
├── Home
│   ├── Continue attempt
│   ├── Choose a passage
│   └── Choose a full test
├── Passage practice reader
├── Full-test reader
├── Results and review
├── Vocabulary notes                    [Should / later integration]
└── Account

Administrator — proposed for joint review
├── Content overview
│   ├── Passages
│   │   ├── Passage editor
│   │   ├── Question-group editor
│   │   └── Preview / publish validation
│   └── Reading tests
│       └── Test composer / preview
├── Users
└── Account
```

The learner's primary navigation exposes Home, Practice, Full tests, and Results. Administrator navigation is a deliberately small **proposal**—Content and Users—and it never appears for learners. The project owner cannot inspect the reference administrator area, so its final information architecture and permissions remain a joint Phase 2 decision rather than an inferred copy. Vocabulary is withheld from an exam-mode session until that session is finalized.

## 3. Key screen structures

### Reading library — choose what to practise

This screen adopts the useful information architecture seen in the supplied reference: learners can distinguish a standalone passage from a complete test, narrow standalone content by Passage 1–3, see completion progress, and scan question formats before starting. It does **not** reuse the reference product's copy, branding, mascot, or cover images.

```text
┌────────────────────────────────────────────────────────────────────────┐
│ Reading library                                         3 / 14 completed │
│ Choose one passage for focused practice, or a full test for 60 minutes. │
├──────────────────────┬─────────────────────────────────────────────────┤
│ What would you like  │ Showing: standalone passages · Passage 2         │
│ to practise?         │ ─────────────────────────────────────────────── │
│                      │ ┌────────────────┐ ┌────────────────┐           │
│ ◉ One passage        │ │ Passage 2       │ │ Passage 2       │           │
│   ○ Passage 1        │ │ Test 14         │ │ Urban ecology   │           │
│   ◉ Passage 2        │ │ 13 questions    │ │ 12 questions    │           │
│   ○ Passage 3        │ │ Gap fill        │ │ Matching        │           │
│                      │ │ Multiple choice │ │ True/False/NG   │           │
│ ○ Full test          │ │ [ Start practice]│ │ [ Start practice]│          │
│                      │ └────────────────┘ └────────────────┘           │
│ [ Clear filters ]    │                                                   │
└──────────────────────┴─────────────────────────────────────────────────┘
```

The left control is a `fieldset` with radio buttons, not a clickable card pretending to be a radio group. Selecting `Full test` hides the passage sub-filter and presents three-passage test cards. Selecting `One passage` reveals Passage 1–3. A visible `Clear filters` action returns the learner to the default catalogue; the URL retains filters so refresh/back navigation preserves the choice.

The catalogue header uses the completion count plus a thin, labelled progress bar (`3 of 14 published learning units completed`) rather than a decorative percentage. This count is based only on finalized learner work. Cards use a quiet paper surface with one narrow semantic part marker: teal for Passage 1, indigo for Passage 2, and violet for Passage 3. Their top visual is an original typographic text texture or an optional licensed content illustration; a card must still work when no image exists.

Each catalogue card contains, in this order:

1. a part/test label and completion state;
2. the readable title, clamped to two lines with the full title available to assistive technology;
3. question count and the distinct question families derived from the published groups;
4. an attempt state (`Not started`, `In progress — 8/13 answers saved`, or `Completed`);
5. one contextual action: `Start practice`, `Continue`, or `Review result`.

Do not show another user's attempt count as social proof. If aggregate content popularity is later required, it must be clearly labelled and exposed only when analytics consent/policy permits it.

Responsive behaviour:

This is a desktop-first product. The primary visual QA target is a 1280px+ desktop; tablet and mobile deliberately receive a clean, usable transformation rather than a separate feature set or pixel-perfect mobile recreation.

- At 1024px+, retain the left filter rail and a 3-column content grid.
- From 640px to 1023px, move filters into an expandable `Filter reading content` drawer above a 2-column grid.
- Below 640px, use a full-width filter button and single-column cards. Each radio option has a 44px minimum target, and the active filters are announced as a short text summary. No mobile-only analytics or editing features are introduced.

### Learner dashboard — a simple practice record

The dashboard is a place to resume learning and inspect completed work, not an analytics product. It adopts the three useful views in the supplied reference but begins with a small, actionable overview rather than a large, dense table.

```text
┌──────────────────────────────────────────────────────────────────────┐
│ Welcome back, Linh                              [ Browse Reading ]   │
├──────────────────────────────────────────────────────────────────────┤
│ Continue                                                            │
│ Passage 2 · 8 / 13 answers saved · last saved 10:42    [ Continue ] │
├──────────────────────────────────────────────────────────────────────┤
│ Completed work: 8     Overall accuracy: 76%     Last full test: 6.5 │
├──────────────────────────────────────────────────────────────────────┤
│ [ By completed attempt ] [ By question family ] [ By passage ]       │
│                                                                      │
│ Title / submitted       Answered   Correct   Accuracy       Action   │
│ Test 4 · 16 Oct 2026     40 / 40    27        67.5%          Review   │
│ Passage: urban ecology   13 / 13    10        76.9%          Review   │
└──────────────────────────────────────────────────────────────────────┘
```

`Continue` is the visual priority whenever the learner has active work. If no active attempt exists, its area becomes `Choose a passage or full test to start`, with one `Browse Reading` action. The compact summary appears only when its underlying data exists: `Last full test` is omitted if the learner has not finalized a full test, and no artificial zero-value cards are shown.

The three tabs are an accessible `tablist` and keep a short, plain-language title:

| View | MVP rows and calculations | Action |
|---|---|---|
| By completed attempt | Resource title, mode, server submission time, duration, answered/total, correct, skipped, accuracy, full-test band when applicable | `Review` opens the immutable result. `Practise again` remains in the reading library, not a duplicate action in every table row. |
| By question family | The three approved families: `OPTION`, `BLANK`, `MATCHING`; total answered, correct, skipped, and accuracy across finalized work | `Browse Reading` pre-filters the catalogue by the selected family only when that filter is later implemented. MVP has no automated recommendation claim. |
| By passage | Passage 1, Passage 2, Passage 3; total answered, correct, skipped, and accuracy across finalized work | `Browse Reading` opens the corresponding passage-part filter. |

Accuracy is `correct / total questions × 100`, displayed to one decimal only when needed; it is not a progress score. Skipped is derived as `total questions - answered` and is never a separately mutable statistic. Duration is computed from server `startedAt` and `submittedAt`, so it cannot be forged by a browser clock.

The desktop table has a sticky first column and horizontally scrolls only the secondary metrics when needed. On mobile, each row becomes a compact disclosure: title and accuracy remain visible; submission time, duration, correct, and skipped open on demand. This preserves scanability without a miniaturized spreadsheet.

Empty state: `You have not completed a Reading activity yet.` followed by `Browse Reading`. No chart, streak, ranking, or prediction is shown in the MVP.

### Start-mode decision

```text
Practice mode                    Exam simulation
No required timer                60 minutes for all 3 passages
Dictionary available             Dictionary locked until submission
Save and return later            Server ends the test at its deadline
[ Start practice ]               [ Start exam simulation ]
```

Starting exam mode requires a confirmation that names the deadline behaviour. The primary button is `Start exam simulation`, never a vague `Continue`.

### Desktop reading desk

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ [Leave]  Reading test 1 · Passage 2   14/40 answered  Saved 10:42  41:12 left │
├───────────┬─────────────────────────────┬────────────────────────────────────┤
│ Reading   │ Passage 2                   │ Questions 14–18                    │
│ tools     │ Title and estimated time    │ shared instructions                  │
│           │                             │ 14  prompt              ( ) A ...   │
│ Highlight │ Long-form passage text      │ 15  prompt              ( ) A ...   │
│ Notes     │ independently scrollable    │ 16  prompt              [matrix]    │
│ Dictionary│                             │                                    │
│           │                 ↔ splitter ↔│ Question panel scrolls independently │
├───────────┴─────────────────────────────┴────────────────────────────────────┤
│ [ Passage 1 ]  [ Question navigator ]                 [ Submit test ]         │
└──────────────────────────────────────────────────────────────────────────────┘
```

The workspace has a compact, sticky header instead of a large page title. It carries the resource name, passage/answer progress, save state, and—only in timed exam mode—the server-derived remaining time. It never shows a predicted or current band while the learner is answering. `Leave` returns to the library only after explaining whether any unsaved input remains.

The passage and question panes have independent vertical scroll positions and a keyboard-operable draggable separator. Desktop defaults to a 52/48 split, permits 40–65% passage width, and persists the preference locally. The separator exposes `role="separator"`, its current value, and keyboard adjustment with left/right arrows; it is not mouse-only.

The reading-tools rail is deliberately quiet and contextual:

- Selecting text in a passage opens a small contextual menu with `Tô sáng` and `Tra từ`. The menu is anchored to the selection and does not resize the reading/question panes.
- A saved highlight belongs to the learner and immutable passage version, so it remains when the learner leaves and later reopens the passage. It stores the exact quote and a UTF-16 range; overlapping highlights are rejected. Clicking a saved highlight removes it.
- Dictionary lookup is selection-based. The user may choose a returned sense and save the original selected form and selected-definition snapshot as a private vocabulary note. Passage context is captured when available but is optional for a word entered from the vocabulary page.
- A learner can edit the note's personal selected form, optional context, and custom meaning. Editing a note never mutates the shared dictionary entry, sense, pronunciation, or provider audio. The note owner is derived from the authenticated principal.

Highlight, dictionary lookup, and vocabulary-note creation are unavailable during an active full-test session. In passage practice, highlights are available and persist across attempts. They are never used by the system as automatic answer evidence; finalized review supplies explanation text only.

### Question-family interaction rules

| Family | Active attempt | Finalized review |
|---|---|---|
| `OPTION` | `taskVariant` selects standard multiple choice, True/False/Not Given, or Yes/No/Not Given. A shared-choice-pool variant fills numbered answer slots without exposing any key; selection autosaves. | Correct selection uses `leaf`; a wrong submitted choice uses `signal` plus text `Your answer`; correct answer is explicitly named. |
| `BLANK` | `taskVariant` renders a sentence, summary, note, table, flow-chart, or short-answer template. One labelled input per answer follows the visible instruction; browser spellcheck behaviour follows explicit question policy. | Shows raw submitted text, normalized evaluated value when different, and correct answer/explanation. |
| `MATCHING` | One shared candidate pool serves each numbered prompt. `taskVariant` selects information, headings, features, or sentence endings; `candidateReuse` governs duplicate choices. Desktop may offer click/drag fill, but keyboard radio/select interaction is always available. Mobile becomes a labelled select/radio list; no forced horizontal grid. | Correct and incorrect cells use text labels and icons in addition to colour; wrong selections never disappear. |

Question prompts may contain learner-created highlights only when they originate from a deliberate selection. The product never automatically highlights answer evidence, either during an attempt or in finalized review; personal highlights remain visually distinct from answer-status feedback.

Each question has a numbered heading, its saved state, and an optional `Add note` control when allowed. The question navigator shows `Unanswered`, `Answered`, and persisted `Flagged for review`, but never `Correct` until finalization.

On narrow screens, the reader has two top-level tabs: `Passage` and `Questions`. The selected question and progress remain visible in the question tab; no split pane is forced below 960px. A persistent bottom action opens the navigator, and the screen-reader focus moves to the new question heading after navigation.

### Results and review

```text
┌──────────────────────────────────────────────────────────────────────┐
│ Result                                      Time taken: 00:48:14      │
├──────────────────────────────────────┬───────────────────────────────┤
│              27 / 40                 │  Correct       27             │
│            questions correct          │  Incorrect     10             │
│       (labelled score ring)           │  Skipped        3             │
│                                      │  Practice guidance: 6.5        │
│ [ Review answers ] [ Back to library ]│  Academic Reading Practice v2  │
└──────────────────────────────────────┴───────────────────────────────┘

  Detailed breakdown
  Family                 Total     Correct     Incorrect     Skipped
  OPTION                   14          10            3            1
  BLANK                    12           8            2            2
  MATCHING                 14           9            5            0
```

Submitting changes the workspace to `Finalizing your result` while the server performs the single idempotent finalization. A repeat click, timeout race, or reconnect retrieves that same final result; the browser never computes it. Manual submission opens a confirmation that says how many questions are unanswered and provides `Submit now` and `Keep working`. At expiry, the completion message states that the server submitted the test automatically.

The summary follows the approved reference frame: a score ring, correct/incorrect/skipped counts, duration, and a detailed breakdown. It has no paid/free labels, promotional illustration, social-sharing prompt, or default retry button in the MVP. `Review answers` is the primary action; `Back to library` is secondary. Starting a new attempt happens through the library so the learner again chooses the intended mode.

The score ring is an SVG/semantic progress indicator with an adjacent plain-text equivalent, for example `27 of 40 questions correct (67.5%)`; colour is not the only status signal. `leaf` represents correct, `signal` represents incorrect, and `slate` represents skipped. A standalone-passage result shows raw correct/total, accuracy, duration, and the family breakdown but never invents a full-test IELTS band. A full-test result additionally calls the conversion `Practice guidance`, links to its in-product explanation, and never represents it as an official result.

The detailed breakdown is intentionally limited to the approved persisted families: `OPTION`, `BLANK`, and `MATCHING`. It displays total questions, correct, incorrect, and skipped; all four values reconcile to the result total. Future subtype labels such as True/False/Not Given require approved content metadata before they can appear as their own statistical rows.

In the question-pane review, an answer explanation expands below the selected question: answer status, submitted/correct values, and a concise explanation. The learner can collapse it to continue scanning answers; the launch MVP adds no automatic passage-evidence highlight.

### Vocabulary notes and categories — learner workspace

The supplied vocabulary reference has a useful three-zone frame: a category rail, a focused word list, and an add/edit drawer. This system keeps that familiar shape but applies the approved data model rather than reproducing unrelated flashcard or dictionary fields. A compact recent-words list appears below the signed-in dashboard's seven-day Reading statistics. `Từ vựng` appears in the authenticated navbar; it is not a public route.

```text
┌────────────────────┬──────────────────────────────────────────┬────────────────────────┐
│ My categories      │ Work & study                      12 notes│ Add vocabulary note    │
│ [ + Add category ] │ Search notes   [ Status: All ]             │ Search word            │
│                    │ ──────────────────────────────────────── │ ────────────────────── │
│ All notes        28│ Headword       Meaning       Status         │ Choose sense           │
│ Work & study     12│ allocate       phân bổ       Learning       │ Personal meaning       │
│ Science           7│ habitat        môi trường   New            │ Context / note         │
│ From Passage 2    9│                                                │ Categories             │
│                    │ [ + Add vocabulary note ]                 │ [ Save note ]          │
└────────────────────┴──────────────────────────────────────────┴────────────────────────┘
```

Desktop uses a 240px category rail and a data list as the stable work surface; the add/edit drawer occupies the right edge only while it is open. The category rail begins with `Tất cả từ`, followed by the learner's own categories and count. Category creation is inline and asks only for a category name. A category delete confirmation explains that saved words will remain and only their category links are removed.

The note list shows the saved headword, selected definition snapshot, optional personal meaning, source context (`Manual` or a passage reference), categories, and review status (`New`, `Learning`, `Mastered`). Filter by category, keyword, or review status. Compact totals may show those three actual states, but the MVP has no flashcard queue, spaced-repetition scheduler, artificial mastery bar, or daily goal.

`Thêm từ vựng` follows this sequence:

1. search the user's selected word through the local shared cache first; the backend calls Merriam-Webster only when the term is not cached;
2. choose one returned sense (definition, part of speech, phonetic/audio when available);
3. copy that selected definition into the note snapshot, then optionally add a personal meaning and a context/note;
4. select one or more owned categories, with inline category creation; and
5. save as `New` by default.

Learners cannot edit global `vocabulary_entries`, senses, provider data, or another learner's notes. A note may reference the same word more than once when its chosen sense or passage context differs. When a dictionary response is unavailable, the drawer explains the failure and offers retry; it does not fabricate a definition. Flashcards in this MVP flip between saved word and meaning and let the learner update `NEW`, `LEARNING`, or `MASTERED`; they do not implement spaced repetition.

On narrow screens, the category rail becomes a simple filter drawer and the note form becomes a full-screen drawer. This satisfies the project's "usable and visually balanced" mobile standard without giving the workspace separate mobile functionality.

### Administrator content editor — confirmed MVP scope

```text
Draft: Coastal ecosystems                         [ Preview ] [ Publish ]
────────────────────────────────────────────────────────────────────────
Title  [_______________________________]      Part [ 2 ]
Passage body [ rich/plain text editor with character count             ]
Question groups
  1. Matching headings · Questions 14–18                   [ Edit ]
  2. Sentence completion · Questions 19–26                 [ Edit ]
[ Add question group ]
Validation: 2 issues must be fixed before publishing
```

Publish opens a validation summary that links directly to invalid fields. It does not silently save or auto-publish a draft. A passage that passes its own content validation may be published independently for standalone practice. It can later be reused in a full test.

The administrator owns Reading content and users, while learner vocabulary notes and their categories remain private to the learner. Confirmed MVP navigation is **Content + Users only**, with no administrator access to personal vocabulary notes or categories. The authoring workflow is: create/edit and validate a passage; optionally publish it as a standalone practice resource; select three valid published passages into a test; preview and publish that full test. Publishing a test requires exactly three valid published passages—one Part 1, one Part 2, one Part 3—and exactly 40 answerable questions in total. Publishing a standalone passage does not require that full-test shape.

## 4. Essential state inventory

| Context | Required states |
|---|---|
| Authentication | loading, invalid credentials, inactive account, expired session, sign-out complete |
| Catalogues | loading skeleton, empty, published-content unavailable, pagination error, retry |
| Dashboard | active work present/absent, no completed activity, summary partially available, table loading/error/retry, no data in selected aggregation |
| Attempt | creating, saved, saving, save failed/retry, offline with unacknowledged work, flagged for review, personal highlight saved/removed, finalized, forbidden/expired |
| Exam | before start, active with server-derived time, reconnecting, imminent expiry, auto-submitted, manual-submit race resolved |
| Results | submit confirmation, finalizing, idempotent final result retrieval, score loading, no permission, finalized review, no full-test band for individual passage |
| Vocabulary notes | no categories, empty category, word query in progress, cached result, dictionary unavailable/retry, sense required, note saved, category delete confirmation, note delete confirmation |
| Admin editor | new draft, dirty draft, saving, validation errors, preview, publish blocked, published, version-required correction |
| External dictionary | not yet implemented; later: cached, loading, unavailable, not found, quota-limited |

## 5. Reusable component inventory

| Component | Contract |
|---|---|
| `AppShell` | role-aware navigation, skip link, account menu, responsive drawer |
| `StatusMessage` | semantic success/warning/error text; never colour alone |
| `SaveIndicator` | `Saving`, `Saved at HH:mm`, or actionable failure/retry; announced politely |
| `PracticeDashboard` | active-attempt priority, conditional summary, accessible aggregation tabs, no derived metric that lacks source data |
| `PracticeRecordTable` | completed-attempt, family, or passage rows; responsive disclosure layout; review-only actions |
| `ReaderWorkspace` | sticky status header, independently scrolling panes, accessible resizable separator, mobile tab transformation |
| `QuestionNavigator` | numbered state, unanswered/answered/flagged state, keyboard navigation, no correct-answer disclosure |
| `QuestionGroup` | renders one approved family plus its safe `taskVariant`, input mode, shared-pool and candidate-reuse metadata; it never receives a correct-answer link during an active attempt |
| `ReaderPane` | readable measure and selection affordance; personal annotations are distinct from answer-status feedback |
| `PassageHighlightLayer` | creates, restores, and removes owner-only highlights anchored to a published passage version; unavailable in active full-test mode |
| `ReadingToolsRail` | shows highlight and dictionary lookup actions in passage practice; unavailable during an active full-test session |
| `ReviewExplanation` | finalized-only explanation with submitted value and correct value; no automatic evidence highlight in the launch MVP |
| `ModeCard` | explains consequences before an irreversible exam start |
| `ResultSummary` | accessible score ring, correct/incorrect/skipped counts, duration, optional versioned full-test guidance label, review entry point |
| `ResultBreakdown` | finalized-only totals by `OPTION`, `BLANK`, and `MATCHING`; correct + incorrect + skipped reconciles to the total |
| `VocabularyWorkspace` | owned-category rail, filtered note list, and contextual add/edit drawer; it never exposes another learner's notes or mutable provider data |
| `VocabularyNoteDrawer` | query, required sense choice when available, definition snapshot, optional personal meaning/context, category multi-select, and status defaulting to `NEW` |
| `CategoryRail` | `All notes`, owned categories, inline create, count, and a safe association-only deletion confirmation |
| `ValidationSummary` | error count plus focusable links to offending admin-editor fields |
| `ConfirmDialog` | explicit focus trap, descriptive action labels, escape/cancel support unless final deadline action is unavoidable |

## 6. Accessibility and responsive acceptance checks

- Every action is keyboard reachable; visible focus uses `ink` plus an offset ring with at least 3:1 contrast against its adjacent surface.
- All fields have programmatic labels, error messages, and instructions. Question status is text plus icon, never colour alone.
- Passage content has semantic headings, lists, tables, and image alt text where applicable; zoom at 200% retains usable navigation.
- The pane separator is keyboard operable and announces its value; matching matrices expose row and column labels to assistive technology and have a single-column mobile alternative.
- During active attempts, selected answers use a non-semantic selected treatment only. Green/red status appears only in finalized review, with explicit text and icons as well as colour. Learner highlights are personal annotations, not correctness/evidence signals, and are disabled in active exam mode.
- Timer alerts are not announced every second. Announce at 10 minutes, 5 minutes, and 1 minute remaining, with an accessible non-intrusive status region.
- A manual submit asks for confirmation; automatic finalization communicates the completed state after reconnection.
- Desktop target is 1280px+. Reader adapts at 960px, tablet controls at 768px, and mobile layout down to 360px. Mobile is tested for legibility and completion of core flows, not built as a separate experience.

## 7. API contract conventions

### General

- Base path: `/api/v1`.
- JSON request/response bodies use lower camel-case fields; database `snake_case` names are never exposed.
- Resource identifiers are decimal strings in JSON to avoid JavaScript precision loss for `bigint` database IDs.
- All instants are ISO-8601 UTC strings, for example `2026-10-16T08:30:00Z`. The frontend derives display time using its locale but never determines expiry.
- Controllers use request/response DTOs; no persistence entity is serialized directly. Controllers remain thin; lifecycle, ownership, and scoring operations live in transactional services.
- `POST` start/finalize operations accept an `Idempotency-Key` header. The server also enforces unique lifecycle constraints, so retries remain safe when the header is absent.

### Paging, filtering, and concurrency

- Lists accept `page` (zero-based, default `0`) and `size` (default `20`, maximum `100`). Approved sort values are endpoint-specific, e.g. `createdAt,desc`.
- A page response has `{ "items": [], "page": { "number": 0, "size": 20, "totalItems": 0, "totalPages": 0 } }`.
- Draft update DTOs include `version`. A stale edit returns `409` with code `CONTENT_VERSION_CONFLICT`; the UI offers reload rather than overwriting another administrator.
- Mutations return the updated resource or a finalization result, not a bare success boolean.

### Problem details

Spring Boot Problem Details is the base shape (`application/problem+json`), extended only with stable machine-readable fields:

```json
{
  "type": "https://api.example.invalid/problems/validation",
  "title": "Request validation failed",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "instance": "/api/v1/admin/passages",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "partNumber", "code": "OUT_OF_RANGE", "message": "Choose 1, 2, or 3." }
  ],
  "traceId": "..."
}
```

Use `400` for malformed/validation input, `401` for unauthenticated, `403` for role denial, `404` for absent or non-disclosed owned resources, `409` for state/version/idempotency conflicts, `422` for well-formed content that violates publish rules, `429` for rate limiting, and `503` for unavailable external dependencies. Secrets, tokens, password hashes, and provider responses are never returned or logged.

## 8. Initial endpoint catalogue

| Area | Operation | Endpoint | Notes |
|---|---|---|---|
| System | health | `GET /health` | Phase 3; no sensitive diagnostics publicly exposed. |
| Auth | registration / sign in / out / current user | `GET /auth/csrf`, `POST /auth/register`, `POST /auth/sessions`, `DELETE /auth/sessions/current`, `GET /me` | Registration creates a learner only. Session cookie is HttpOnly and SameSite=Lax; unsafe requests require the session CSRF token. |
| Learner dashboard | overview and active work | `GET /dashboard` | Returns active attempts, conditional summaries, and a small recent-completed list; only the authenticated learner's data. |
| Learner statistics | paged aggregation | `GET /statistics?dimension=ATTEMPT`, `GET /statistics?dimension=QUESTION_FAMILY`, `GET /statistics?dimension=PASSAGE` | Counts derive only from finalized attempts; dimension values are fixed and validated. |
| Learner catalogue | reading-library read model | `GET /reading-library?format=PASSAGE&partNumber=2&progress=UNFINISHED` | Supplies the selection UI with published cards, learner-specific attempt state, question-family summary, and completion totals. `format` is `PASSAGE` or `FULL_TEST`; `partNumber` is allowed only for `PASSAGE`. |
| Learner catalogue | list / view passages | `GET /passages`, `GET /passages/{passageId}` | Published content only; catalogue payload is lighter than reader payload. |
| Practice attempt | start / view / save / flag / submit | `POST /passages/{passageId}/attempts`, `GET /attempts/{attemptId}`, `PUT /attempts/{attemptId}/answers/{questionId}`, `PUT /attempts/{attemptId}/question-states/{questionId}`, `POST /attempts/{attemptId}/submit` | The active-reader response contains the attempt's versioned passage, question-group `taskVariant`/input metadata, candidates, current learner answers, flags, and permitted tools; it contains no answer key, correctness, explanation, or automatic evidence highlight. Answer save and flag state are idempotent ownership/lifecycle-checked updates. |
| Practice highlights | list / create / delete | `GET/POST /api/reading/passages/{passageId}/highlights`, `DELETE /api/reading/passages/{passageId}/highlights/{highlightId}` | Owner-only passage-version highlights persist across attempts. Creation validates the exact selected text and UTF-16 range; overlapping ranges and mutations during an active full test are rejected. |
| Full test | list / start / view / submit | `GET /reading-tests`, `POST /reading-tests/{testId}/sessions`, `GET /test-sessions/{sessionId}`, `POST /test-sessions/{sessionId}/submit` | Exam response includes server timestamps and `expiresAt`; finalization is idempotent. |
| Results | attempt / session review | `GET /attempts/{attemptId}/result`, `GET /test-sessions/{sessionId}/result` | Includes server-calculated summary and family breakdown after finalization; correct answers, review status, and explanations appear only then. Passage highlights load from the owner-only passage endpoint and remain independent of the attempt. |
| Categories | list/create/delete | `GET/POST /api/vocabulary/categories`, `DELETE /api/vocabulary/categories/{categoryId}` | Owner derived from the authenticated principal; foreign category IDs are rejected and database composite FKs enforce ownership. |
| Vocabulary notes | list/recent/create/status/delete | `GET /api/vocabulary-notes`, `GET /api/vocabulary-notes/recent`, `POST /api/vocabulary-notes`, `PATCH /api/vocabulary-notes/{noteId}/status`, `DELETE /api/vocabulary-notes/{noteId}` | Only the authenticated learner's notes. Creates copy a server-known sense definition into a snapshot and accept only owned category IDs. |
| Vocabulary lookup | shared cached-entry/provider lookup | `GET /api/vocabulary/lookup?word=allocate` | Checks shared entries and mapped stems first; only cache misses call Merriam-Webster from the backend. Provider key is never sent to the client. |
| Admin passages | list/create/detail/update/preview/publish | `GET/POST /admin/passages`, `GET/PATCH /admin/passages/{passageId}`, `GET /admin/passages/{passageId}/preview`, `POST /admin/passages/{passageId}/publish` | Admin-only; payloads carry group task variants, safe presentation templates, choice/matching pools, reuse rules, and private answer keys. A passage may publish independently once its own validation passes; `publish` returns `422` with grouped violations. |
| Admin tests | list/create/detail/update/publish | `GET/POST /admin/reading-tests`, `GET/PATCH /admin/reading-tests/{testId}`, `POST /admin/reading-tests/{testId}/publish` | Requires exactly three valid **published** passages: one each for Parts 1–3 and exactly 40 answerable questions in total. It does not make constituent passage publication automatic. |
| Admin users | list / activation change | `GET /admin/users`, `PATCH /admin/users/{userId}` | No password hash or security metadata in response. |

### Representative learner operations

Dashboard overview response:

```json
{
  "activeAttempts": [
    {
      "attemptId": "901",
      "resourceTitle": "Urban ecology",
      "passagePart": 2,
      "savedAnswers": 8,
      "totalQuestions": 13,
      "lastSavedAt": "2026-10-16T10:42:00Z"
    }
  ],
  "summary": {
    "completedActivities": 8,
    "overallAccuracy": 76.0,
    "lastFullTestBand": 6.5,
    "lastFullTestBandLabel": "6.5"
  },
  "recentCompleted": []
}
```

Statistics response rows expose `totalQuestions`, `answered`, `correct`, derived `skipped`, and `accuracy`. An attempt row additionally exposes `submittedAt`, `durationSeconds`, `resourceType`, and optional full-test `bandScore`/`bandResultLabel`; it never exposes another learner's activity or an answer key.

Finalized result summary shape:

```json
{
  "status": "SUBMITTED",
  "totalQuestions": 40,
  "correct": 27,
  "incorrect": 10,
  "skipped": 3,
  "accuracy": 67.5,
  "durationSeconds": 2894,
  "bandScore": 6.5,
  "bandResultLabel": "6.5",
  "bandTableVersion": "academic-reading-practice-v2",
  "familyBreakdown": [
    { "questionType": "OPTION", "totalQuestions": 14, "correct": 10, "incorrect": 3, "skipped": 1 },
    { "questionType": "BLANK", "totalQuestions": 12, "correct": 8, "incorrect": 2, "skipped": 2 },
    { "questionType": "MATCHING", "totalQuestions": 14, "correct": 9, "incorrect": 5, "skipped": 0 }
  ]
}
```

For a standalone passage, `bandScore`, `bandResultLabel`, and `bandTableVersion` are absent.

Reading-library response:

```json
{
  "filters": { "format": "PASSAGE", "partNumber": 2, "progress": "UNFINISHED" },
  "completion": { "completedUnits": 3, "totalPublishedUnits": 14 },
  "items": [
    {
      "resourceType": "PASSAGE",
      "passageId": "42",
      "partNumber": 2,
      "title": "Urban ecology",
      "questionCount": 13,
      "questionTypes": ["BLANK", "MATCHING"],
      "attemptState": "IN_PROGRESS",
      "savedAnswers": 8,
      "completedAt": null
    }
  ],
  "page": { "number": 0, "size": 20, "totalItems": 1, "totalPages": 1 }
}
```

The endpoint returns only the current learner's activity state. It returns no correct answers, passage body, question text, or explanation; the reader endpoint remains the first point at which a valid attempt can load answerable content.

Start standalone attempt:

```http
POST /api/v1/passages/42/attempts
Idempotency-Key: 7a2c...
Content-Type: application/json

{ "mode": "PRACTICE" }
```

```json
{
  "attemptId": "901",
  "passageId": "42",
  "mode": "PRACTICE",
  "status": "IN_PROGRESS",
  "startedAt": "2026-10-16T08:30:00Z",
  "questions": []
}
```

Save an answer:

```json
{ "submittedValue": "B", "submittedRawValue": "B" }
```

Finalize a full test:

```json
{
  "sessionId": "801",
  "status": "SUBMITTED",
  "totalCorrect": 27,
  "totalQuestions": 40,
  "bandScore": 6.5,
  "bandResultLabel": "6.5",
  "bandTableVersion": "academic-reading-practice-v2"
}
```

## 9. Phase 2 exit criteria

Phase 2 closed with project-owner approval on 2026-10-16. The Reading Desk direction, reference-frame boundary, desktop-first responsive behaviour, task-variant taxonomy, matching-frame model, personal-annotation rule, component inventory, and API rules are agreed. Phase 3 now creates the runnable foundation from these contracts; it does not begin by generating every entity or controller.
