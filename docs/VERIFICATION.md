# Executed verification report

Recorded 2026-10-17 (UTC). These are executed local measurements, not fabricated seed statistics.

## Environment and results

- Java 21.0.6 (Azul), Spring Boot 3.5.16, Maven 3.9.9.
- Real PostgreSQL 16.15 and Redis 7.0.15 from Ubuntu packages, run directly in an isolated validation environment. **These differ from the Docker image patches** (PostgreSQL 16.9 / Redis 7.4.4); container parity remains unverified.
- Frontend TypeScript compilation + Vite production build: PASS.
- Backend compilation/package: PASS.
- JUnit: **21 passed, 0 failed, 0 errors, 0 skipped**, using a separate real PostgreSQL test database. Evidence: verification/backend-results.xml.
- Playwright Chromium: **5 passed**, including C4 coordinate accuracy, refresh recovery, hold→checkout→booking persistence, stale-seat conflict, short-hold expiration, responsive layouts and live numeric lab reports. Evidence: verification/browser-results.json.
- **Two actual Java processes**: competing holds produced exactly one 200 and one 409; a hold and its bearer token survived a process restart; a confirmed booking survived another process restart. Evidence: verification/process-results.txt.
- External Python public-API generator: **12 attempts / 6 workers / one identity / one seat**, one successful booking, 11 expected conflicts. This includes user-active-hold limits. Evidence: http-load-results.json.
- Direct SQL after experiments: **0 oversold seats, 0 inventory/booking mismatches**. Evidence: db-verification.txt.
- Runtime OpenAPI generated and fetched with authentication: docs/openapi.json.
- Browser page errors: none in the main journey. Server logs checked: no remaining ERROR/exception entries after the SSE async authorization correction. Graceful shutdown may wait for the configured 5-second limit while an SSE connection is open, then terminate that connection.

## Persisted lab measurements

Each run used 100 attempts and 12 workers, invoking the actual reservation service against isolated synthetic events. All six runs completed and were VERIFIED, with zero infrastructure errors. The duplicate scenario intentionally replays one checkout rather than acquiring a new hold each time.

| Scenario | Attempts | Holds | Bookings | Booked seats | Expected conflicts | p95 ms | Violations |
|---|---:|---:|---:|---:|---:|---:|---:|
| SINGLE | 100 | 1 | 1 | 1 | 99 | 35 | 0 |
| POOL | 100 | 4 | 4 | 4 | 96 | 65 | 0 |
| OVERLAP | 100 | 1 | 1 | 2 | 99 | 13 | 0 |
| EXPIRATION | 100 | 5 | 4 | 4 | 96 | 18 | 0 |
| CHECKOUT_RACE | 100 | 5 | 4 | 4 | 96 | 26 | 0 |
| DUPLICATE | 100 | 1 | 1 | 1 | 0 | 38 | 0 |

Raw durations, p50/p95/p99, throughput, event identifiers, status and other counters are in lab-results.json. These short runs measure the local service path; they exclude HTTP transport and do not establish internet-scale capacity. The UI browser test ran one additional independent lab experiment; it is not included in the six-run table.

The expiration scenarios use a DB-time expiration fixture on isolated test holds. CHECKOUT_RACE in the UI demonstrates rejection at an expired boundary; the **actual synchronized expiration-versus-checkout and release-versus-checkout races** are JUnit tests. Restart and multi-process scenarios are external process checks, not in-process UI actions.

## Coverage and material limits

Executed tests cover atomic multi-seat failure, 24 simultaneous one-seat holds, expired checkout rejection, reallocation without cleanup, hold ownership, duplicate callback/submission, incompatible idempotency payload, decline, timeout, late-payment compensation, hold limits, event isolation, release/expiration races, separate service instances and API role/report contracts. The process checks additionally use real JVMs.

**Docker is unavailable in the execution environment.** `docker compose up --build`, image pulls/builds, Docker health checks and tests through compose.test.yaml were **not executed**. The Compose files parse as valid YAML, but this is not equivalent to running containers. The documented default command therefore needs its first Docker validation on your machine.

The original SeatLock reference image was not included. The interface follows the written design. Direct screenshot-to-reference matching is **unverified**. Desktop/tablet/mobile screenshots come from the actual React application. Short-hold test runs use a deliberately reduced two-second duration; normal startup defaults to five minutes.

No cloud deployment, HTTPS termination, k6 execution, distributed production-scale load, real payment, cancellation, hold extension, real user registration or production identity administration was verified or is claimed. The Python external generator was executed; k6 is an alternate provided script. Process checks create disposable ordinary fixture events; in-app lab events are isolated and hidden from the catalogue.

The source uses an intentionally coarse event mutation lock. PostgreSQL is authoritative, but this code has not undergone an independent security or production-readiness audit. Public deployment should disable the lab and provision private credentials.

## Version provenance

Spring Boot 3.5.16 was resolved from Maven Central and executed on Java 21. Official compatibility reference: https://docs.spring.io/spring-boot/3.5/system-requirements.html . The frontend lockfile and pinned Maven parent define the shipped dependency graph. Docker versions are explicit tags, not immutable image digests.

## Clean extraction

**PASS:** extracted the source ZIP into a clean directory, ran `./mvnw -o -B package` through Maven Wrapper (21 tests passed), installed frontend dependencies using `npm ci --offline`, and ran the production frontend build. Started the extracted backend and frontend, completed a two-seat hold → checkout → persisted booking, and reran the responsive browser screenshot check with the default 300-second hold duration.

Direct SQL in that clean run returned zero for all five checks: oversold event-seats, booked inventory mismatches, overlapping live hold claims, partial booked holds, and payment/booking mismatches. Evidence: verification/clean-build.txt and verification/clean-db-invariants.txt. The final screenshots show the default five-minute window and actual booked/selected seat states.

Final packaging changes after extraction were documentation/evidence updates only. Docker execution remains unverified regardless of these source builds.
