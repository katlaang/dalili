# Service Outage Runbook

Use this when the frontend or backend is unavailable.

## Step 1: Identify The Failed Layer

1. Does the frontend URL load?
2. Does `https://api.example.com/health` return `OK`?
3. Does `https://api.example.com/audit/health` return JSON?

## Step 2: Check Containers

```bash
docker compose --env-file .env -f docker-compose.vm.yml ps
docker compose --env-file .env -f docker-compose.vm.yml logs --tail=100 caddy
docker compose --env-file .env -f docker-compose.vm.yml logs --tail=100 app
docker compose --env-file .env -f docker-compose.vm.yml logs --tail=100 db
```

## Step 3: Check VM Capacity

```bash
free -h
df -h
docker stats --no-stream
```

Common failure modes:

- Out of memory -> app or database restarted
- Disk full -> Postgres or Docker cannot write
- Caddy failure -> TLS/proxy issue, backend may still be healthy internally
- Database unhealthy -> app returns 5xx on protected routes

## Step 4: Backend-Specific Checks

```bash
curl -f https://api.example.com/health
docker compose --env-file .env -f docker-compose.vm.yml exec db \
  pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

## Step 5: Safe Restart

If configuration and disk look healthy:

```bash
docker compose --env-file .env -f docker-compose.vm.yml restart app
docker compose --env-file .env -f docker-compose.vm.yml restart caddy
```

If the database is the problem, investigate before restarting it blindly.

## Escalate

Escalate if:

1. `tamperDetected` is true
2. database restore may be required
3. rollback is required
4. repeated OOM or disk failures continue after restart
