# Deployment

Use a Linux server with Docker Engine and Compose v2, then run the same compose.yaml. Start resource planning around 2 vCPU and 4 GB RAM with enough disk for database/backups; this is an unmeasured planning assumption, not a tested capacity promise. Maven builds may require more memory. Build images in CI if the server is small.

1. Set unique DB_PASSWORD and DEMO_PASSWORD **before first startup**. Protect .env on the host and keep it outside source control. Seed accounts are an intentionally local demo convenience; remove/rotate them for public use. Add a real identity lifecycle for production.
2. Set LAB_ENABLED=false for a public demo. Limits in this implementation are 300 attempts, 24 workers, 60 seconds and one global job. Do not expose a public operator credential. A real public service needs lower limits, per-tenant quotas and authentication hardening.
3. Place Caddy, Nginx or a cloud load balancer in front of port 3000 with a domain and HTTPS. Only expose 443/80; firewall 3000 to your proxy. Keep DB, Redis, backend and actuator internal.
4. Route frontend and `/api` on the same origin. Disable proxy response buffering/cache for SSE, use HTTP/1.1 upstream, read/idle timeout at least 660 seconds. The app reconnects and refreshes after stream loss; sticky sessions are not required for tokens/inventory, but streaming connections stay with their initial process.
5. Store pgdata on durable disks. Make regular `pg_dump` backups; encrypt and copy off-host. Test restore into a new database before relying on backups. Redis is non-authoritative and need not be backed up.
6. Keep tokens/DB password in a secret manager, rotate credentials explicitly, restrict outbound access and patch dependencies/images. Pin image digests in your own registry after your platform build validation; current version tags are pinned but not immutable digests.
7. Scrape authenticated metrics internally. Monitor DB connections/lock waits, callback backlog, error rates, expiry jobs and disk. Do not expose Prometheus or Swagger without access controls.
8. Deploy migrations once with Flyway's schema lock. The current idempotent demo seeding assumes a normal sequential initial startup; provision initial seed data before scaling replicas. Lab jobs use an in-process executor and do not migrate across replicas. Keep lab disabled in a horizontally scaled public deployment.

An optional AWS layout: ALB + ACM HTTPS → ECS/Fargate frontend/backend services; private RDS PostgreSQL with automated backups; private ElastiCache Redis; Secrets Manager; CloudWatch logs/alarms. Allow backend→DB/Redis security groups only. Test ALB idle timeouts and SSE reconnection. Set DB_URL using the managed endpoint and TLS parameters (`sslmode=verify-full`, trusted CA). Implement production account provisioning instead of seeded shared credentials.

Vercel/Netlify/static object hosting alone cannot run the Java backend, PostgreSQL and Redis. They could host the frontend only with API routing/CORS/auth changes. The default deployment deliberately keeps everything on one origin.

## Backups and upgrade examples

```bash
docker compose exec -T postgres pg_dump -U seatlock seatlock > seatlock-backup.sql
# Back up .env securely, separately from source control.
docker compose build
docker compose up -d
```

Do not pipe an unreviewed dump into a live database. Restore to a clean target and verify booking/seat counts before switching traffic. Never use `down --volumes` during an upgrade.
