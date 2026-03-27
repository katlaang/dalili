# Secrets Rotation Runbook

This runbook covers the main secrets currently used by the app.

## JWT Secret

Impact:

- all existing sessions become invalid
- users must log in again

Steps:

1. Update `DALILI_JWT_SECRET` in `.env`
2. Rebuild and redeploy the backend:

```bash
./gradlew bootJar
docker compose --env-file .env -f docker-compose.vm.yml up -d --build
```

3. Verify staff login still works

## Database Password

Important:

Changing `POSTGRES_PASSWORD` in `.env` alone does not rotate the existing database password in a running Postgres
container.

Steps:

1. Back up the database
2. Enter Postgres:

```bash
set -a
source .env
set +a

docker compose --env-file .env -f docker-compose.vm.yml exec db \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

3. Run:

```sql
ALTER USER dalili WITH PASSWORD 'new-strong-password';
```

4. Update `.env` with the new password
5. Restart the app stack:

```bash
docker compose --env-file .env -f docker-compose.vm.yml up -d
```

## GROQ API Key

1. Update `GROQ_API_KEY` in `.env`
2. Restart the app:

```bash
docker compose --env-file .env -f docker-compose.vm.yml up -d
```

## Cloudflare Pages Frontend Variables

If `EXPO_PUBLIC_API_BASE_URL` changes:

1. Update it in Cloudflare Pages project settings
2. Trigger a frontend redeploy
3. Verify login requests hit the new backend URL
