# Smart Attendance — MongoDB Production Edition (v6)

A Spring Boot 3.4 / Java 21 attendance system with responsive Bangla/English frontend, JWT-protected administrator dashboard, MongoDB Atlas persistence and server-controlled confirmation email. **No application secrets are committed to this repository.**

## What this release changes

- Student attendance is **written to MongoDB `smart_attendance.attendance_records` before a success response**. Any MongoDB read/write failure is an error—not a successful ephemeral or demo record.
- Admin **Add / Edit / Delete** uses the same `attendance_records` collection and immutable `recordId` to target the correct Mongo document. Edits and deletions **require** `recordId` and never fall back to a row index.
- Admin settings (class schedule, email provider, notification ON/OFF) are persisted in `smart_attendance.admin_settings`, keyed by `SITE_OWNER_EMAIL` (default `ADMIN_EMAIL`). The public site reads the same settings.
- The public list exposes **masked student IDs only**. Duplicate submissions are rejected by MongoDB's compound unique index `(date, className, studentId)`.
- Emails are optional and **OFF by default**. If enabled, the backend uses EmailJS (default), Resend, Brevo (HTTPS APIs), or Gmail SMTP (not viable on Render Free). The browser does not pretend that a mail send succeeded.
- Email provider API keys and Gmail App Passwords are encrypted with AES-GCM before being persisted; the admin settings API returns only key-configured flags, not secrets.
- No default admin account, hardcoded database credentials, JWT signing secret, sample attendance, or mock backend is included.

## Source layout

| Path | Purpose |
| --- | --- |
| `frontend/index.html` | Student site (masked public list, form, countdown) |
| `frontend/admin.html`, `frontend/admin.js` | Admin authentication, schedules, data grid and notification controls |
| `backend/src/main/java/` | Spring Boot REST API, database, auth and mail |
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

If deployment stops with `MongoSecurityException` and `bad auth : authentication failed`
(Atlas error 8000), Atlas rejected the configured credentials. The later
`mongoTemplate`, repository and controller errors come from that failure.
Use the Atlas **database user**, reset/verify its password, and update **Render →
Environment → MONGODB_URI** for the same cluster. Percent-encode the username and
password exactly once; keep `/smart_attendance` as the data database and
`authSource=admin` for Atlas. For example, a password containing `@` uses `%40`
in the URI. Do not encode the whole URI or leave `<password>` placeholders or
surrounding quotes. Use the Atlas-generated authentication options instead of
forcing `authMechanism=SCRAM-SHA-1`. If the log already says `source='admin'`,
changing `authSource` alone will not correct a wrong username/password.
Save the Render environment, redeploy, and verify `/actuator/health` reports `UP`.
**Pushing code does not replace the credentials stored in Render.** See
[the Bangla recovery steps](SETUP_BN.md#11-mongodb-atlas-bad-auth-recovery).

The backend prints these recovery steps when authentication prevents startup.
It still requires successful database authentication and index creation before
serving attendance requests.

In Data Explorer select database `smart_attendance` and inspect the following **collections**:

- `attendance_records`: appears after a student submits attendance or admin adds a record.
- `admin_settings`: appears after the admin saves configuration.

The empty `_test_connectivity` collection is **not the attendance collection**, so zero documents there does not prove that the app is failing to save. Use the Admin **Database Connected** card and the actual collections above.

If a MongoDB unique index cannot be created because existing documents violate `(date, className, studentId)`, deduplicate the existing records first or contact your MongoDB administrator; the app must not silently ignore this constraint.

## Configure student confirmation emails

### EmailJS (default, free HTTPS API)

In Admin → Settings select **EmailJS API**, create a free account at emailjs.com, connect your Gmail service, create a template, then paste the **Service ID**, **Template ID**, and **Public Key** (plus optional Private Key if strict mode is enabled). Enable notifications and save, then click **Send test email**. Provider quotas and verification requirements apply.

### Resend or Brevo HTTPS API

In Admin → Settings, select **Resend API** or **Brevo API**, supply a valid provider API key and a **verified sender email/domain**, enable notifications, and save. Keys are encrypted in MongoDB and never returned to the browser. Test sending to your admin email. Provider account verification, rate limits, sender/domain restrictions, and real email delivery are external and cannot be simulated reliably.

### Gmail SMTP

Only suitable on a host that permits outbound SMTP. **Render Free blocks SMTP ports 25, 465 and 587**, so Gmail SMTP cannot be made to work there by changing the App Password. Prefer EmailJS/Resend/Brevo. If using a paid host, put the Gmail App Password in the Admin form (not Render `.yml`, not GitHub). The backend encrypts it.

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
# Frontend contract test: DOM id consistency, syntax and email guards
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
- If you previously stored records **only in Google Sheets** under an old version, they will not automatically migrate to MongoDB. Back up and import the old data separately before treating MongoDB as complete history.
- The first request to a sleeping Render Free service can take longer. Inspect **Render → Logs** for failed startup, authentication or database index errors.
- Rotate all screenshot-exposed secrets; do not paste credentials into screenshots, public repositories, static frontend scripts or support tickets.

## v6 hardening notes

- All Records date picker defaults to the current **Asia/Dhaka** date instead of the tab label, which previously produced an invalid date.
- A record mutation refreshes the grouped tab list, counts, table and database status. Selecting a date-specific tab prohibits saving a conflicting date; use All Records to move an entry between dates.
- Docker builds execute the Maven test suite as part of `mvn package` so a failing JUnit test stops deployment.
- An email API's accepted/sent response means the provider accepted the request; final inbox delivery, spam filtering and provider webhook outcomes require live verification.
- `npm test` runs frontend DOM/API-contract checks; `npm run test:java` runs the actual Spring Boot tests when Maven and dependencies are available.

## v6 integrity and notification fixes (26 October 2026)

- POST-delivered email receipts run **once** per successful MongoDB submission in a background pool; the student's HTTP response is never delayed by an outbound email. `emailStatus` is reported honestly and does not roll back MongoDB attendance.
- Attendance table order is stable by `createdAt` + record ID, not stored SL, which can be reused after deletion or concurrent requests. Displayed SL remains calculated from actual table order.
- JUnit regression tests cover instant-submit semantics and duplicate-email prevention; GitHub Actions/Render builds run them on a networked runner.
