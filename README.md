# Smart Attendance — MongoDB Production Edition (v6)

A Spring Boot 3.4 / Java 21 attendance system with responsive Bangla/English frontend, JWT-protected administrator dashboard, MongoDB Atlas persistence and server-controlled confirmation email. **No application secrets are committed to this repository.**

## What this release changes

- Student attendance is **written to MongoDB `smart_attendance.attendance_records` before a success response**. Any MongoDB read/write failure is an error—not a successful ephemeral or demo record.
- Admin **Add / Edit / Delete** uses the same `attendance_records` collection and immutable `recordId` to target the correct Mongo document. Edits and deletions **require** `recordId` and never fall back to a row index.
- Admin settings (class schedule, provider, gateway URL, notification ON/OFF) are persisted in `smart_attendance.admin_settings`, keyed by `SITE_OWNER_EMAIL` (default `ADMIN_EMAIL`). The public site reads the same settings.
- The public list exposes **masked student IDs only**. Duplicate submissions are rejected by MongoDB's compound unique index `(date, className, studentId)`.
- Emails are optional and **OFF by default**. If enabled, the backend uses Google Apps Script, Resend, Brevo, EmailJS API, or Gmail SMTP (not viable on Render Free). The browser does not pretend that a mail send succeeded.
- Email provider API keys, Gmail App Passwords and new Apps Script gateway secrets are encrypted with AES-GCM before being persisted; the admin settings API returns only key-configured flags, not secrets.
- No default admin account, hardcoded database credentials, JWT signing secret, sample attendance, or mock backend is included.

## Source layout

| Path | Purpose |
| --- | --- |
| `frontend/index.html` | Student site (masked public list, form, countdown) |
| `frontend/admin.html`, `frontend/admin.js` | Admin authentication, schedules, data grid and notification controls |
| `backend/src/main/java/` | Spring Boot REST API, database, auth and mail |
| `Code.gs` | Optional Google Apps Script mirror and Gmail `MailApp` mail relay |
| `Dockerfile`, `render.yaml` | One Render Docker web service serves both frontend + backend |
| `.github/workflows/` | Maven backend CI + optional GitHub Pages frontend deployment |
| `SETUP_BN.md` | Bangla step-by-step deployment guide |

## Deploy on Render (one web service)

1. Push the **extracted repository files** (NOT this ZIP itself) to a private GitHub repo or a reviewed public repo. `Dockerfile` is at the **repository root**.
2. In Render: **New → Web Service → Connect repository → Runtime: Docker**. Leave **Root Directory empty**, use root `Dockerfile`, and enable automatic deploys. Alternatively use the root `render.yaml` Blueprint.
3. Set the environment variables **directly in Render**, not GitHub files or frontend configuration:

   | Key | Value |
   | --- | --- |
   | `MONGODB_URI` | Fresh MongoDB Atlas SRV connection string with `/smart_attendance?…` in the path |
   | `ADMIN_EMAIL` | Administrator sign-in email |
   | `ADMIN_PASSWORD` | New long unique administrator password |
   | `JWT_SECRET` | New random secret at least 32 bytes; 64+ random characters advised |
   | `SITE_OWNER_EMAIL` | Same as `ADMIN_EMAIL` (optional; recommended for clarity) |
   | `MAIL_ENCRYPTION_KEY` | Base64 of 32 random bytes, e.g. `openssl rand -base64 32` |
   | `FRONTEND_ORIGINS` | Only when hosting frontend on another origin, e.g. `https://USERNAME.github.io` |
   | `ADMIN_USERS_JSON` | Optional secondary admin credentials, generally leave `[]` |

4. In MongoDB Atlas: **Database Access** → create/update the database user with the correct password and `readWrite` on `smart_attendance`; **Network Access** → allow the outbound network range used by Render. Free Render networking can have dynamic egress IPs; if temporarily using Atlas `0.0.0.0/0`, use a strong database password and least-privilege role. TLS is enabled via SRV connection.
5. Trigger **Manual Deploy → Deploy latest commit** if required. Open `https://YOUR-SERVICE.onrender.com/actuator/health`. This must show healthy MongoDB connectivity. Open `/index.html` and `/admin.html` on the **same Render hostname**.
6. Log into Admin with `ADMIN_EMAIL` / `ADMIN_PASSWORD`, save class schedule and email settings, and check the MongoDB status card. Submit one real attendance and confirm `attendance_records` contains a document; edit/delete that document in the admin records grid.

**Important:** The existing screenshot shows secrets in plain text. **Rotate all exposed administrator, MongoDB user, JWT and encryption credentials immediately.** Rotating the encryption key invalidates previously saved encrypted email passwords/API keys, so enter and save new ones afterward. Existing Git history may still contain secrets—rotating them is mandatory even if current files are cleaned.

### MongoDB Atlas verification

In Data Explorer select database `smart_attendance` and inspect the following **collections**:

- `attendance_records`: appears after a student submits attendance or admin adds a record.
- `admin_settings`: appears after the admin saves configuration.

The empty `_test_connectivity` collection is **not the attendance collection**, so zero documents there does not prove that the app is failing to save. Use the Admin **Database Connected** card and the actual collections above.

If a MongoDB unique index cannot be created because existing documents violate `(date, className, studentId)`, deduplicate the existing records first or contact your MongoDB administrator; the app must not silently ignore this constraint.

## Configure student confirmation emails

### Option A — Google Apps Script (free HTTPS, best if you use Google Sheets)

1. Open a Google Spreadsheet → **Extensions → Apps Script**. Replace its code with `Code.gs`.
2. **Project settings → Script properties** → add `GATEWAY_SECRET` containing a new high-entropy random secret of 32–256 characters.
3. Deploy as **Web App**, execute as the account owner, and copy the deployed `https://script.google.com/macros/s/.../exec` URL. Grant the needed Google Sheets / `MailApp` scopes; run `testMailAuthorization()` in the Apps Script editor when prompted.
4. In Admin → Settings enter **Apps Script Web App URL**, **Google Sheet URL**, and the matching **Gateway Secret**; save.
5. Choose **Google Apps Script** as provider; switch **Student Confirmation Emails ON** and save. Click **Send test email**; use the actual email delivery result shown.
6. When changing `Code.gs`, **deploy a new Web App version**; merely saving a `.gs` file does not update an older deployment.

Google Sheets is an **optional mirror**, not the application's database. Student submissions first commit to MongoDB. Admin edits/deletes affect MongoDB; they do **not** automatically update previously mirrored rows in Google Sheets.

### Option B — Resend or Brevo HTTPS API

In Admin → Settings, select **Resend API** or **Brevo API**, supply a valid provider API key and a **verified sender email/domain**, enable notifications, and save. Keys are encrypted in MongoDB and never returned to the browser. Test sending to your admin email. Provider account verification, rate limits, sender/domain restrictions, and real email delivery are external and cannot be simulated reliably.

### Option C — Gmail SMTP

Only suitable on a host that permits outbound SMTP. **Render Free blocks SMTP ports 25, 465 and 587**, so Gmail SMTP cannot be made to work there by changing the App Password. Prefer Options A or B. If using a paid host, put the Gmail App Password in the Admin form (not Render `.yml`, not GitHub). The backend encrypts it.

### Turning notifications OFF

In Admin → Student Confirmation Emails, set **OFF**, save, and recheck the dashboard status. New attendance is still saved to MongoDB; no student auto-email will be attempted. A mail delivery failure is reported separately and does not erase successfully persisted attendance.

## Optional separate GitHub Pages frontend

The root Docker image already serves the frontend, so GitHub Pages is unnecessary unless you want a second public URL.

1. In GitHub repo **Settings → Secrets and variables → Actions → Variables**, set **public** variable `API_BASE_URL=https://YOUR-RENDER-SERVICE.onrender.com`.
2. **Settings → Pages → Source: GitHub Actions**. Push `main` or manually run **Deploy Smart Attendance to GitHub Pages**.
3. In Render set `FRONTEND_ORIGINS=https://YOUR_USERNAME.github.io` (origin only, no `/repo` path), including any other real static frontend origins separated by commas. Redeploy after changing this configuration.
4. The browser receives only the public API URL. All database operations, authorization and credential storage happen in Render.

## Local development / automated tests

Requires Java 21, Maven 3.9+, Node.js 20+, access to MongoDB Atlas.

```bash
# Node/App Script test: OFF, ON, duplicate, admin test email
npm test

# Frontend production build (same-origin configuration by default)
npm run build

# Spring Boot unit tests for validation, persistence, CRUD and settings
npm run test:java

# Local server (set ADMIN_EMAIL, ADMIN_PASSWORD, MONGODB_URI, JWT_SECRET,
# MAIL_ENCRYPTION_KEY and optionally FRONTEND_ORIGINS first)
cd backend && mvn spring-boot:run
```

On the unified Render deployment, `/index.html` and `/admin.html` are served from Spring Boot's static resources. The root `Dockerfile` rebuilds and bundles the frontend at deployment time. For local testing of the admin API, use the browser network tab and look for HTTP 200 only after the MongoDB operation commits, HTTP 409 on duplicate attendance, HTTP 404 for missing admin records, HTTP 503 on database unavailability. Run GitHub **Backend CI** for actual Java unit tests on a networked Maven runner.

## Known constraints and production checks

- No live credentials are included. Real end-to-end mail, Atlas or Render redeployment must be verified on your connected accounts; test results cannot be honestly claimed from local static tests alone.
- If you previously stored records **only in Google Sheets** under the old version, they will not automatically migrate to MongoDB. Back up and import the old data separately before treating MongoDB as complete history.
- The first request to a sleeping Render Free service can take longer. Inspect **Render → Logs** for failed startup, authentication or database index errors.
- Rotate all screenshot-exposed secrets; do not paste credentials into screenshots, public repositories, static frontend scripts or support tickets.

## v6 hardening notes

- All Records date picker defaults to the current **Asia/Dhaka** date instead of the tab label, which previously produced an invalid date.
- A record mutation refreshes the grouped tab list, counts, table and database status. Selecting a date-specific tab prohibits saving a conflicting date; use All Records to move an entry between dates.
- The Apps Script connection check uses an authenticated **POST** instead of placing `GATEWAY_SECRET` in the URL. **Redeploy `Code.gs` as a new Web App version** before testing this feature. Old Apps Script versions will return `unknown_action` for POST `adminSheets`.
- Newly configured Apps Script secrets are encrypted in MongoDB with `MAIL_ENCRYPTION_KEY` (the same Render-only key used for provider credentials). Existing plaintext gateway secrets will be migrated the next time an administrator saves the settings. Keep this key stable: changing it breaks decryption of stored secrets until the relevant credentials are re-entered.
- Docker builds execute the Maven test suite as part of `mvn package` so a failing JUnit test stops deployment.
- An email API's accepted/sent response means the provider accepted the request; final inbox delivery, spam filtering and provider webhook outcomes require live verification.
- `npm test` runs both Apps Script mock regressions and frontend DOM/API-contract checks; `npm run test:java` runs the actual Spring Boot tests when Maven and dependencies are available.

## v6 integrity and notification fixes (26 October 2026)

- POST to Apps Script is attempted **once** per successful MongoDB submission. A slow/ambiguous response is **not retried** in the same request; this avoids accidental duplicate confirmation emails and HTTP client timeout chains. `emailStatus` is reported as failed if delivery cannot be confirmed; it does not roll back MongoDB attendance.
- API now returns `mirrorStatus`: `synced` (Apps Script positively acknowledged), `failed` (attempt failed/rejected), or `not_configured` (no Google Sheets mirror configured). The public page distinguishes a Sheets synchronization failure from MongoDB persistence.
- Apps Script HTTPS client rejects redirects to unfamiliar hosts and has bounded timeouts. It only accepts HTTPS redirects to permitted Google Apps Script content hosts.
- Attendance table order is stable by `createdAt` + record ID, not stored SL, which can be reused after deletion or concurrent requests. Displayed SL remains calculated from actual table order.
- JUnit regression tests added for API mirror failure and duplicate-email prevention; **not executed here** because Maven/dependencies and live service credentials are unavailable. GitHub Actions/Render builds will run them.
- A Sheets mirror remains optional and is **not** synchronized after Admin MongoDB edits/deletes. If you need a two-way or strongly consistent Google Sheet, this release does not implement that.
