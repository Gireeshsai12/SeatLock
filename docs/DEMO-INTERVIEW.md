# Three-minute demo

0:00–0:30 — Sign in as operator, point out fictional events and the real seat coordinates. Explain PostgreSQL authority.

0:30–1:05 — Select C4 and C5, acquire a hold, refresh and show recovered seats and server countdown. Explain logical expiry does not need cleanup.

1:05–1:35 — Choose DUPLICATE checkout, confirm once, and revisit My bookings. Explain user-scoped key and unique payment/booking constraints.

1:35–2:20 — Run SINGLE with 100 attempts / 12 workers. Show actual attempts, expected conflicts, successful holds, bookings, p95 and invariant verification. A finite one-seat event should have one owner; most requests must fail normally.

2:20–3:00 — Show audit activity and event editing guard. Explain coarse event lock tradeoff, reconnect snapshot behavior and the distinction between a local measurement and production capacity.

# Interview guide

**Why PostgreSQL instead of a Redis lock?** The seat row and booked-seat uniqueness survive restarts and coordinate JVMs. Redis expiry cannot atomically commit a booking transaction with the database.

**Why lock the event as well as seats?** Event locking serializes version updates and simplifies verification and callbacks. It reduces throughput for one hot event. A future version could use only ordered seat locks and atomic version increments/outbox publication, with a documented user/checkout lock order.

**How is multi-seat atomicity guaranteed?** Validate and mutate all sorted seat rows in a transaction. One conflict rolls back the entire hold.

**What happens to expired rows?** Logical availability compares hold expiry with database time. Historical references can remain until cleanup without granting an invalid owner checkout rights.

**Why not make the browser timer authoritative?** Browsers have clock skew and can disconnect. The UI uses an offset from serverTime; the transaction checks clock_timestamp after lock acquisition.

**What if payment is slow?** Simulated attempts persist a ready_at deadline then release locks. A worker completes them later in a fresh transaction. Real external providers require durable webhooks, signature checking, provider idempotency and explicit recovery/refund orchestration; this demo does not implement a real provider.

**What if the callback arrives twice?** The locked checkout row leaves PENDING only once; unique booking source-hold/payment-attempt constraints add protection.

**What if a key is reused differently?** Compare the stored hold and mode and return IDEMPOTENCY_CONFLICT. Keys are scoped to a user.

**What if release races with checkout?** Both use the same locks. One transition wins; the other sees the terminal/invalid state. No stale client can overwrite it.

**What is 'oversold'?** More than one booking-item claim for the same event-seat. The unique key prevents this. Lab verification also checks inventory points at the booking item and booked holds have exactly their requested seats.

**Are reported conflicts failures?** They are expected outcomes under contention. Infrastructure errors or incomplete runs must not receive VERIFIED status. Distinguish attempts, holds, bookings and booked seats.

**How fresh is the UI?** Approximately one-second committed snapshots, reconnect resync and nondecreasing versions. This is eventual display consistency; checkout still revalidates against locked authoritative state.

**What remains for production?** Real identity administration, immutable image digests, dependency security review, TLS/secrets/backups, durable distributed job orchestration, stronger abuse controls, and load tests on the intended infrastructure.

# Résumé bullets

Use only measurements actually recorded in VERIFICATION.md. Safe implementation statements:

- Built a Java 21/Spring Boot and React reservation demo with PostgreSQL transactions, ordered inventory locks, owner-authorized holds and user-scoped idempotent simulated checkout.
- Implemented persisted booking recovery, SSE snapshot reconnection, Redis request limits and an operator-only concurrency laboratory with database invariant checks and exportable run reports.

Append executed test counts or request totals only after checking the included verification report. Do not claim real payments, production traffic, millions of requests, or a performance improvement without a measured baseline.
