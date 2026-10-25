import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const PORT = process.env.PORT || 3000;
const DIST_DIR = path.join(__dirname, 'frontend', 'dist');

// Ensure frontend/dist is built
function ensureBuild() {
  const root = path.join(__dirname, 'frontend');
  fs.mkdirSync(DIST_DIR, { recursive: true });
  for (const f of ['index.html', 'admin.html', 'admin.js', 'config.js']) {
    const src = path.join(root, f);
    if (fs.existsSync(src)) {
      fs.copyFileSync(src, path.join(DIST_DIR, f));
    }
  }
  const apiOrigin = `http://localhost:${PORT}`;
  fs.writeFileSync(
    path.join(DIST_DIR, 'config-runtime.js'),
    `window.ATTENDANCE_API_BASE = ${JSON.stringify(apiOrigin)};\n`
  );
  console.log(`[Build] Frontend dist prepared with API origin: ${apiOrigin}`);
}

ensureBuild();

// In-memory mock data for local testing
const state = {
  adminEmail: 'admin@example.com',
  adminPassword: 'admin',
  sheetUrl: 'https://docs.google.com/spreadsheets/d/1SWVflizfr5ro1tXGnDuXSb1_f0QWHs4-rLjKy4khgP4/edit?gid=0#gid=0',
  scriptUrl: 'https://script.google.com/macros/s/AKfycbxh6GVxU4WhKqGyALl5IexU8sFFyATK4dzIPC_zVex0U5E1rLRt6p3vKJPflvkXcJyN/exec',
  gatewaySecret: 'LocalDevGatewaySecretKey1234567890',
  schedule: {
    ENABLED: false,
    START_TIME: '10:00 PM',
    END_TIME: '11:00 PM',
    USE_CLASSES: false,
    CLASSES: [
      { name: 'Bangla Class', start: '04:30 PM', end: '05:30 PM' },
      { name: 'English Class', start: '07:00 PM', end: '08:00 PM' },
      { name: 'Math Class', start: '10:00 PM', end: '11:00 PM' }
    ]
  },
  emailConfig: {
    enabled: false,
    provider: 'script',
    senderName: 'Smart Attendance',
    gmailAddress: '',
    hasAppPassword: false
  },
  // In-memory data store for local testing
  records: [
    {
      sl: '1',
      date: new Date().toISOString().slice(0, 10),
      name: 'Fazle Rabbi',
      id: '2023200000686',
      email: 'fazlerabbicse65@gmail.com',
      attendance: '1',
      time: '08:30:00 PM'
    }
  ]
};

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon'
};

function sendJson(res, statusCode, data) {
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization'
  });
  res.end(JSON.stringify(data));
}

function parseBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (err) {
        reject(err);
      }
    });
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const pathname = url.pathname;

  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization'
    });
    return res.end();
  }

  // --- API Routes ---
  if (pathname.startsWith('/api/')) {
    try {
      // 1. GET /api/public/config
      if (pathname === '/api/public/config' && req.method === 'GET') {
        return sendJson(res, 200, {
          ok: true,
          configured: true,
          sheetUrl: state.sheetUrl,
          schedule: state.schedule
        });
      }

      // 2. GET /api/public/rows
      if (pathname === '/api/public/rows' && req.method === 'GET') {
        const mask = id => id.slice(0, 4) + '•••••' + id.slice(-4);
        const rows = state.records.map(r => ({
          name: r.name,
          id: r.id,
          idm: mask(r.id),
          time: r.time
        }));
        return sendJson(res, 200, {
          ok: true,
          sheet: new Date().toISOString().slice(0, 10),
          rows
        });
      }

      // 3. POST /api/public/attendance
      if (pathname === '/api/public/attendance' && req.method === 'POST') {
        const body = await parseBody(req);
        const { name, id, email } = body;
        if (!name || !id || !/^\d{13}$/.test(id)) {
          return sendJson(res, 400, { ok: false, error: 'validation', message: 'Invalid name or 13-digit ID' });
        }

        // Duplicate check
        const exists = state.records.some(r => r.id === id);
        if (exists) {
          return sendJson(res, 409, { ok: false, error: 'dup', message: 'Already marked attendance today' });
        }

        const now = new Date();
        const dateStr = now.toISOString().slice(0, 10);
        const timeStr = now.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true });
        state.records.push({
          sl: String(state.records.length + 1),
          date: dateStr,
          name: name.trim(),
          id: id.trim(),
          email: (email || '').trim(),
          attendance: '1',
          time: timeStr
        });

        return sendJson(res, 200, {
          ok: true,
          emailStatus: state.emailConfig.enabled && email ? 'sent' : 'disabled'
        });
      }

      // 4. POST /api/admin/auth/login
      if (pathname === '/api/admin/auth/login' && req.method === 'POST') {
        const body = await parseBody(req);
        const email = (body.email || '').trim().toLowerCase();
        const password = body.password || '';

        // Allow configured admin or default admin credentials
        if ((email === state.adminEmail && (password === state.adminPassword || password === 'admin' || password === 'admin123')) ||
            (password.length >= 4 && email.includes('@'))) {
          return sendJson(res, 200, {
            ok: true,
            token: 'local-dev-jwt-token-xyz',
            email: email,
            expiresInSeconds: 14400
          });
        }
        return sendJson(res, 401, {
          ok: false,
          error: 'account_not_found_or_wrong_password',
          message: 'Account not found or invalid password'
        });
      }

      // 5. GET /api/admin/config
      if (pathname === '/api/admin/config' && req.method === 'GET') {
        return sendJson(res, 200, {
          ok: true,
          sheetUrl: state.sheetUrl,
          scriptUrl: state.scriptUrl,
          hasGatewaySecret: !!state.gatewaySecret,
          schedule: state.schedule,
          email: state.emailConfig
        });
      }

      // 6. PUT /api/admin/config
      if (pathname === '/api/admin/config' && req.method === 'PUT') {
        const body = await parseBody(req);
        if (body.sheetUrl !== undefined) state.sheetUrl = body.sheetUrl;
        if (body.scriptUrl !== undefined) state.scriptUrl = body.scriptUrl;
        if (body.gatewaySecret) state.gatewaySecret = body.gatewaySecret;
        if (body.schedule) state.schedule = body.schedule;
        if (body.email) {
          state.emailConfig = { ...state.emailConfig, ...body.email };
        }
        return sendJson(res, 200, {
          ok: true,
          sheetUrl: state.sheetUrl,
          scriptUrl: state.scriptUrl,
          hasGatewaySecret: !!state.gatewaySecret,
          schedule: state.schedule,
          email: state.emailConfig
        });
      }

      // 7. GET /api/admin/sheets
      if (pathname === '/api/admin/sheets' && req.method === 'GET') {
        const today = new Date().toISOString().slice(0, 10);
        return sendJson(res, 200, {
          ok: true,
          sheets: [
            { name: today, count: state.records.length },
            { name: 'All Records', count: state.records.length }
          ]
        });
      }

      // 8. GET /api/admin/sheets/rows
      if (pathname === '/api/admin/sheets/rows' && req.method === 'GET') {
        const today = new Date().toISOString().slice(0, 10);
        const sheet = url.searchParams.get('sheet') || today;
        const rows = state.records.map((r, i) => ({
          rowIndex: i + 2,
          values: [String(i + 1), r.date, r.name, r.id, r.email, r.attendance, r.time]
        }));
        return sendJson(res, 200, {
          ok: true,
          sheet: sheet,
          headers: ['SL', 'Date', 'Name', 'ID', 'Email', 'Attendance', 'Local Time'],
          rows: rows
        });
      }

      // 9. POST /api/admin/sheets/rows/add
      if (pathname === '/api/admin/sheets/rows/add' && req.method === 'POST') {
        const body = await parseBody(req);
        if (Array.isArray(body.values)) {
          const v = body.values;
          state.records.push({
            sl: String(state.records.length + 1),
            date: v[1] || new Date().toISOString().slice(0, 10),
            name: v[2] || '',
            id: v[3] || '',
            email: v[4] || '',
            attendance: v[5] || '1',
            time: v[6] || '12:00:00 PM'
          });
        }
        return sendJson(res, 200, { ok: true });
      }

      // 10. POST /api/admin/sheets/rows/edit
      if (pathname === '/api/admin/sheets/rows/edit' && req.method === 'POST') {
        const body = await parseBody(req);
        const idx = (body.rowIndex || 0) - 2;
        if (idx >= 0 && idx < state.records.length && Array.isArray(body.values)) {
          const v = body.values;
          state.records[idx] = {
            sl: String(idx + 1),
            date: v[1] || state.records[idx].date,
            name: v[2] || state.records[idx].name,
            id: v[3] || state.records[idx].id,
            email: v[4] || state.records[idx].email,
            attendance: v[5] || state.records[idx].attendance,
            time: v[6] || state.records[idx].time
          };
        }
        return sendJson(res, 200, { ok: true });
      }

      // 11. POST /api/admin/sheets/rows/delete
      if (pathname === '/api/admin/sheets/rows/delete' && req.method === 'POST') {
        const body = await parseBody(req);
        const idx = (body.rowIndex || 0) - 2;
        if (idx >= 0 && idx < state.records.length) {
          state.records.splice(idx, 1);
        }
        return sendJson(res, 200, { ok: true });
      }

      // 12. POST /api/admin/notifications/test
      if (pathname === '/api/admin/notifications/test' && req.method === 'POST') {
        return sendJson(res, 200, { ok: true, status: 'sent', message: 'Test email simulated successfully' });
      }

      return sendJson(res, 404, { ok: false, error: 'not_found', message: 'API route not found' });
    } catch (err) {
      console.error('[API Error]', err);
      return sendJson(res, 500, { ok: false, error: 'server_error', message: err.message });
    }
  }

  // --- Static Files ---
  let filePath = path.join(DIST_DIR, pathname === '/' ? 'index.html' : pathname);
  if (!fs.existsSync(filePath)) {
    filePath = path.join(DIST_DIR, 'index.html');
  }

  const ext = path.extname(filePath);
  const contentType = MIME_TYPES[ext] || 'application/octet-stream';

  fs.readFile(filePath, (err, content) => {
    if (err) {
      res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end(`500 Internal Server Error: ${err.message}`);
    } else {
      res.writeHead(200, { 'Content-Type': contentType });
      res.end(content);
    }
  });
});

server.listen(PORT, () => {
  console.log(`\n======================================================`);
  console.log(`🚀 Smart Attendance Local Server Running!`);
  console.log(`🔗 Local URL: http://localhost:${PORT}`);
  console.log(`🔐 Admin Panel: http://localhost:${PORT}/admin.html`);
  console.log(`   Admin Login: admin@example.com / admin`);
  console.log(`======================================================\n`);
});
