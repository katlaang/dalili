# Post-Deploy Verification Runbook

Run this after every frontend or backend deployment.

## Backend Checks

```bash
curl -f https://api.example.com/health
curl -f https://api.example.com/audit/health
docker compose --env-file .env -f docker-compose.vm.yml ps
docker compose --env-file .env -f docker-compose.vm.yml exec db \
  pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

Expected:

- `/health` returns `OK`
- `/audit/health` returns JSON without `tamperDetected: true`
- all containers are `Up`
- PostgreSQL reports `accepting connections`

## Frontend Checks

1. Open the Cloudflare Pages URL or custom domain
2. Confirm the login screen renders
3. Open browser developer tools and confirm API calls go to `https://api.example.com`
4. Confirm there are no CORS or mixed-content errors

## Functional Checks

1. Staff login succeeds
2. A protected API call succeeds after login
3. If kiosk flow is enabled, kiosk login works with the configured device ID and secret

## Sign-Off

Mark the deployment successful only if:

1. Health endpoints pass
2. Frontend loads
3. Login works
4. Database is healthy
