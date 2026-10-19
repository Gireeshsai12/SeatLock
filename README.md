# SeatLock — Reservation Engine

Java 21 / Spring Boot / React + TypeScript / PostgreSQL / Redis.

A runnable reservation application and bounded concurrency laboratory. Fictional events, local simulated payments, real persisted reservations. No payment account is needed.

## Start on Windows PowerShell

Install Docker Desktop, enable its WSL 2 engine, and **open Docker Desktop until the engine is running**. `docker version` must show both Client and Server. A `docker` command-not-found error means installation/PATH is incomplete. A `docker_engine` pipe error means the engine is not running. Extract the entire ZIP, then:

```powershell
cd E:\seatlock
Copy-Item .env.example .env
docker compose up --build
```

Use the actual extracted folder containing `compose.yaml` (some unzip tools create an extra folder). Open **http://localhost:3000** once the frontend is healthy. Initial Maven/npm/image downloads need internet and may take several minutes.

## Start on macOS / Linux

```bash
cd seatlock
cp .env.example .env
docker compose up --build
```

Only port **3000** is published. PostgreSQL 5432, Redis 6379, backend 8080 remain internal. Change WEB_PORT if 3000 is occupied.

## Demo accounts

| Role | Email | Local demo password |
|---|---|---|
| User | user@seatlock.local | SeatLock-demo-2026! |
| Operator | operator@seatlock.local | SeatLock-demo-2026! |
| Admin | admin@seatlock.local | SeatLock-demo-2026! |

These are fictional credentials for local use. DEMO_PASSWORD affects first seed only; changing it does not overwrite existing users' passwords. Rotate credentials explicitly before any public deployment. Sign-in tokens expire in eight hours and survive backend restarts in PostgreSQL; the browser keeps its token in sessionStorage.

## Try it

1. Sign in as operator. Browse an event and choose seats, for example C4.
2. Click **Hold selected seats**. Refresh to recover the hold.
3. Choose SUCCESS under **Simulated checkout** and complete checkout.
4. Open **My bookings** to see the persisted SL reference.
5. Run a one-seat laboratory experiment. Requests will mostly conflict; this is expected.
6. Inspect the measured report, Operations, and audit Logs.

One active hold per user, six seats per hold, default 300 seconds. Release an existing hold before another event. Expired holds are logically available immediately, regardless of cleanup. Cancellation and hold extensions are intentionally unsupported.

## Stop, restart, inspect

```bash
docker compose ps
docker compose logs --tail=100 backend
docker compose stop
docker compose start
```

`docker compose down` removes containers and preserves pgdata. **Optional destructive reset** (deletes all demo bookings):

```bash
docker compose down --volumes
```

Never use the reset command to fix a normal startup error. If a build fails, inspect logs and retry `docker compose up --build`. Keep Docker's disk allocation sufficient; removing unrelated volumes is not required. All dependencies are pinned in build files/lockfile; image tags are explicit versions rather than `latest`.

## Tests

Real PostgreSQL integration tests, isolated from the application database:

```bash
docker compose -f compose.test.yaml up --build --abort-on-container-exit --exit-code-from tests
docker compose -f compose.test.yaml down --volumes
```

Browser tests against a running app require Node 22:

```bash
cd frontend
npm ci
npx playwright install chromium
```

PowerShell:

```powershell
$env:BASE_URL="http://localhost:3000"
npm run test:e2e
```

macOS/Linux: `BASE_URL=http://localhost:3000 npm run test:e2e`.

The expiration browser test is deliberately skipped unless SHORT_HOLD=1. Run a separate disposable demo with HOLD_SECONDS=2, then enable that variable. Do not shorten an existing active production hold. The two-instance/restart script is in `tests/process_checks.py`; its local prerequisites and usage are documented there. See `docs/VERIFICATION.md` for precisely executed checks and remaining gaps.

## Without Docker (development)

Install Java 21, PostgreSQL 16, Redis 7 and Node 22. Create seatlock database/user/password matching application.yml, or set DB_URL / DB_USER / DB_PASSWORD. Start PostgreSQL and Redis, then:

```bash
cd backend
./mvnw spring-boot:run
# Windows: .\mvnw.cmd spring-boot:run
```

In a second terminal:

```bash
cd frontend
npm ci
npm run dev
```

Open http://localhost:5173. Vite proxies /api to localhost:8080. Maven Wrapper downloads Maven 3.9.9. Do not commit .env files.

## Documentation

- [Architecture and schema](docs/ARCHITECTURE.md)
- [API examples](docs/API.md); live authenticated OpenAPI: `/v3/api-docs` on backend port 8080 (not exposed by default proxy).
- [Deployment](docs/DEPLOYMENT.md)
- [Demo and interview guide](docs/DEMO-INTERVIEW.md)
- [Executed verification and limitations](docs/VERIFICATION.md)
- [License](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md)

The written visual specification was available; the original SeatLock reference image was not attached. Screenshots are of the real running React application, when execution is recorded in the verification report. This is a portfolio demo, not an audited payment or ticketing platform.

External HTTP load generator (Python 3, no extra packages): `python load/http_contention.py --base http://localhost:3000 --event EVENT_UUID --tokens-file tokens.json`. Supply a JSON array of valid demo bearer tokens in a temporary local file; do not commit it. It uses normal public APIs, so user/rate limits are included. An alternative k6 script is in `load/contention.js`. Both require an isolated event; neither resets data. Authoritative read-only verification queries are in `tests/verify_invariants.sql`.
