import fs from 'node:fs';
import path from 'node:path';
const root = path.resolve(import.meta.dirname, '..');
const dist = path.join(root, 'dist');
fs.mkdirSync(dist, { recursive: true });
for (const f of ['index.html', 'admin.html', 'admin.js', 'config.js', 'favicon.svg', 'favicon.ico', 'favicon.png', 'apple-touch-icon.png']) {
  const src = path.join(root, f);
  if (fs.existsSync(src)) fs.copyFileSync(src, path.join(dist, f));
}
const api = process.env.API_BASE_URL ? String(process.env.API_BASE_URL).replace(/\/+$/, '') : '';
if (api && !/^https?:\/\/[^\s"'<>]+$/.test(api)) {
  throw Error('API_BASE_URL must be a valid URL');
}
fs.writeFileSync(path.join(dist, 'config-runtime.js'), 'window.ATTENDANCE_API_BASE = ' + JSON.stringify(api) + ';\n');
console.log('Static frontend built; API origin:', api || '(same-origin / relative)');
