# Backend Runbook: Cheap Ubuntu VM

This runbook deploys the backend and PostgreSQL to one cheap Ubuntu VM using Docker Compose and Caddy.

## Target

- Ubuntu VM with at least 2 GB RAM
- Example providers: Hetzner, Vultr, Lightsail
- Public DNS name such as `api.example.com`

## Prerequisites

1. Ubuntu VM created
2. DNS `A` record for `api.example.com` pointing to the VM
3. Ports open:
    - `22/tcp`
    - `80/tcp`
    - `443/tcp`
4. This repository cloned onto the VM

## Prepare The VM

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git ufw
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
newgrp docker
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable
```

## Prepare The App

From the repository root on the VM:

```bash
cp .env.vm.example .env
```

Edit `.env` and set at minimum:

- `APP_DOMAIN`
- `POSTGRES_PASSWORD`
- `DALILI_JWT_SECRET`
- `DALILI_SECURITY_ALLOWED_ORIGINS`
- `DALILI_AUDIT_DEVICE_ID`

## First Deploy

Build the backend jar and start the stack:

```bash
./gradlew bootJar
docker compose --env-file .env -f docker-compose.vm.yml up -d --build
```

## Notes On TLS

Caddy obtains certificates automatically for `APP_DOMAIN`.

For the first certificate issuance:

1. Keep the `api.example.com` DNS record DNS-only
2. Wait for Caddy to obtain the certificate
3. After that, you may enable proxying/CDN features if you want

## Health Checks

```bash
curl -f https://api.example.com/health
curl -f https://api.example.com/audit/health
docker compose --env-file .env -f docker-compose.vm.yml ps
docker compose --env-file .env -f docker-compose.vm.yml logs --tail=100 app
```

## Routine Commands

```bash
docker compose --env-file .env -f docker-compose.vm.yml pull
docker compose --env-file .env -f docker-compose.vm.yml up -d --build
docker compose --env-file .env -f docker-compose.vm.yml logs -f app
docker compose --env-file .env -f docker-compose.vm.yml logs -f caddy
docker compose --env-file .env -f docker-compose.vm.yml logs -f db
```
