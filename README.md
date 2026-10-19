# Smart Attendance System 🗓️

An interactive, responsive daily attendance system powered by HTML5, CSS3, JavaScript, and Google Sheets (via Google Apps Script).

## 🚀 Live Demo on GitHub Pages
This project is ready to be hosted on **GitHub Pages**.

### How to Enable GitHub Pages:
1. Push this repository to GitHub.
2. Go to your repository on GitHub.
3. Click on **Settings** > **Pages** (in the left sidebar).
4. Under **Build and deployment**:
   - **Source**: `Deploy from a branch`
   - **Branch**: `main` (or `master`), Folder: `/ (root)`
5. Click **Save**.
6. In 1–2 minutes, your website will be live at:
   `https://<your-github-username>.github.io/<repository-name>/`

---

## 📁 Files Included
- `index.html` — The main frontend web interface (supports English & Bengali, Dark/Light mode).
- `Code.gs` — Google Apps Script backend code connected to Google Sheets.

---

## ⚙️ Google Sheets & Apps Script Setup
1. Create a Google Sheet.
2. Go to **Extensions** > **Apps Script**.
3. Paste the contents of `Code.gs`.
4. Click **Deploy** > **New deployment**.
5. Select type: **Web app**.
6. Set:
   - **Execute as**: *Me*
   - **Who has access**: *Anyone*
7. Copy the Web App URL (ends with `/exec`).
8. Paste it into `index.html` under `CONFIG.SCRIPT_URL`.
