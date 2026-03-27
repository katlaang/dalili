# Frontend Runbook: Cloudflare Pages

This runbook deploys the Expo web frontend to Cloudflare Pages.

## Prerequisites

1. Backend API already reachable over HTTPS at `https://api.example.com`
2. Cloudflare account
3. Frontend code in this repository under `frontend/`

## Local Validation

From `frontend/`:

```bash
npm install
npm run export:web
```

Expected result:

- `frontend/dist` exists
- export completes successfully

## Cloudflare Pages Settings

Recommended configuration:

- Project root directory: `frontend`
- Build command: `npm ci && npm run export:web`
- Build output directory: `dist`
- Production branch: your main deployment branch

## Required Environment Variables

Set this in Cloudflare Pages:

```text
EXPO_PUBLIC_API_BASE_URL=https://api.example.com
```

Optional:

```text
EXPO_PUBLIC_KIOSK_DEVICE_ID=kiosk-front-desk-1
EXPO_PUBLIC_KIOSK_DEVICE_SECRET=kiosk-secret-change-me
```

## Custom Domain

Attach your frontend domain, for example:

- `app.example.com`

## Deploy

### Git-based deploy

1. Connect the repository in Cloudflare Pages
2. Apply the build settings above
3. Add the required environment variables
4. Trigger the first deployment

### Manual deploy

From `frontend/`:

```bash
npm install
npm run export:web
```

Upload the generated `dist/` folder to Cloudflare Pages.

## Success Criteria

1. The Pages deployment finishes successfully
2. The frontend loads in a browser
3. Login requests go to `https://api.example.com`
4. Browser developer tools show no mixed-content errors
