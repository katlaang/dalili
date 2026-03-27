# Database Backup And Restore Runbook

This runbook assumes the backend VM uses `docker-compose.vm.yml`.

## Backup

From the repository root on the VM:

```bash
set -a
source .env
set +a

mkdir -p backups
timestamp=$(date +%Y%m%d-%H%M%S)
docker compose --env-file .env -f docker-compose.vm.yml exec -T db \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" > "backups/dalili-$timestamp.sql"
```

Verify the file exists and is non-empty.

## Restore

Warning: this overwrites the current database contents.

1. Take a fresh backup first
2. Stop application writes if possible

```bash
set -a
source .env
set +a

cat backups/<backup-file>.sql | \
  docker compose --env-file .env -f docker-compose.vm.yml exec -T db \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

## Safer Restore Workflow

If you need a cleaner restore:

1. Stop the app container
2. Recreate the database
3. Restore the dump
4. Start the app container
5. Run the post-deploy verification runbook
