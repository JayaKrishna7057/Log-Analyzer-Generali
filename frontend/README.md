# Log Analyzer — frontend

React 19 + Vite. Upload form and report view for the analyzer API.

See the [project README](../README.md) for the full picture.

## Running

```bash
npm install
npm run dev      # http://localhost:5173
```

The backend must be running too, or uploads fail with "Could not reach the analysis server".

```bash
mvn -f ../backend/pom.xml clean package
java -jar ../backend/target/log-analyzer-backend-1.0.0.jar
```

## Scripts

| Command | Does |
|---|---|
| `npm run dev` | Dev server with hot reload |
| `npm run build` | Production bundle into `dist/` |
| `npm run preview` | Serve the built bundle |
| `npm run lint` | oxlint |

## Backend URL

Defaults to `http://localhost:8080/api`. To point elsewhere, copy `.env.example` to `.env.local`:

```
VITE_API_BASE_URL=https://analyzer.example.com/api
```

The backend must also allow the origin you serve this from — see `app.cors.allowed-origins` in
`backend/src/main/resources/application.properties`.

## Structure

| Path | Holds |
|---|---|
| `src/api/logAnalyzerApi.js` | Backend client. Retries once on a network failure, since a briefly unreachable backend surfaces as a generic `TypeError`. |
| `src/components/FileUpload.jsx` | File picker. Sorts the selection into stdout/stderr by filename — exact `stdout`/`stderr` first, so `stdout_errors.log` is not read as stderr. |
| `src/components/ReportView.jsx` | The report: summary tiles, layer tables, issues with `file:line` references, failure details. |
| `src/utils/csv.js` | CSV export, with the `sep=,` directive and a BOM so Excel opens it correctly whatever the locale. |
