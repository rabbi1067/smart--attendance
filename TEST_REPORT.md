# Smart Attendance v6 — Deep Re-Audit and Verification

**Audit date:** 2026-10-26 (Asia/Dhaka). **Starting point:** provided v5 ZIP.

> **Update (2026-10-29):** The Google Sheets Integration (Google Apps Script mirror, `Code.gs`, gateway secret and `mirrorStatus`) has since been **completely removed**. MongoDB is the single source of truth; emails now go through EmailJS (default), Resend, Brevo or Gmail SMTP. The Apps Script items below describe the historical audit only.

## Confirmed fixes in this release

1. Google Apps Script mirror failures previously could lead to repeated in-request mail attempts and a long response (POST + fallback), causing duplicate confirmation messages and browser timeouts. Apps Script confirmation is now single-attempt and reports `failed` when not confirmed.
2. An Apps Script response with `ok:false` previously was not distinctly represented as a failed Google Sheets mirror; backend now reports `mirrorStatus=failed` while preserving the already committed MongoDB attendance.
3. Public attendance UI displays a separate warning for optional Sheets mirror sync failures; it never claims the Mongo record was rolled back for that reason.
4. Apps Script redirect handling now only accepts trusted HTTPS Google content hosts, and request timeouts are bounded to avoid slow duplicate delivery attempts.
5. Stored `sl` may repeat after deleting an intermediate record or concurrent adds; Mongo public/admin record display now sorts by stable creation timestamp and record ID, not the mutable/reused sequence.
6. Added JUnit source tests for confirmed email result handling, mirror failures without double send, and stable ordering. They must run in a networked Maven CI runner.

## Automated execution in this sandbox

| Scope | Outcome | Evidence / limits |
|---|---|---|
| Java 21 service smoke | PASS — 24 scenarios | Real `javac` execution of service/model source using lightweight mock Spring/Mongo interfaces; not a live Atlas or full Spring Boot test |
| Apps Script regression mock | PASS — 6 scenarios | Google Sheets and MailApp mocked; validates OFF/ON, duplicate, admin test mail, POST health, unauthorized |
| Frontend JS / DOM contract | PASS — 109 admin DOM IDs, 42 public DOM IDs | Node VM syntax validation, references, duplicate IDs, frontend/API contract |
| Static frontend build | PASS | `npm run build`, generated `frontend/dist` |
| Secrets scan (known concrete credential patterns) | PASS | No production password, database URI or private key embedded; `.env.example` contains placeholders |
| Actual Maven/Spring Boot JUnit suite | NOT RUN | Maven not installed and Maven Central DNS unavailable in this environment; JUnit test source included and Render/GitHub CI configured |
| Chromium browser E2E | BLOCKED | Sandbox browser navigation yields `ERR_BLOCKED_BY_ADMINISTRATOR` (including data URLs) |
| Live MongoDB Atlas CRUD / restart persistence | NOT VERIFIED | Requires working authorized production access and a running deployment |
| Live Apps Script / Gmail / HTTPS email | NOT VERIFIED | Requires external authorization and actual inbox/provider response |
| Live Render redeployment | NOT DONE | No account access or deployment action performed |

## Required post-deployment acceptance tests

1. Rotate all secrets exposed in earlier screenshots; configure `MONGODB_URI`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `JWT_SECRET`, `MAIL_ENCRYPTION_KEY` only in Render Environment. Re-enter encrypted mail secrets after changing the encryption key.
2. Deploy repository root `Dockerfile`. It executes `mvn package`, which includes JUnit test execution; CI must pass before production use.
3. Ensure `/actuator/health` is `UP`; sign into `/admin.html` and verify Mongo connected.
4. Submit a new test Student ID, confirm one document in Atlas `smart_attendance.attendance_records`, duplicate is HTTP 409; reload/Render restart and confirm record persists.
5. Edit and delete a test record from Admin Records, verify Mongo collection and tab counts. Do not confuse optional Google Sheets mirror with the authoritative database; old Sheets rows do not automatically reflect admin edits/deletes.
6. Turn email OFF and confirm no delivery; turn ON with an authorized HTTPS provider and test to a real recipient, confirming inbox arrival. For Apps Script use the new deployed `Code.gs` version and inspect `mirrorStatus` if sync fails.
7. Check responsive Admin UI and Student page on desktop/mobile using a real browser; browser E2E could not be completed in this environment.

**Important:** No claim is made that production Atlas, actual email delivery, full Maven tests, or Render live are verified. Do not treat local mocks as production certification.
