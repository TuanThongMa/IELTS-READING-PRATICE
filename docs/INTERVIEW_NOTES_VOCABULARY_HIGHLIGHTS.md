# Interview Notes: Shared Vocabulary and Passage Highlights

## Shared dictionary cache vs personal notes

- `vocabulary_entries`, senses, pronunciations, and related words are shared dictionary data. A normalized language/word key and search-term aliases let later users reuse cached provider results.
- `vocabulary_notes`, categories, and review status belong to one user. Queries and mutations always include the authenticated `user_id`; composite foreign keys also prevent attaching another user's category to a note.
- A note copies the chosen definition into `selected_definition`. This snapshot keeps a learner's saved meaning stable if provider data is refreshed later.
- A note also references the exact `vocabulary_sense_id` selected during lookup. The sense points to its pronunciation and audio URL, so the personal collection plays the same pronunciation without copying provider data into every note. Existing notes are backfilled by matching the saved definition where possible.
- The provider API key stays in backend configuration. The browser calls the application API and never receives the provider credential.

**Interview point:** A shared cache can avoid repeated third-party calls, but it must not turn personal annotations into shared data. Separate ownership models and enforce them in both SQL constraints and service queries.

**Interview point:** Store a stable relationship to the selected dictionary sense in addition to snapshotting the displayed definition. The snapshot preserves what the learner saw; the foreign key retrieves related data such as IPA and audio. This avoids guessing a pronunciation from another sense of the same headword.

**Interview point:** Uniqueness of a shared dictionary entry does not prevent duplicate personal notes. The personal identity is user + selected sense + source passage + context; repeated saves reuse the note and merge category links. A PostgreSQL unique index protects concurrent requests, while the API reports whether it created or reused a note. Different contexts remain separate learning occurrences.

**Interview point:** A category is an optional grouping of personal notes, not their owner. An uncategorized note still belongs to the user and appears in “All words”; deleting a category must not delete its notes. Keep global totals in a separate user-scoped aggregate query, because counting a category-filtered or `LIMIT`ed list gives an incorrect total.

## Passage highlight anchors

- A personal highlight stores `passage_id`, either `quiz_attempt_id` or `reading_test_attempt_id`, UTF-16 `start_offset`/`end_offset`, the exact selected text, and nearby context.
- Offsets match JavaScript string indexing in the browser and Java `String` indexing in the API. The server checks the submitted quote against the stored passage text before accepting it.
- Highlights are user-owned and independent from answer keys, answer evidence, and scoring. Each belongs to one passage or full-test attempt. Review loads that submitted attempt's highlights; a new attempt starts with none.
- Overlapping selections are rejected to keep rendering and deletion predictable. Highlight remains available during an exam attempt; dictionary lookup is disabled in real-test mode.

**Interview point:** A user-scoped annotation still needs the right lifecycle key. Scoping highlights only by `user_id` and `passage_id` makes a retake inherit the previous attempt's marks. The API validates that the attempt belongs to the user and contains the passage, while unique partial indexes isolate ranges per attempt type. The migration assigns old marks to an attempt only when timestamps establish a match; unmatched marks are retained without attaching them to a guessed attempt.

**Interview point:** Text offsets are only stable against a particular text version. Publishing edited content as a new passage version avoids silently moving old highlights to unrelated text.

## Practice vs real-test attempt mode

- Each passage or full-test attempt stores its selected mode (`PRACTICE` or `REAL_TEST`) in PostgreSQL. Reopening an in-progress attempt returns the stored mode; a URL or client request cannot switch it.
- Practice mode allows dictionary lookup and vocabulary-note saving. Real-test mode hides those controls in the browser and the API rejects lookup and note-creation requests while the user has an active real-test attempt.
- Leaving a real-test attempt saves the latest answers and elapsed time, changes the attempt to `PAUSED`, and allows the user to continue later. Resuming restores `IN_PROGRESS` and the stored mode. This also releases real-test-only API restrictions while the learner is outside that attempt. Submitting is a separate permanent action.

**Interview point:** A UI restriction is not an authorization boundary. The backend checks the active attempt mode for vocabulary operations, while the database stores the mode so resume behavior remains consistent across refreshes and devices.

**Interview point:** Separate “pause” from “submit.” Pausing checkpoints a resumable attempt and releases active exam restrictions; submission finalizes it and makes its answers immutable. This models user intent directly and avoids treating a lost browser tab as a completed exam.

## External API and resilience

- Lookup is local-first: check normalized words and provider-returned stems before making an external request.
- Normalize case, whitespace, and Unicode before cache lookup. Keep provider parsing behind a backend service so the frontend depends on a stable application DTO.
- A provider result can match a phrase in `meta.stems` without defining the selected word itself. Prefer the exact headword, then valid single-word inflections; do not attach the requested term to every returned entry. Old derived aliases must be invalidated when that matching rule changes.
- Merriam-Webster usage examples are `vis` pairs inside individual senses. Keep one example with its own definition, and mark cached entries after a successful parse so entries without examples do not trigger a provider call on every lookup. For a note added directly in the vocabulary page, the chosen dictionary example becomes editable context. For a note selected from a passage, save its source sentence as context and continue showing the dictionary example separately during lookup. The saved context is a snapshot; `passage_id` records where the lookup happened.
- The passage context extractor uses the selected text offset within its paragraph and `Intl.Segmenter("en", { granularity: "sentence" })`. It joins common abbreviations that the segmenter may split, and falls back to at most ten words on each side when no reliable sentence is found or the sentence exceeds 80 words.
- When the provider key is missing or the provider is unavailable, return a clear service error; cached dictionary entries remain usable.

**Interview point:** A cache improves latency and reduces cost, but it does not by itself solve concurrent cache misses, provider rate limits, stale data, or terms with multiple senses. Those need explicit policies as traffic grows.

**Interview point:** Search matches from a third-party dictionary are not interchangeable meanings. Validate the relationship between the query and each headword before caching, and keep examples attached to their source sense. Cache invalidation must address previously stored derived links without deleting user notes.

**Interview point:** A sentence segmenter is a locale-aware heuristic, not a guarantee of grammatical boundaries. Keep the original text offsets, handle known abbreviation cases, and define a bounded fallback instead of assuming every period ends a sentence.

## Verification boundary

- A frontend production build checks TypeScript and bundling.
- A backend package build checks Java compilation and packaging; it does not prove SQL migrations, ownership isolation, or provider parsing work against PostgreSQL.
- Before launch, add PostgreSQL integration coverage for migration startup, cross-user access, idempotent shared-cache insertion, highlight validation, and note/category ownership.
