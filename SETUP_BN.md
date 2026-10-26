# Smart Attendance v6 — বাংলা Deployment ও Verification Guide

**নতুন সিস্টেমে MongoDB Atlas হলো মূল database।** Student attendance এবং Admin Add/Edit/Delete একই `attendance_records` collection-এ কাজ করে। Admin Settings সেভ হয় `admin_settings` collection-এ। database unavailable হলে আর fake success বা RAM fallback দেখাবে না।

## ১. Render-এ একসাথে Frontend + Backend Deploy

1. আপডেটেড ZIP unzip করে project-এর সব file GitHub repository-তে push করো। **`.git` history ZIP-এ দেওয়া হয়নি** এবং `.env`/credentials commit করবে না।
2. Render → তোমার `smart-attendance` Web Service → Settings → **Root Directory খালি** রাখবে। **Runtime: Docker**, Dockerfile Path: `Dockerfile` (repository root)।
3. Render → **Environment**-এ নিচের variable-গুলো সরাসরি বসাবে; GitHub YAML-এ নয়:

   - `MONGODB_URI` = MongoDB Atlas থেকে নেওয়া **নতুন password-সহ** connection string, database `/smart_attendance`।
   - `ADMIN_EMAIL` = যে email দিয়ে admin login করবে।
   - `ADMIN_PASSWORD` = নতুন long, unique password।
   - `JWT_SECRET` = নতুন 32+ byte random secret (64+ characters ভালো)।
   - `SITE_OWNER_EMAIL` = `ADMIN_EMAIL`-এর same email।
   - `MAIL_ENCRYPTION_KEY` = `openssl rand -base64 32` দিয়ে তৈরি 32-byte key-এর base64।
   - `FRONTEND_ORIGINS` = GitHub Pages/অন্য host থাকলে শুধু origin, যেমন `https://YOUR_USERNAME.github.io`। শুধু Render ব্যবহার করলে খালি রাখতে পারো।

4. MongoDB Atlas → Security → Database Access → নতুন strong-password user তৈরি করে `smart_attendance`-এ `readWrite` দেবে। Network Access → Render-এর outbound egress network allow করবে। Render Free-এর dynamic IP-এর জন্য `0.0.0.0/0` দিতে হলে strong user password ও সীমিত database privilege অত্যন্ত জরুরি।
5. Render → **Manual Deploy → Deploy latest commit**। Deploy শেষ হলে `/actuator/health` চেক করো। `UP` দেখালে app-এর `/index.html` ও `/admin.html` চেক করো।

## ২. MongoDB-তে সত্যি data save হচ্ছে কিনা

1. Render `/admin.html` → login → **Database Status: Connected** দেখো।
2. Class schedule/saving settings → MongoDB Atlas → `smart_attendance` → `admin_settings` collection-এ document থাকবে।
3. Public website-এ valid Student Name ও 13-digit ID দিয়ে attendance দাও। Success হলে → `attendance_records`-এ document থাকবে।
4. Admin Dashboard → Attendance Records → Add / Edit / Delete করলে একই MongoDB document পরিবর্তন/মুছে যাবে। Refresh করে যাচাই করো।
5. `_test_connectivity` collection-এ 0 document থাকলেও সমস্যা নয়—**ওটা attendance collection নয়**।
6. Duplicate Student ID একই date/class-এ দিলে reject করবে। Database বন্ধ/permission error হলে success দেখাবে না।

## ৩. Student Confirmation Email (Render Free)

**Render Free-তে Gmail SMTP বন্ধ (25/465/587)।** Gmail App Password দিয়েও কাজ করবে না। নিচের যেকোনো HTTPS পদ্ধতি ব্যবহার করো:

**Google Apps Script:** Google Sheet → Extensions → Apps Script → ZIP-এর `Code.gs` paste → Project Settings → Script Properties → `GATEWAY_SECRET` নামে 32–256 character নতুন secret সেট করো → Web App deployment বানিয়ে `/exec` URL নাও → Apps Script-এর `testMailAuthorization()` run করে permission allow করো। Admin Settings-এ Script URL, Sheet URL, একই secret Save করো। **Student Confirmation Emails ON**, Provider **Google Apps Script**, Save → **Send test email**।

**Resend / Brevo:** সংশ্লিষ্ট service-এ verified sender address/domain ও API key তৈরি → Admin Settings-এ provider নির্বাচন, verified sender + API key দিয়ে Save → **Send test email**। API key MongoDB-তে encrypted থাকে।

**Email বন্ধ করতে:** Admin → Student Confirmation Emails **OFF** → Save। এরপর student attendance database-এ যাবে; কারও কাছে confirmation email automatically যাবে না।

**Google Sheet সীমাবদ্ধতা:** Student attendance MongoDB-তে প্রথমে save হয়; Apps Script থাকলে Sheet-এ copy যায়। Admin Edit/Delete MongoDB record পরিবর্তন করে, পুরোনো Google Sheet copy নয়। আগের Sheets-only history চাইলে আলাদা migrate করতে হবে।

## ৪. GitHub Pages (Optional)

অতিরিক্ত GitHub Pages site চাইলে GitHub repository → Settings → Secrets and variables → Actions → **Variables** → `API_BASE_URL` = `https://YOUR-RENDER-SERVICE.onrender.com`। Settings → Pages → Source **GitHub Actions**; workflow deploy করো। Render `FRONTEND_ORIGINS`-এ `https://YOUR_USERNAME.github.io` যোগ করো (repository path নয়)। Public backend URL secret নয়; admin password, JWT, Mongo URI, Apps Script gateway secret **কোনোভাবেই** GitHub-এ দেবে না।

## ৫. Tests / Critical Security

- `npm test`: Apps Script notification ON/OFF, duplicate, test email, authenticated POST health ও frontend DOM/API contract tests।
- `npm run build`: production frontend.
- `npm run test:java`: Spring Boot JUnit tests (Java 21 এবং Maven দরকার)।
- Browser/manual testing: admin login → save schedule → student submission → Atlas verify → admin edit/delete → notification OFF/ON test → admin test email।

**সবচেয়ে জরুরি:** Screenshots-এ প্রকাশিত `ADMIN_PASSWORD`, MongoDB user password, `JWT_SECRET`, `MAIL_ENCRYPTION_KEY` অবিলম্বে **rotate** করো। Encryption key পাল্টালে আগের encrypted email credentials ব্যবহার করা যাবে না—Admin Settings থেকে আবার save করতে হবে। পুরোনো Git history-তে secrets থাকলে শুধু নতুন commit করলেই সেগুলো মুছে যায় না।

> এই release-এ কোনো live credential নেই; তোমার Render/MongoDB/Email account-এর control ছাড়া production-এ live write ও deliverability নিশ্চিত করার দাবি করা যায় না।

## ৬. v6-এ নতুন করে যা করতে হবে

- Render-এর `MAIL_ENCRYPTION_KEY` একটি **valid base64-encoded 32-byte random key** হতে হবে। নতুন Apps Script gateway secret-ও database-এ encrypted থাকবে। Existing plaintext secret থাকলে Admin → Settings → Save দিলে সেটি encrypted-এ migrate হবে। **আগের encryption key rotate করার আগে তার নিচে সংরক্ষিত password/API key আবার নতুন করে set করতে হবে**; শুধু key পাল্টে পুরোনো ciphertext decrypt হবে না।
- `Code.gs` আপডেট করে Google Apps Script-এ **new deployment version** প্রকাশ করো। v6 connection test POST ব্যবহার করে; পুরোনো Apps Script deployment হলে test কাজ করবে না।
- “All Records” → Add দিয়ে নতুন record দিতে পারবে। Date default হবে Asia/Dhaka অনুযায়ী আজকের তারিখ; Date পরিবর্তন করে record অন্য দিনেও add করা যাবে। Date-specific tab-এ date mismatch দিলে validation error দেখাবে।
- Add/Edit/Delete এরপর tab labels/counts এবং table আবার MongoDB থেকে load হবে। পুরোনো version-এর row-index-only Edit/Delete requests v6-এ ইচ্ছাকৃতভাবে reject হবে; refresh করে নতুন Admin UI ব্যবহার করবে।
- Render Docker build-এ এখন `mvn package` দিয়ে JUnit tests চালানোর চেষ্টা হয়; test fail করলে deploy বন্ধ হবে।
- Email provider `sent` দেখালেও inbox delivery guarantee নয়; actual delivery logs, sender verification, permissions এবং recipient inbox/Spam পরীক্ষা করো।

## ৭. v6-এর নতুন নির্ভরযোগ্যতা পরীক্ষা

- নতুন attendance **MongoDB-তে সফলভাবে save** হলেও Google Sheets mirror fail করলে user-কে আলাদা warning দেখাবে। `mirrorStatus=failed` হলে database save ব্যর্থ বোঝায় না।
- Google Apps Script timeout হলে একই POST আবার পাঠানো হবে না; duplicate email এড়ানোর জন্য একবারই attempt করবে। Failed email থাকলে Admin → Settings → Test Email দিয়ে provider/authorization পরীক্ষা করবে। স্বয়ংক্রিয় retry queue এই সংস্করণে নেই।
- Mongo record delete/add-এর পর পুরোনো SL পুনরাবৃত্ত হলেও frontend-এর record display stable creation-time order-এ থাকবে।
- Render-এর নতুন deployment-এর আগে পুরোনো credentials rotate ও encrypted keys আবার save করবে।
- Live MongoDB write/read, persistence after restart, live email inbox receipt, end-to-end browser flow এগুলো CI mock tests দিয়ে প্রমাণ হয় না; deploy-এর পরে acceptance checks করতে হবে।
