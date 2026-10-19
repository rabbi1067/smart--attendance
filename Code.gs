const HEADERS = ["Date", "Name", "ID", "Email", "Attendance", "Local Time"];
const TIMEZONE = "Asia/Dhaka";        // server decides the attendance date (device date can't be faked)
const RATE_SECONDS = 10;              // same ID can't hit the server again within this time
const GLOBAL_LIMIT = 80;              // max requests per 10s window for the whole site
const LIST_CACHE_SECONDS = 20;        // today's list is cached to protect the sheet

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
function today_() { return Utilities.formatDate(new Date(), TIMEZONE, "yyyy-MM-dd"); }
function now_() { return Utilities.formatDate(new Date(), TIMEZONE, "h:mm:ss a"); }
function mask_(id) { return id.slice(0, 4) + "•••••" + id.slice(-4); }

function getDaySheet_(ss, date) {
  let sh = ss.getSheetByName(date);
  if (sh) return sh;
  sh = ss.insertSheet(date, 0);                       // newest date tab comes first
  ["A:A", "B:B", "C:C", "D:D", "F:F"].forEach(r => sh.getRange(r).setNumberFormat("@")); // text: keeps 13 digits, blocks formulas
  sh.getRange(1, 1, 1, HEADERS.length).setValues([HEADERS])
    .setBackground("#4f46e5").setFontColor("#ffffff").setFontWeight("bold")
    .setFontSize(11).setHorizontalAlignment("center").setVerticalAlignment("middle");
  sh.setRowHeight(1, 36);
  sh.setFrozenRows(1);
  [110, 200, 150, 240, 110, 120].forEach((w, i) => sh.setColumnWidth(i + 1, w));
  sh.getRange("A2:F").setVerticalAlignment("middle");
  sh.getRange("E2:E").setHorizontalAlignment("center").setFontColor("#16a34a").setFontWeight("bold");
  ["A2:A", "C2:C", "F2:F"].forEach(r => sh.getRange(r).setHorizontalAlignment("center"));
  sh.getRange(1, 1, sh.getMaxRows(), HEADERS.length)
    .applyRowBanding(SpreadsheetApp.BandingTheme.INDIGO, true, false);
  sh.setTabColor("#4f46e5");
  return sh;
}

function doPost(e) {
  const cache = CacheService.getScriptCache();
  let d;
  try { d = JSON.parse(e.postData.contents); } catch (err) { return json_({ ok: false, error: "server" }); }

  const name = String(d.name || "").trim().slice(0, 80);
  const id = String(d.id || "").trim();
  const email = String(d.email || "").trim().slice(0, 100);
  let time = String(d.time || "").trim();
  if (!time || !/(?:AM|PM|\d{1,2}:\d{2})/i.test(time)) time = now_();
  const date = today_();

  // 1) validation
  if (!/^\d{13}$/.test(id)) return json_({ ok: false, error: "bad_id" });
  if (name.length < 2) return json_({ ok: false, error: "bad_name" });
  if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return json_({ ok: false, error: "bad_email" });

  // 2) fast duplicate check (cache) - no sheet read
  const dupKey = "d_" + date + "_" + id;
  if (cache.get(dupKey)) return json_({ ok: false, error: "dup" });

  // 3) rate limits: per ID and for the whole site
  const rateKey = "r_" + id;
  if (cache.get(rateKey)) return json_({ ok: false, error: "rate" });
  cache.put(rateKey, "1", RATE_SECONDS);
  const gKey = "g_" + Math.floor(Date.now() / 10000);
  const n = Number(cache.get(gKey) || 0) + 1;
  cache.put(gKey, String(n), 30);
  if (n > GLOBAL_LIMIT) return json_({ ok: false, error: "busy" });

  // 4) short locked section: real duplicate check on the sheet + save
  const lock = LockService.getScriptLock();
  try { lock.waitLock(25000); } catch (err) { return json_({ ok: false, error: "busy" }); }
  try {
    const sh = getDaySheet_(SpreadsheetApp.getActiveSpreadsheet(), date);
    const last = sh.getLastRow();
    if (last > 1) {
      const ids = sh.getRange(2, 3, last - 1, 1).getValues().flat().map(String);
      if (ids.indexOf(id) !== -1) {
        cache.put(dupKey, "1", 21600);
        return json_({ ok: false, error: "dup" });
      }
    }
    sh.appendRow([date, name, id, email, 1, time]);     // Attendance = 1 (Present)
    cache.put(dupKey, "1", 21600);
    cache.remove("list_" + date);
    return json_({ ok: true });
  } catch (err) {
    return json_({ ok: false, error: "server" });
  } finally {
    lock.releaseLock();
  }
}

function formatTime12_(val) {
  if (!val) return "";
  if (val instanceof Date) {
    return Utilities.formatDate(val, TIMEZONE, "h:mm:ss a");
  }
  const str = String(val).trim();
  const m12 = str.match(/^(\d{1,2}):(\d{2}):(\d{2})\s*(AM|PM|am|pm)$/i);
  if (m12) {
    const h = parseInt(m12[1], 10);
    return h + ":" + m12[2] + ":" + m12[3] + " " + m12[4].toUpperCase();
  }
  const m = str.match(/(?:^|\s|T)(\d{1,2}):(\d{2}):(\d{2})/);
  if (m) {
    let h = parseInt(m[1], 10);
    const ampm = h >= 12 ? "PM" : "AM";
    h = h % 12 || 12;
    return h + ":" + m[2] + ":" + m[3] + " " + ampm;
  }
  return str;
}

function doGet() {
  const cache = CacheService.getScriptCache();
  const date = today_();
  const key = "list_" + date;
  const hit = cache.get(key);
  if (hit) return ContentService.createTextOutput(hit).setMimeType(ContentService.MimeType.JSON);

  const sh = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(date);
  let rows = [];
  if (sh && sh.getLastRow() > 1) {
    rows = sh.getRange(2, 1, sh.getLastRow() - 1, 6).getValues()
      .map(r => ({ name: r[1], idm: mask_(String(r[2])), time: formatTime12_(r[5]) }));   // public list: masked ID only
  }
  const out = JSON.stringify({ ok: true, rows: rows });
  if (out.length < 90000) cache.put(key, out, LIST_CACHE_SECONDS);
  return ContentService.createTextOutput(out).setMimeType(ContentService.MimeType.JSON);
}
