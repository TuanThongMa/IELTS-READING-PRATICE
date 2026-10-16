# Phase 3 — Technical Foundation Decision

Status: Approved by project owner on 2026-10-16  
Purpose: establish a runnable, testable MVP foundation without introducing infrastructure that the launch scope does not need.

## 1. Chosen stack

| Concern | Decision | Reason for this project |
|---|---|---|
| Web client | React 19, TypeScript, Vite, React Router | A desktop-first interactive reader is naturally client-rendered; this is a small, typed client without SSR or a second application server. |
| Frontend styling | CSS Modules plus Reading Desk design tokens | Keeps the agreed visual system explicit and avoids a component-library dependency that would impose an unrelated visual language. |
| Backend | Java 21 LTS, Spring Boot 4.1, Spring MVC | Conventional REST development, validation, security, and test tooling are appropriate for a thesis and personal launch. |
| Architecture | modular monolith with lightweight Clean Architecture boundaries | Feature modules own their use cases; HTTP, JPA, PostgreSQL, and external services stay at the edge. It demonstrates architecture without the operational cost of microservices. |
| Persistence | PostgreSQL, Spring Data JPA, Flyway | Matches the approved relational PDM and makes schema history reproducible. |
| Authentication | Spring Security server session in an `HttpOnly`, `SameSite=Lax` cookie with CSRF protection | The SPA and API are deployed from one origin. This avoids prematurely adding refresh-token/JWT lifecycle complexity. |
| Local runtime | Docker Compose | Starts a predictable PostgreSQL service and backend build without requiring a machine-wide Maven installation. |
| Verification | JUnit 5 + Testcontainers PostgreSQL; Vitest/Testing Library; focused Playwright journeys | Provides evidence for the thesis while keeping end-to-end coverage focused on the learner-critical flows. |
| Operations | Actuator health endpoint, OpenAPI description, structured application logs | Supplies practical diagnostics and API evidence for review and launch. |

## 2. Runtime compatibility

The current development machine has Node 22, Docker Compose, Git, and Java 26 available, but no Maven command. The application targets Java 21 LTS and uses a containerized JDK 21/Maven build. Local development therefore does not depend on Java 26 or a global Maven installation.

Vite's supported React TypeScript template and the installed Node version support this frontend choice. Spring Boot 4.1 supports Java versions 17 through 26; Java 21 is selected as the long-term-support deployment target rather than treating the local Java 26 feature release as a production requirement.

## 3. Explicit exclusions

The launch MVP does not introduce Next.js/SSR, microservices, Kafka, Redis, Kubernetes, GraphQL, a mobile app, or a generic UI-kit dependency. Each would add configuration, operations, or a second architectural model without serving the approved Reading learner journey.

## 4. Module boundary plan

```text
backend
  identity       authentication, users, roles
  reading        published tests/passages and active-reader views
  attempts       timing, answers, flags, submission, scoring
  vocabulary     learner-owned notes and categories
  administration content publication and user management
  shared         errors, auditing, identifiers, clock

frontend
  app            routing, providers, application shell
  features       the same learner/admin capabilities
  shared         API client, accessible primitives, tokens
```

Each feature exposes application operations and persistence ports inward. Controllers and JPA adapters translate into those operations; no controller owns scoring, attempt lifecycle, or publication rules.

## 5. Phase 3 first increment

1. Establish the `Main-Code` Git branch and repository layout.
2. Add Docker Compose for PostgreSQL and a Spring Boot health endpoint.
3. Add a React/Vite shell with a single route and Reading Desk token baseline.
4. Prove local startup, health response, frontend build, and baseline automated checks.

No domain table, business endpoint, or screen beyond the shell is added in this increment. Those arrive only after the foundation is verified.

## 6. Review convention

When the project owner asks for a code review, explain the requested change in this order: intent, changed files, execution flow, how to run or verify it, and any deliberate omission. Prefer deletion and the smallest viable implementation over speculative abstraction.
