# Log Analyzer Generali

Upload a job's `stdout` / `stderr` and get back what actually happened: which units ran, what
failed, and where to look in the raw file.

Spring Boot 3.3 API + React 19 frontend. Frontend and backend only — no Docker, no deployment
config.

## Requirements

- **JDK 21**
- **Maven 3.9+**
- **Node 20+**

## Running it

### One command (VS Code)

Open this folder in VS Code and run the default build task — **Terminal → Run Build Task**
(`Ctrl+Shift+B`). It starts the backend (`mvn -f backend spring-boot:run`) on
`http://localhost:8080` and the frontend (`npm run dev` in `frontend/`) on
`http://localhost:5173` together, each in its own terminal panel.

First time only: install frontend dependencies before running the task.

```bash
cd frontend && npm install
```

### Manually

**Backend** — starts on `http://localhost:8080`

```bash
mvn -f backend/pom.xml spring-boot:run
```

**Frontend** — starts on `http://localhost:5173`

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173 and upload a log.

## Configuration

Backend, in `backend/src/main/resources/application.properties`:

| Property | Default | Purpose |
|---|---|---|
| `server.port` | `8080` | API port |
| `spring.servlet.multipart.max-file-size` | `50MB` | Per-file upload limit |
| `spring.servlet.multipart.max-request-size` | `100MB` | Total request limit |
| `app.cors.allowed-origins` | `http://localhost:5173,http://127.0.0.1:5173` | Origins allowed to call the API |

Frontend: copy `.env.example` to `.env.local` to point at a different backend.

## Layout

```
backend/          Spring Boot API
  src/main/java/com/loganalyzer/
    controller/   endpoints and the shared error shape
    model/        response records
    service/
      profile/    one LogProfile per dialect, plus detection
      issue/      error classification and fingerprinting
frontend/         React + Vite
  src/
    api/          backend client
    components/   upload form and report view
    utils/        CSV export, history, theme
```

## Tests

```bash
mvn -f backend/pom.xml test     # backend
cd frontend && npm test         # frontend
cd frontend && npm run lint
```
