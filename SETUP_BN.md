# Smart Attendance v3 — GitHub Pages + Render Setup (বাংলা)

## Architecture

`Student → GitHub Pages website → Render Spring Boot API → Google Apps Script → Google Sheet`

Admin login, per-admin settings, email credentials ও timer: `Render API → MongoDB Atlas`।

**গুরুত্বপূর্ণ:** GitHub Pages এবং Vercel দুটোই static frontend; সেখানে secret admin password নিরাপদে রাখা যায় না। Admin login-এর `ADMIN_EMAIL` / `ADMIN_PASSWORD` Render-এর Environment-এ বসাতে হবে। Public frontend শুধু Render API URL জানবে।

## Step 1 — Google Sheet এবং Apps Script

1. Google Sheet খুলে `Extensions → Apps Script`-এ ZIP-এর `Code.gs` সম্পূর্ণ paste করুন।
2. Script Properties-এ `GATEWAY_SECRET` নামে একটি 32+ character secret দিন। একই secret পরে Admin Dashboard-এ রাখতে হবে।
3. Web App হিসেবে deploy করুন: `Execute as: Me`, `Who has access: Anyone`। Google permissions (Sheets এবং email-এর MailApp permission) অনুমোদন দিন।
4. `/exec` URL কপি করুন। পরে code পরিবর্তন করলে deployment-এ অবশ্যই **New version** প্রকাশ করতে হবে।

## Step 2 — MongoDB Atlas

1. Free Atlas cluster, DB user এবং network access configure করুন।
2. `mongodb+srv://...` URI নিন। এটি **শুধু Render environment**-এ রাখবেন।

## Step 3 — Render Web Service (Backend)

GitHub-এ project-এর পুরো folder structure push করুন। Render → `New Web Service` → GitHub repository connect:

- **Root Directory:** `backend`
- **Runtime:** `Docker`
- **Dockerfile:** `./Dockerfile` (Root Directory-এর সাপেক্ষে)

Environment variables:

| Name | Value |
| --- | --- |
| `MONGODB_URI` | Atlas connection URI |
| `ADMIN_EMAIL` | নিজের administrator email |
| `ADMIN_PASSWORD` | শক্তিশালী admin login password |
| `JWT_SECRET` | random 64-character+ secret |
| `MAIL_ENCRYPTION_KEY` | `openssl rand -base64 32` command output |
| `SITE_OWNER_EMAIL` | `ADMIN_EMAIL`-এর একই value |
| `FRONTEND_ORIGINS` | `https://YOUR_USERNAME.github.io` (repo name যুক্ত করবেন না) |

Multiple admin দরকার হলে `ADMIN_USERS_JSON` ব্যবহার করা যাবে। প্রত্যেকের settings MongoDB-তে আলাদা save হবে, তবে visitor-facing settings নিয়ন্ত্রণ করবে `SITE_OWNER_EMAIL`।

## Step 4 — GitHub Pages (Frontend)

1. GitHub repo → `Settings → Secrets and variables → Actions → Variables` → `API_BASE_URL` name দিয়ে Render URL, যেমন `https://YOUR-BACKEND.onrender.com` দিন।
2. `Settings → Pages` → Source `GitHub Actions` নির্বাচন করুন।
3. `Actions` → `Deploy Smart Attendance to GitHub Pages` → `Run workflow`। এরপর GitHub Pages site-এ যান।
4. Main page-এর নিচে `ADMINISTRATOR` click করুন; Render environment-এর `ADMIN_EMAIL` ও `ADMIN_PASSWORD` দিয়ে login করুন।
5. Apps Script URL, Sheet URL, gateway secret, timer এবং email settings save করুন।

## Step 5 — Email Notification

- **OFF** → কারো কাছে automatic email যাবে না।
- **ON + Google Apps Script** → Render Free-তে কাজ করার জন্য recommended; App Password লাগে না। Apps Script deploy করা Google account থেকে branded attendance receipt যাবে। Google Apps Script mail quota প্রযোজ্য।
- **ON + Gmail SMTP** → Sender Gmail + Google 16-character App Password দিন। `MAIL_ENCRYPTION_KEY` ব্যবহার করে password encrypt করা হবে। **Render Free-তে SMTP কাজ করবে না**, কারণ SMTP ports বন্ধ; paid Render/SMTP-egress-enabled hosting দরকার।
- Settings Save করার পর `Send test to my admin email` click করে পরীক্ষা করুন।

কোনো student email field ফাঁকা রাখলে তাকে email যাবে না। Email পাঠাতে সমস্যা হলেও Google Sheet-এ উপস্থিতি save হয়ে থাকবে। এক ছাত্রের এক ক্লাস/দিনের duplicate attendance ব্লক করা হয়।

## Test checklist

1. Render `/actuator/health` check করুন।
2. Admin login → Sheet connection save → refresh করে সেটিংস আছে কি না দেখুন।
3. Google Sheet tab view, row add/edit/delete test করুন।
4. Timer OFF (24 hours), Timer ON (single class), multiple class schedule পরীক্ষা করুন।
5. Email OFF থাকা অবস্থায় attendance দিয়ে নিশ্চিত করুন কোনো email যায়নি।
6. Email ON এবং student email fill করে submission test করুন।
7. GitHub Pages আর Render-এর CORS mismatch হলে `FRONTEND_ORIGINS` চেক করুন।

**Safety:** App Password, Atlas URI, JWT_SECRET, Gateway Secret কখনো GitHub public source code বা GitHub Pages JavaScript-এ বসাবেন না।

বিস্তারিত deployment documentation: `README.md`।
