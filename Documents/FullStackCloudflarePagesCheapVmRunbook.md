# Full-Stack Runbook: Cloudflare Pages + Cheap Backend VM

This is the recommended cheap full-stack deployment path for Dalili:

- Frontend on Cloudflare Pages
- Backend and PostgreSQL on one cheap Ubuntu VM
- HTTPS on the backend via Caddy

## Target Architecture

- `app.example.com` -> Cloudflare Pages frontend
- `api.example.com` -> cheap VM running Caddy -> Spring Boot app
- PostgreSQL runs privately inside Docker on the same VM

## Prerequisites

1. A domain you control
2. A cheap Ubuntu VM with at least 2 GB RAM
3. DNS access for:
    - `app.example.com`
    - `api.example.com`
4. Git access to this repository

## Recommended Order

1. Deploy the backend VM first
2. Verify `https://api.example.com/health`
3. Deploy the frontend to Cloudflare Pages
4. Set `EXPO_PUBLIC_API_BASE_URL=https://api.example.com`
5. Run the post-deploy verification runbook

## Files Used

- Backend env template: `.env.vm.example`
- Backend compose stack: `docker-compose.vm.yml`
- Backend TLS proxy: `deploy/caddy/Caddyfile`
- Frontend production env example: `frontend/.env.production.example`

## Runbooks To Follow

1. [Backend: Cheap VM](C:\Projects\Dalili health\dalili\Documents\BackendVmRunbook.md)
2. [Frontend: Cloudflare Pages](C:\Projects\Dalili health\dalili\Documents\FrontendCloudflarePagesRunbook.md)
3. [Post-deploy verification](C:\Projects\Dalili health\dalili\Documents\PostDeployVerificationRunbook.md)
