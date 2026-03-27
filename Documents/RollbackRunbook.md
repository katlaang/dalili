# Rollback Runbook

Use this if a frontend or backend deploy introduces a regression.

## Important Warning

The backend currently uses `spring.jpa.hibernate.ddl-auto=update`.
That means backend rollback is not always schema-safe.

Before rolling back the backend:

1. Take a database backup
2. Confirm the target commit is compatible with the current schema

## Frontend Rollback

### Cloudflare Pages

1. Open the Pages project
2. Find the last known-good deployment
3. Promote or redeploy that version

If you deploy from Git, reverting the offending commit and pushing is usually cleaner.

## Backend Rollback

From the repo on the VM:

```bash
git log --oneline -n 10
git checkout <known-good-commit>
./gradlew bootJar
docker compose --env-file .env -f docker-compose.vm.yml up -d --build
```

Then run the post-deploy verification runbook.

## If Rollback Fails

1. Restore the database from backup if schema drift is the issue
2. Redeploy the last known-good frontend
3. Keep the system in maintenance mode until verification passes
