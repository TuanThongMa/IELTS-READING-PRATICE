# Admin PDF Import Flow — Design Proposal

Status: Step 1 backend extraction complete; Admin review UI pending  
Updated: 2026-11-03

## Scope and order

This starts backlog item B3 in `IMPLEMENTATION_ROADMAP.md`. The next matching validation in a full test remains deferred until this flow is complete, as requested.

The importer lives inside the existing Admin area and supports IELTS Academic Reading content only. Detect text per page and send image-only pages through OCR: the supplied Cambridge PDF has no extractable text, so OCR is needed for this source.

## Proposed admin flow

```text
Choose source and import scope
          ↓
Extract and compare with source PDF
          ↓
Review passage boundaries, questions, and answer key
          ↓
Save drafts → use existing validation → publish one passage or the full test
```

### 1. Choose source

- Add **Nhập đề từ PDF** beside the existing Admin actions; do not add a separate public route.
- Choose either **Một passage** (then select Passage 1, 2, or 3) or **Full test**.
- Upload one PDF with clear file-size and file-type feedback.
- Explain that scanned pages may take several minutes and every imported answer needs Admin confirmation. Upload never publishes content automatically.

### 2. Compare extraction

Desktop layout uses two independently scrollable panes: the original PDF on the left and extracted, editable text on the right. Extracted blocks show their source page so the admin can compare and correct reading order, headings, and paragraph boundaries. On narrow screens, show the source and extracted content as switchable panels.

Show parser warnings next to the affected page or block, such as a page with no selectable text or a suspected split passage. Keep the current approved palette and font tokens (`Be Vietnam Pro` for controls, `Source Serif 4` for reading content).

### 3. Review IELTS structure

- Suggest passage boundaries, passage numbers, question ranges, group instructions, and likely task variants when recognizable.
- Show uncertain or unrecognized content as **Cần kiểm tra**; never silently omit it.
- Let the admin edit extracted passage text, question prompts, options/candidate pool, answer key, accepted answers, and word limits before saving.
- Require explicit confirmation of answer keys and candidate-reuse rules. PDF layout extraction alone cannot establish the intended correct answer or all IELTS authoring rules.
- Apply the existing flexible passage numbering and 40-question full-test validation; do not hardcode 13/13/14 question splits.

### 4. Save and publish

- Save confirmed content as drafts using the existing passage and question-group model.
- Allow an individual passage to be published on its own.
- Publish a full test only after all three passages and the complete 40-question composition pass validation; the operation must be transactional so no partial publication occurs.
- Keep the source PDF available in the review flow while the admin is checking it. Decide the persisted source-reference model after the extraction spike, before adding a Flyway migration.

## Technical proposal

- Put OpenDataLoader PDF 2.5.17 behind a bounded Spring Boot extraction worker. The spike used its CLI successfully; the implementation decision below keeps it in a separate process and calls a local Python hybrid OCR service for scanned pages. The Java core also supports hybrid mode, but the process boundary gives the web JVM an enforceable timeout and limits parser failures to one job.
- Prefer structured JSON extraction for page and element provenance, with Markdown/plain text as an editing representation. The parser extracts document layout; a separate, conservative IELTS recognition layer proposes structure and must not be treated as ground truth.
- Keep the first extraction spike read-only: process the existing `Cambridge18-test2.pdf`, compare extracted text/page references against the source, and record where heuristics succeed or fail. Do not create or publish database content during the spike.
- For the integrated flow, enforce ADMIN authorization and CSRF, check both MIME type and PDF signature, cap upload size/pages, use random temporary filenames, clean temporary files on success/failure, and avoid logging uploaded text or document contents.
- Add database changes only after the extraction shape and provenance requirements are understood; use Flyway. Saving the confirmed full-test draft must be atomic.

## First implementation checkpoint

1. Run the extraction spike against the supplied Cambridge PDF.
2. Review text fidelity, page references, and passage/question-boundary signals.
3. Present the extraction findings and final UI/API shape for approval.
4. Implement the Admin upload-and-review vertical slice, then transactional draft creation and publication validation.

The first checkpoint must answer whether OpenDataLoader's Java artifact resolves cleanly in this Spring Boot build and whether its page-level output is useful for this IELTS PDF. Its Java guide lists a Maven coordinate and an additional repository, so dependency resolution is a concrete early risk to verify.

### Checkpoint 1 findings — 2026-11-02

- The official OpenDataLoader 2.5.17 CLI processed the supplied 14-page `Cambridge18-test2.pdf` successfully after it was copied to an ASCII-only temporary filename. Directly passing the original Windows path containing Vietnamese characters failed because that CLI invocation changed characters in the path to `?`.
- Deterministic extraction produced 14 image elements, one on every page, zero text elements, and a zero-byte Markdown file. This particular source requires OCR before passage or question recognition can begin. The original roadmap's condition for moving OCR earlier is therefore met.
- The `org.opendataloader:opendataloader-pdf-core:2.5.17` Maven artifact and its transitive dependencies resolved through the project's Maven wrapper when the documented `vera-dev` repository was supplied. The backend has not yet been changed or built with this dependency.
- All parser outputs and the temporary PDF copy remain outside the Git repository. No database rows or learner-facing content were changed.

**Next checkpoint, pending owner confirmation:** try OpenDataLoader's hybrid OCR on a small representative page set from this PDF, compare the recognized text and question numbers with the source, and decide whether its runtime cost and accuracy fit the Admin import flow. Keep the text-PDF path as the simpler route when a source has a text layer.

### Checkpoint 2 findings — 2026-11-02

- Ran OpenDataLoader PDF 2.5.17 hybrid mode (`docling-fast`, local EasyOCR, English, CPU) on three representative pages from the supplied scanned PDF: original PDF pages 1 (Passage 1 text), 12 (questions 27–33), and 14 (answer key). The temporary three-page PDF and all OCR outputs were kept outside the repository. No Spring Boot, frontend, Flyway, or database content was changed.
- The run succeeded in about **66 seconds for three pages** (server log: 47.9 seconds OCR, about 22 seconds per page overall). The JSON contains 58 structured elements with bounding boxes and page references; its page numbers refer to the temporary three-page input, so an integrated importer must retain the mapping back to original PDF pages 1, 12, and 14.
- Passage 1 heading and most prose were recovered, but punctuation changed and at least one phrase was reordered: the source's “a ring of timber posts” became “a of timber posts ... ring”. All seven numbered statements 27–33 on the sampled question page were recovered, but the three TRUE/FALSE/NOT GIVEN instruction definitions were separated from their labels. These are usable **draft text**, not publication-ready content.
- On the answer-key page, OCR recovered answer values for **34 of 40** questions. It omitted the six values for questions **14–19** (source: 14 C, 15 A, 16 B, 17 D, 18 C, 19 D); it also dropped the number before answer 1. A separate 300-dpi crop of the key improved recognition of 14 C and 15 A but still missed several short, isolated letters. The answer key therefore requires explicit visual confirmation for every question; missing or ambiguous values must block publication rather than be guessed.
- The first attempt passed `--pages 1,12,14` against the original 14-page PDF, but the hybrid backend still processed all 14 pages and exceeded a 120-second timeout. Physically extracting the selected pages into a temporary PDF made the three-page run finish. Page selection for preview therefore needs actual PDF subsetting before this hybrid OCR route, with original-page mapping preserved.
- **Decision for the next checkpoint:** OCR is feasible for this scanned source, but its CPU cost and recognition errors make a synchronous upload-and-publish request unsuitable. Design an asynchronous, bounded Admin extraction job with visible progress and a side-by-side source review. Keep text-layer extraction as the cheaper route for text PDFs. Implement this only after the owner approves the next step.

### Checkpoint 3 — Admin UI and API design, pending owner review

#### Fit with the current application

- Add **Nhập đề từ PDF** to the existing `AdminPage` heading actions. Keep Admin access behind the current session/`ROLE_ADMIN` protection and CSRF token. The current frontend `api()` helper sends JSON only, so multipart upload needs a small separate helper that retains the CSRF header and lets the browser set the multipart boundary.
- The current passage editor creates a passage first and then its question groups; the test editor accepts only three **published** passages. Import review must therefore stage a complete document separately, then call one server-side application service to create the confirmed passage/group records. A series of frontend calls to the current CRUD endpoints could leave a partial full test. Choosing **Lưu các passage nháp** creates passage/group drafts only; it does not create a `reading_tests` draft.
- Passage, group, and test rules currently live mostly in their Admin controllers. Extract the shared creation and publication rules into small services before the import `apply` path; the importer must call those services within one transaction rather than call other controllers or duplicate their validation.
- The current group service renumbers questions locally from 1 through N within each passage. Keep original PDF question numbers in the import review for checking; map them to local numbers only when applying the reviewed content. For a full test, validate the original numbers 1–40 and the flexible three passage ranges before applying.

#### Admin screen

```text
Admin heading: Quản trị Reading             [Nhập đề từ PDF]

Upload:  [Một passage / Full test] [Passage 1/2/3 if needed]
         [Chọn PDF] [Bắt đầu nhận dạng]
Progress: Đang nhận dạng trang 6/14 · Có thể rời trang và quay lại

Review:  Tên đề / phạm vi / số trang / số mục cần kiểm tra
┌────────────────────────────┬───────────────────────────────────┐
│ PDF gốc                    │ Passage 1 | 2 | 3 | Đáp án       │
│ Trang 12/14 · phóng to     │ Văn bản, nhóm câu hỏi, đáp án      │
│                            │ từng khối sửa được · Trang nguồn  │
└────────────────────────────┴───────────────────────────────────┘
         [Lưu phần kiểm tra] [Kiểm tra đề] [Lưu các passage nháp] [Xuất bản]
```

- Use a bundled PDF.js viewer in the left pane so clicking a field's **Trang nguồn** opens the exact source page; do not rely on a browser PDF plugin's fragment navigation. Keep the approved visual tokens: page `#F8FAFC`, surface `#FFFFFF`, border `#E2E8F0`, text `#1E293B`, primary `#2563EB`, and warning `#B45309`. The right pane keeps passage text in `Source Serif 4`; controls and warnings use `Be Vietnam Pro`. Blue indicates the active field, amber indicates **Cần kiểm tra**, and red uses the existing danger token for a blocking error. The PDF page and editable source block are the visual focus; avoid decorative cards, new colors, or dark mode.
- The review view expands beyond the current Admin container's 1100-pixel maximum width. Add `importing` to the existing mutually exclusive Admin view state and include its dirty form in the parent `useBlocker`/`beforeunload` guard; do not mount a second router blocker. Bundle `pdfjs-dist` and its worker locally so source viewing does not depend on a CDN.
- The right pane shows extracted text and detected question groups as editable fields. Selecting a passage, question, or answer keeps the corresponding PDF page visible. OCR text is always shown as a proposal; unmatched blocks remain visible under **Chưa phân loại**, and missing answer values appear as empty fields with their source question number.
- The answer-key tab lists all expected numbers, including 14–19 that OCR missed in the sample. Each answer has an explicit `confirmed` flag that Admin sets after comparing the PDF; `/check` blocks publication while any flag is false. A value guessed from question content is never prefilled as a fact. Keep the existing group-level `correctAnswerLabels` for multiple-answer choice, matching `candidateReuse`, blank accepted answers and word limits, and the `presentationTemplate`/`inputMode` fields for summary, table, and flow-chart completion. Do not flatten all 14 variants into one generic `question.answer` field.
- On a narrow viewport, switch between **PDF gốc** and **Nội dung nhập** while retaining the selected page/field. Poll job status about every three seconds while the progress view is open; stop polling on unmount. The server job continues if the Admin leaves, and a recent-import list restores it after refresh. Preserve unsaved-change navigation warnings for review edits.

#### Import job and storage

- Flyway adds one `pdf_import_jobs` table with a bigint identity key, `created_by_user_id` foreign key, scope/optional part number, constrained status, `cancel_requested` boolean, progress counts, random source-storage key, safe display filename, `review_data jsonb`, applied result IDs, integer review version, error code, and timezone-aware created/updated/expiry timestamps. Index `(created_by_user_id, created_at DESC)` for the recent-import list. The JSON is read and replaced by job ID; no GIN index is needed. Do not store PDF bytes in PostgreSQL.
- Put source PDFs in a private, persistent Docker volume mounted into the API container. Use generated filenames and never serve the directory publicly. Retain source and review data for seven days, then remove both with a cleanup task; show expiry in the Admin screen. The applied passage/test records remain after cleanup.
- Initial configurable limits: one running OCR job, two queued jobs, 20 MB per PDF, 25 pages, and a 15-minute total processing timeout. Reject oversized or invalid PDFs before queueing. These numbers are a starting bound for the 14-page sample and must be checked against actual Docker memory and timing during implementation.
- Job states: `QUEUED → RUNNING → REVIEW_READY → DRAFT_SAVED/PUBLISHED`; failures become `FAILED`. A discard request sets `cancel_requested`; the worker stops after its current page batch and marks `CANCELLED`. Cleanup marks old jobs `EXPIRED` and removes their source/review data. Neither `CANCELLED` nor `EXPIRED` jobs can be applied. On API restart, requeue persisted `QUEUED` jobs and mark interrupted `RUNNING` jobs retryable. OCR and file I/O happen outside a database transaction; short transactions update job state/review and apply confirmed content.

#### Extraction execution

- Run the tested OpenDataLoader PDF 2.5.17 CLI as a bounded child process from a Spring service. Package the pinned CLI JAR in the API image. For scanned pages, a local hybrid service runs EasyOCR in a separate Compose service, reachable by its service name on the internal network; do not expose its port publicly. Cache its models in a volume so a restart does not download them again.
- Add a PDF splitting/text-detection library to the backend image (for example PDFBox); the current JRE image has no such tool. Detect selectable text on each page. Keep the text-layer route for those pages; send only image-only pages through hybrid OCR. Split OCR work into small physical page batches (initially up to three pages), because the tested `--pages` flag did not stop the hybrid backend from processing the whole source PDF. Preserve the original-page map and report progress after each completed batch. Limit subprocess time, capture a bounded error summary, delete temporary batches, and never log the extracted passage or answer key.
- The recognition layer may propose passage boundaries, instructions, task types, question groups, and answers. It must retain every source block and mark uncertainty. It cannot silently invent missing OCR text or turn an unrecognized diagram/table into a plain question.

#### API contract (`/api/admin/pdf-imports`)

| Endpoint | Purpose | Main response |
| --- | --- | --- |
| `POST /` multipart `file`, `scope`, optional `partNumber` | Validate and enqueue | `202` with job ID, state, page count, `Location` |
| `GET /` and `GET /{id}` | Recent jobs; state, phase, processed/total pages, expiry, error | `200` |
| `GET /{id}/source` | Stream original PDF to the Admin viewer | `200 application/pdf`, private/no-store |
| `GET /{id}/review` | Immutable source blocks plus editable recognized draft and review version | `200` when ready |
| `PUT /{id}/review` with `{version, draft}` | Save corrected text, grouping, and confirmed answers | `200` with next version; stale version `409` |
| `POST /{id}/check` with `{version, mode}` | Return field paths, source pages, and blocking issues | `200 {ready, issues}` |
| `POST /{id}/apply` with `{version, mode}` | `DRAFT`: create passage/group drafts; `PUBLISH`: validate and publish a passage or all three passages plus full test atomically | `201` with created IDs; identical retry returns same IDs, different mode returns `409` |
| `POST /{id}/retry`; `DELETE /{id}` | Retry a failed job from retained source; discard or request cancellation | `202` for queued retry/cancellation; `204` for immediate discard |

All endpoints are Admin-only and job reads/updates are scoped to the creator. Use the existing session and CSRF rules for mutations. Return `400/415/413` for invalid input, `404` for an inaccessible job, `409` for stale version/state, `422` for reviewed content that fails validation, `429` when the bounded queue is full, and `503` if OCR is unavailable. Error messages tell the Admin what to fix without exposing server paths or OCR internals.

#### Apply and publication rules

- `DRAFT` creates the confirmed passage(s) and question groups in one transaction. If any group cannot be represented by the current 14 supported variants, return field-level issues and create nothing. Store the resulting IDs on the job so a browser retry cannot create duplicates. The import review then becomes read-only; Admin continues editing and later publishes those drafts through the existing passage/test editors.
- `PUBLISH` is available directly from a fully reviewed, unapplied import. It validates each passage and, for a full test, exactly three distinct passage parts and 40 globally consecutive source question numbers. In one transaction it creates the records, publishes all three passages, composes the full test with cumulative ranges, and publishes the test. Lock the import job row and write its status/result IDs in that same transaction, so a retried request returns the same IDs. Any failed validation rolls back the entire operation. An import already applied as `DRAFT` cannot be applied a second time as `PUBLISH`; use the existing editors for those drafts.
- A single-passage import uses the same review and apply path but publishes only that passage; no full-test band score is created. A later full test may reuse the published passage through the existing composition flow.
- Source-page coordinates and the imported global numbers remain in the temporary review record, not in learner-facing content. The published model continues to use the current passage-local numbering and computed full-test ranges.

#### Next implementation slices, only after approval

1. Flyway job table, private file storage, multipart validation, bounded worker, and job-status API. Verify a scanned upload reaches `REVIEW_READY` without changing Reading content.
2. Admin two-pane review and correction UI, including source-page navigation, review saving, and explicit answer confirmation.
3. Transactional draft/publish application into the existing passage, question-group, and full-test services; validate the complete Cambridge sample and failure/retry behavior.

#### Implementation checkpoint 4 — 2026-11-03

- Slice 1 is implemented: Flyway V21 job table, Admin-only multipart API, private PDF volume, one bounded extraction worker, text-layer detection, OCR batches of up to three physical pages, timeout/progress/retry/cancel/expiry handling, and source-page mapping. No passage, question, or full-test record is created by extraction.
- The PostgreSQL HTTP integration test on a disposable database covered Admin authorization, owner scoping, PDF signature and scope validation, and the three-job queue bound. The text-PDF smoke test reached `REVIEW_READY` with two text blocks. The scanned Cambridge page reached `REVIEW_READY` with ten OCR blocks.
- The complete 14-page `Cambridge18-test2.pdf` reached `REVIEW_READY` with **14/14 pages, 217 blocks**, and source-page numbers `[1, 2, ..., 14]`. Every page is flagged `OCR_REVIEW_REQUIRED`. The disposable database still had zero passages and zero reading tests afterward.
- Docker OCR uses PyTorch 2.6.0 and torchvision 0.21.0 CPU builds. An earlier 2.5.1 CPU attempt exposed a Transformers model compatibility error during a real OCR request, although `/health` returned OK; the corrected image passed both scan tests. The OCR model files are cached in a named volume.
- The Admin review/correction screen, answer confirmation, and draft/publish transaction remain in slices 2–3. Do not treat the 217 recovered blocks as verified IELTS content.

## Acceptance criteria for the complete importer

- Admin can upload a supported PDF and compare every extracted passage/question with its source page.
- Passage boundaries, question numbering, task variants, answer keys, and accepted-answer rules can be corrected before saving.
- Invalid, unsupported, oversized, or unreadable files produce a clear recoverable error and do not leave draft content behind.
- Confirmed passages can be saved and published individually.
- A full test can be published only when all three passages and exactly 40 numbered questions pass the existing flexible range rules; no partial publication occurs.

## Interview note

**Điểm phỏng vấn:** PDF text extraction and IELTS content recognition are different problems. The parser can recover text, order, and source coordinates; domain validation and human review prevent a plausible-looking but incorrectly structured exam from being published. Persist confirmed drafts transactionally so a failure cannot leave a half-created test.

**Điểm phỏng vấn:** Detect whether an uploaded PDF has extractable text before selecting the processing route. An image-only document needs OCR; blindly running a text parser can return a technically successful job with empty learning content. Generated ASCII temporary filenames also avoid filename/path encoding problems and prevent the original filename from controlling a server-side path.

**Điểm phỏng vấn:** OCR output is untrusted source data, even when the job succeeds. Keep page provenance, flag missing question numbers and answer keys, and require human confirmation before a transaction creates publishable exam content. A slow OCR dependency also belongs behind a bounded asynchronous job, with timeout and cleanup, rather than inside a long HTTP request.

**Điểm phỏng vấn:** Persist a background job's state and result IDs. On publish, lock its row and update those IDs in the same transaction that creates Reading content. A repeated HTTP request then returns the original result instead of publishing a duplicate test; OCR itself stays outside that transaction so it cannot hold database locks for minutes.

## References

- [OpenDataLoader PDF repository](https://github.com/opendataloader-project/opendataloader-pdf)
- [OpenDataLoader Java quick start](https://opendataloader.org/docs/quick-start-java)
- [OpenDataLoader hybrid mode](https://opendataloader.org/docs/hybrid-mode)
- [OpenDataLoader CLI options](https://opendataloader.org/docs/reference/cli-options)
