# IELTS Reading Practice Project — Project Context

Last updated: 2026-10-16

## Purpose

The project is an IELTS Academic Reading practice system developed for an Information Systems academic project. The system supports individual passage practice, full-test simulation, vocabulary notes, scoring, and learner performance statistics.

This repository is currently in the analysis and design stage. Do not create application code until the data model, business rules, and interface structure have been agreed.

## Planned technology

- Backend: Spring Boot REST API.
- Database: PostgreSQL.
- Frontend: to be designed and implemented after the visual direction, color palette, typography, sitemap, and interface structure are agreed.
- External vocabulary provider: Merriam-Webster Learner's Dictionary API, accessed only through the backend. The API key is a secret and must not be exposed to the frontend or committed to the repository.
- Delivery approach: phased vertical slices with explicit learning goals, common-error analysis, automated tests, and exit criteria. See `IMPLEMENTATION_ROADMAP.md`.

## Actors

### Learner

- Takes an individual reading passage or a full reading test.
- Chooses practice mode or exam simulation mode.
- Submits answers and receives raw score, converted band score, explanations, and performance statistics.
- Creates personal vocabulary notes from external words, passage text, or a previously completed passage.
- Creates categories and assigns a vocabulary note to one or more categories.

### Administrator

- Manages users and their active status.
- Creates and maintains passages, question groups, questions, answers, explanations, and full reading tests.
- Reviews and publishes learning content.
- Initial content entry is manual. Structured bulk import may later be supported through a controlled Excel, CSV, or JSON template.
- External APIs or automated content retrieval have not yet been selected.

## Learner workflow

### Content selection

A learner selects either:

- One independent passage; or
- One full test containing multiple passages.

### Practice mode

- No mandatory time limit.
- Answers are saved during the attempt.
- The learner may select text in the passage, look up a word, and save it to personal vocabulary notes.
- The learner may also save vocabulary while reviewing a completed passage.

### Exam simulation mode

- A full IELTS Reading simulation uses one 60-minute timer for the complete three-section test.
- Dictionary lookup and saving highlighted vocabulary are unavailable until submission.
- Answers are saved during the attempt.
- The system automatically submits the session when time expires.
- A timed single-passage activity may be supported as a product practice feature, but it must not be presented as the official full-test timing format.

### Scoring and review

- Each correctly answered question contributes one raw point.
- Raw score is converted to an IELTS band score.
- The first release supports IELTS Academic Reading only and uses an explicit, testable Academic raw-score-to-band table.
- After submission, the learner may review submitted answers, correct answers, explanations, and performance by question type.

## Session interpretation

- `TEST_SESSIONS` exists only when a learner takes a full reading test.
- `TEST_SESSIONS` stores the full-test mode and lifecycle status.
- `QUIZ_ATTEMPTS` represents work on an individual passage and may exist independently.
- A full test session contains multiple passage attempts.
- An individual passage activity creates a standalone attempt and does not create a test session.
- Every attempt belongs directly to a user. Its test-session reference is optional and is populated only when the attempt is part of a full test.
- `ANWERS_QUESTION` should be renamed to `USER_ANSWERS` when the model is revised.

## Content authoring workflow

1. Create a draft passage or full test.
2. Enter passage content.
3. Create question groups and their instructions.
4. Add questions, valid answers, and explanations.
5. Preview and validate question counts and scoring behavior.
6. Publish the content.

Published content and completed learner attempts require change-control rules so later content edits do not silently alter historical results.

## Current model areas

The supplied CDM XML contains the following principal entities:

- `USERS`
- `CATEGORIES`
- `VOCABULARY_NOTES`
- `VOCABULARY_ENTRIES`
- `VOCABULARY_PHONETICS`
- `VOCABULARY_MEANINGS`
- `VOCABULARY_RELATED_WORDS`
- `PASSAGES`
- `QUESTION_GROUPS`
- `PASSAGE_QUESTIONS`
- `OPTION_QUESTIONS`
- `BLANK_QUESTIONS`
- `MATCHING_QUESTIONS`
- `READING_TESTS`
- `TEST_SESSIONS`
- `QUIZ_ATTEMPTS`
- `ANWERS_QUESTION`
- `USER_QUESTION_TYPE_STATS`

Further CDM images will be reviewed against the original PowerDesigner XML before PostgreSQL data types and final cardinalities are selected.

