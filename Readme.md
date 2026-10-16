# IELTS Academic Reading Practice

Project source for the IELTS Academic Reading practice system. Product and data decisions are recorded in [`docs/`](docs/).

## Local foundation

- Frontend: `cd frontend` then `npm install` and `npm run dev`
- Backend and PostgreSQL: `docker compose up --build`
- Health check: `http://localhost:8080/actuator/health`

The first implementation increment intentionally contains only the runnable foundation. Reading, attempt, vocabulary, and administration use cases will be introduced incrementally with tests.
