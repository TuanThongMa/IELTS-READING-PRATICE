# IELTS Academic Reading Practice

Project source for the IELTS Academic Reading practice system. Product and data decisions are recorded in [`docs/`](docs/).

## Local foundation

- Frontend: `cd frontend` then `npm install` and `npm run dev`
- Backend and PostgreSQL: `docker compose up --build`
- Health check: `http://localhost:8080/actuator/health`

Docker Compose supplies the backend database connection using the same `POSTGRES_DB`, `POSTGRES_USER`, and `POSTGRES_PASSWORD` values as PostgreSQL. The defaults are for local development only. Flyway runs migrations from `backend/src/main/resources/db/migration` when the backend starts. For pgAdmin, connect to `localhost:5433` using the configured database and credentials. Change the schema through new migration files, not manual pgAdmin edits; never edit a migration already applied to a persistent database.

The development frontend uses Vite's proxy for `/auth` and `/me` so requests and session cookies share the browser origin. Visit the URL printed by Vite. Registration creates a learner account; login uses a server session and CSRF token. For HTTPS deployments set `SESSION_COOKIE_SECURE=true`; deployment hardening, login rate limiting, password reset, and administrator controls remain future work.

Reading, attempt, vocabulary, and administration use cases will be introduced incrementally with tests.
