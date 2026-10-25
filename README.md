# Smart Attendance • Full-Stack Administrator Edition (v3)

Modern responsive Smart Attendance application with a **GitHub Pages or Vercel static frontend**, **Spring Boot 3.4 / Java 21 backend on Render**, **MongoDB Atlas administrator settings**, and a **Google Apps Script → Google Sheets data adapter**.

## What's included

- `frontend/index.html`: public attendance site, Bangla/English, light/dark theme, countdown, attendance list, responsive social footer, **ADMINISTRATOR** button.
- `frontend/admin.html`, `frontend/admin.js`: secure administrator login, integration settings, single/multi-class schedules, live Google Sheet tab browser with search, create, edit, delete and confirmation dialogs; automatic email notifications and an HTML receipt preview.
- `frontend/config.js`: backwards-compatible default `SCHEDULE_CONFIG` and `TIMER_CONFIG`. Actual live settings come from Spring Boot.
- `frontend/scripts/build.mjs`, `frontend/vercel.json`: Vercel frontend build and public API URL injection.
- `backend`: Spring Boot API + Spring Security JWT authentication, per-admin MongoDB configuration, encrypted Gmail App Passwords (AES-256-GCM), server-side Asia/Dhaka schedule checks, SMTP email, proxied Apps Script requests.
- `Code.gs`: Google Sheets editor and free-hosting-friendly MailApp email service. **REPLACE** the previous Google Apps Script deployment's source with this version; changes are required for the administrator functions and gateway secret.

## 1. Set up Google Sheet + Google Apps Script

1. Open your spreadsheet in Google Sheets. **Extensions → Apps Script**. Replace the old Apps Script code with the full `Code.gs` from this ZIP and save.
2. Open **Project Settings → Script Properties**. Add property `GATEWAY_SECRET` with a random secret of **at least 32 characters**. The administrator panel can generate one with its **Generate** button; copy it to Script Properties.
3. **Deploy → Manage deployments → Edit** an existing Web App deployment or create a new one. Select **New version**, **Execute as: Me**, **Who has access: Anyone**; deploy and copy its `/exec` URL. Ensure you've authorized permissions.
4. On the admin dashboard, enter the Script URL, Google Sheet URL and the **same gateway secret**. Save.
5. **Important:** Changing `Code.gs` does not update an existing deployment automatically. Deploy a **new version** each time the script changes.

**Email permission:** This script contains `MailApp.sendEmail()`. On first deploying/updating, review Google permission prompts and authorize sending emails from the Apps Script owner's Google account. For extra security, restrict access to that Google account; be mindful of Google's daily MailApp quota (Google currently lists 100 recipients/day for consumer accounts and 1,500 recipients/day for Google Workspace; quotas may change).

The Apps Script Web App is reachable by anyone with the URL, but every operation (including the public attendance list and writes) requires the server-side `GATEWAY_SECRET`. Never place that secret in `index.html`, frontend environment variables or your public GitHub repository.

## 2. MongoDB Atlas

Create a free MongoDB Atlas cluster, database user and allowed network access to permit Render connections. Copy the MongoDB connection string into Render as `MONGODB_URI`. **Do not commit this URI** into source code. Admin settings are stored in collection `admin_settings`, **keyed by the admin email**. They survive Render restarts and deployments. Each administrator has isolated stored settings. For the public site to show a particular admin's configuration, set `SITE_OWNER_EMAIL` to that admin email. Without this, it defaults to `ADMIN_EMAIL`.

## 3. Render — Spring Boot backend

Create **New → Web Service**. Connect the Git repository and set:

- **Root Directory:** `backend`
- **Runtime:** Docker
- **Dockerfile Path:** `./Dockerfile` (relative to backend root)
- **Instance:** free if eligible

Add these **Render backend** environment variables:

| Variable | Example | Purpose |
| --- | --- | --- |
| `MONGODB_URI` | `mongodb+srv://...` | Persistent MongoDB Atlas storage |
| `ADMIN_EMAIL` | `admin@example.com` | One admin login account |
| `ADMIN_PASSWORD` | long unique password | That admin's password (never on frontend) |
| `JWT_SECRET` | random 64+ character string | JWT signature key; at least 32 bytes |
| `MAIL_ENCRYPTION_KEY` | output of `openssl rand -base64 32` | Encrypts stored Gmail App Passwords; do not change after storing passwords |
| `FRONTEND_ORIGINS` | `https://my-attendance.vercel.app,http://localhost:3000` | Exact allowed frontend origins; comma-separated |
| `SITE_OWNER_EMAIL` | `admin@example.com` | Admin workspace used by the public site (optional) |
| `ADMIN_USERS_JSON` | `[ {"email":"staff@example.com","password":"unique-long-secret"} ]` | Optional additional admin accounts; set as valid JSON |

Render sets `PORT` automatically. Do not prefix any sensitive settings with `NEXT_PUBLIC_` or `VITE_`.

Check backend: `https://YOUR-RENDER-SERVICE.onrender.com/actuator/health` (should return `UP`).

### Email delivery and notification OFF behavior

1. Log into `admin.html` → **General Settings** → **Student Confirmation Emails**.
2. **Email Notifications OFF (default):** no automatic email is sent to any student. No SMTP attempt and no Apps Script MailApp attempt occurs. Students can still submit attendance.
3. **Google Apps Script mode (recommended on Render Free):** set **Enable automatic email notifications ON**, select **Google Apps Script**, optionally set a sender display name and save. Your Google Script deployment owner sends the confirmation (no custom SMTP Gmail or App Password is involved). Authorize MailApp in Apps Script when updating the deployment. This works via HTTPS between Render and Apps Script, even on Render Free.
4. **Gmail SMTP mode (requires paid Render or another host allowing outbound port 587):** select **Gmail SMTP**, enter a Gmail address, 16-character Google **App Password**, and display name, then save. Enable 2-Step Verification on the Google account and create a Google App Password. This App Password is **not** your normal account password. It is encrypted using AES-256-GCM and `MAIL_ENCRYPTION_KEY` before saving in MongoDB; the admin API only returns whether one is configured, not the password.
5. **Test:** click **Send test to my admin email** after saving the settings. The destination is always the currently logged-in administrator's email. Then submit a test attendance with a real email address. On success, student gets a branded email with date, class and masked ID.
6. **Email delivery failure:** Google Sheets submission stays saved. The public page shows attendance success and an additional warning if an attempted email fails. Never retry an attendance just because the email failed.

**Render restriction (verified October 2026):** Free Render Web Services block outbound SMTP ports 25, 465 and 587. Gmail App Password + SMTP **cannot work on Render Free**. Use Google Apps Script mode without App Password for a free deployment. See https://render.com/docs/free and https://render.com/changelog/free-web-services-will-no-longer-allow-outbound-traffic-to-smtp-ports.

**Note:** Google App Passwords are account-sensitive credentials. Use HTTPS, avoid sharing them, rotate if exposed, and preserve the same `MAIL_ENCRYPTION_KEY` for subsequent deployments. Changing the key invalidates encrypted existing App Passwords; save new ones after a key change.

### Administrator account notes

Only accounts defined with `ADMIN_EMAIL`/`ADMIN_PASSWORD` or `ADMIN_USERS_JSON` can authenticate. **There is no self-registration.** Invalid credentials display an account-not-found/incorrect-credentials popup. Failed attempts are rate-limited. Sessions expire after 4 hours. `SITE_OWNER_EMAIL` controls whose saved configuration drives the public attendance site; other admins have separate private workspaces and can manage their configured spreadsheets without changing the public site.

### Why credentials must be set in Render, not GitHub Pages or Vercel

This frontend is **static HTML/JavaScript**. Values injected into frontend builds can be extracted by visitors. Frontend environment variables used in static builds **cannot securely implement an administrator password check**. Keep admin credentials, the gateway secret and MongoDB URI in **Render** only. GitHub Pages/Vercel should have only the *public* backend URL. If you manage deployment secrets centrally, sync the secrets securely to Render rather than exposing them in browser JavaScript.

## 4. Vercel — static frontend

Create a Vercel Project from the same repository:

- **Root Directory:** `frontend`
- **Framework Preset:** Other
- **Build Command:** `npm run build`
- **Output Directory:** `dist`
- **Environment Variable:** `API_BASE_URL=https://YOUR-RENDER-SERVICE.onrender.com`

**Redeploy** after changing `API_BASE_URL`. The frontend has no hard-coded Google Apps Script URL. Add the final Vercel domain to Render's `FRONTEND_ORIGINS` variable (e.g. `https://smart-attendance.vercel.app`) and redeploy Render. If using a Vercel preview domain, allow that **exact** origin too; permissive wildcard CORS is intentionally not enabled.

Visit your Vercel website, click **ADMINISTRATOR** in the footer, log in with the Render credentials, save the Google Sheet and routine. Refresh the public homepage: it will load those settings automatically.

## 5. Run locally

Requires **Java 21, Maven, MongoDB Atlas**, and Node 20+.

1. Set `MONGODB_URI`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `JWT_SECRET`, `FRONTEND_ORIGINS=http://localhost:3000`.
2. `cd backend && mvn spring-boot:run`.
3. From `frontend`: `API_BASE_URL=http://localhost:8080 npm run build` then serve the `dist/` folder with `python -m http.server 3000 -d dist` (or run a static server of your choice).
4. Open `http://localhost:3000`, click **ADMINISTRATOR**.

## Behaviour, validation and security

- The browser uses a **server-configured schedule**; the backend checks **Asia/Dhaka** time for **every** attendance submission. Student-submitted date, time or class values are **ignored**, and the Apps Script generates the trusted date/time. The client countdown is just a visual aid.
- When `ENABLED=false`, attendance remains open 24 hours, with one date tab. When `ENABLED=true` and `USE_CLASSES=false`, a single start/end window controls one date tab. When both flags are true, each named class creates its own date/class tab.
- Date windows must finish **later on the same day**; overnight windows and overlapping classes are deliberately rejected to keep the visual timer and backend validation consistent.
- Student ID must be exactly 13 digits; duplicate IDs are rejected per sheet/day/class. Email is optional but validated. Name must contain 2–80 characters. The app displays success only when Google Sheets confirms the write.
- Public records show **masked** student IDs. Full IDs, editable cells and email values are available **only** from authenticated administrator requests. Admin row deletion is confirmed and renumbers serial numbers. Admin edits/additions validate row layout and prevent formula injection.
- No script URL, secret, admin password, MongoDB URI or signing key is sent to the public frontend. MongoDB and the Apps Script gateway must both be configured before student submission is possible.
- Source code includes basic JUnit schedule-validation tests: `cd backend && mvn test`.

## Common troubleshooting

| Symptom | Check |
| --- | --- |
| `Cannot reach backend` | Render service sleeping/restarting; `API_BASE_URL`; CORS `FRONTEND_ORIGINS` |
| `Google Sheets service unavailable` | Apps Script deployed new version; gateway secret matches; execute-as-Me; spreadsheet bound to script |
| `Unauthorized` | Render `ADMIN_EMAIL`, `ADMIN_PASSWORD`, or expired admin session |
| `Not configured` | Log in as `SITE_OWNER_EMAIL`, enter Script URL and secret, save |
| Saving configuration does not affect public site | `SITE_OWNER_EMAIL` doesn't match the logged-in admin |
| `No row records` | No attendance today, or selected sheet is empty; check the sheet tabs |
| `Password` in Vercel environment | Move it to Render; never expose admin secrets in a public build |

### Deployment security reminder

Anyone who can edit the Google Sheet directly bypasses this app's checks; restrict Google Sheet edit sharing to trusted accounts. Apps Script gateway secrets provide access control at the adapter but are not a substitute for Google account permissions. Use separate credentials for production and rotate credentials if they were previously exposed in a public repository or frontend bundle.


## 6. GitHub Pages frontend + Render backend (recommended deployment)

This repository already includes `.github/workflows/deploy-pages.yml` to build and deploy `frontend/dist` to **GitHub Pages**. You do not need to move HTML files into your repository root.

1. Upload **the entire extracted `smart-attendance/` folder contents** to the root of a GitHub repository (keep `backend/`, `frontend/`, `.github/` and `Code.gs` at the paths shown). Note: the `.github` directory is hidden in some file managers; do not omit it.
2. Deploy the Render backend first as described in Section 3. Configure `MONGODB_URI`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `JWT_SECRET`, `SITE_OWNER_EMAIL`, `MAIL_ENCRYPTION_KEY` and `FRONTEND_ORIGINS`. In GitHub Pages, the allowed CORS **origin** is `https://YOUR_USERNAME.github.io` (not the `/repository-name` path). If your frontend is also on Vercel, comma-separate both domains in `FRONTEND_ORIGINS`.
3. In the GitHub repository go to **Settings → Secrets and variables → Actions → Variables → New repository variable**. Set `API_BASE_URL` to `https://YOUR-RENDER-API.onrender.com`. This URL is public configuration, **not** a secret.
4. Go to **Settings → Pages → Build and deployment → Source → GitHub Actions**.
5. Push to `main`/`master` or open **Actions → Deploy Smart Attendance to GitHub Pages → Run workflow**. The workflow validates the API URL, builds the static website and uploads it to Pages. GitHub publishes to `https://YOUR_USERNAME.github.io/YOUR_REPOSITORY/` (or your username root site where appropriate).
6. Go to `.../admin.html` via the **ADMINISTRATOR** footer button. Sign in using `ADMIN_EMAIL` and `ADMIN_PASSWORD` configured on Render. Enter Script URL, Sheet URL, gateway secret, schedule and email options. Save, then confirm on the public page.
7. **GitHub Pages serves only public static assets.** All credentials, JWT signing, schedule checks, email settings and spreadsheet modifications remain on Render/MongoDB/Apps Script.

If you change the GitHub repo name, the pages URL path changes but API CORS origin remains `https://YOUR_USERNAME.github.io`. Render Free may spin down when idle, causing the first request to take longer than subsequent requests.

## 7. Useful files / release checks

| File | Purpose |
| --- | --- |
| `.github/workflows/deploy-pages.yml` | GitHub Actions Pages build and deployment |
| `.github/workflows/backend-ci.yml` | `mvn test` on pushes/PRs |
| `.env.example` | Names of secret Render env variables (do not fill into GitHub source) |
| `backend/Dockerfile` | Multi-stage Java 21 Render Docker build |
| `frontend/scripts/build.mjs` | Builds the public static site using API_BASE_URL |
| `frontend/config-runtime.js` | Local development fallback; build replaces it with Render API URL |

**Before going live:** check `.../actuator/health`, log into the admin, save/refresh config, test read/edit/delete with dummy sheet rows, test both enabled/disabled emails, submit a dummy attendance and observe the confirmation status. Gmail MailApp quotas and SMTP network restrictions are deployment-dependent and cannot be verified without actual credentials.
