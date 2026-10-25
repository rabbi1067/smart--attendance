'use strict';
const $ = id => document.getElementById(id);
const API = String(window.ATTENDANCE_API_BASE || '').replace(/\/$/, '') + '/api';
let token = sessionStorage.getItem('admin_token') || '';
let userEmail = sessionStorage.getItem('admin_email') || '';
let sheets = [], sheetData = null, currentEdit = null, currentDelete = null;
let toastTimer;
function notice(message) {
  const t = $('toast');
  if (!t) return;
  const isErr = /fail|error|wrong|invalid|required|maximum/i.test(String(message));
  const icon = isErr ? '⚠️' : '✓';
  t.innerHTML = `<span style="font-size:1.05rem;line-height:1">${icon}</span><span>${String(message)}</span>`;
  t.classList.remove('hidden');
  clearTimeout(toastTimer); toastTimer = setTimeout(()=> $('toast').classList.add('hidden'),4500);
}
async function request(path,{method='GET',body=null,protectedRoute=true}={}) {
  const opts = {method,headers:{'Content-Type':'application/json'}};
  if (protectedRoute && token) opts.headers.Authorization = `Bearer ${token}`;
  if (body !== null) opts.body = JSON.stringify(body);
  const c = new AbortController(); const timer = setTimeout(()=>c.abort(),32000); opts.signal = c.signal;
  let response, data;
  try { response = await fetch(API+path,opts); data = await response.json(); }
  catch(e) { throw Error('Cannot reach backend. Check Render URL, deployment and network.'); }
  finally { clearTimeout(timer); }
  if(response.status === 401 && protectedRoute) { logout(false); throw Error('Session expired. Please log in again.'); }
  if(!response.ok || data.ok === false) throw Error(data.message || (data.error==='dup'?'Duplicate student ID in this sheet':data.error) || 'Request failed');
  return data;
}
function showApp(show) {
  $('loginView').classList.toggle('hidden', show);
  $('dashboard').classList.toggle('hidden', !show);
  $('logout').classList.toggle('hidden', !show);
  $('account').classList.toggle('hidden', !show);
  $('account').textContent = userEmail;
}
function logout(showMessage=true) {
  token = ''; userEmail='';
  sessionStorage.removeItem('admin_token');sessionStorage.removeItem('admin_email');
  showApp(false);
  $('loginPassword').value = '';
  if (showMessage) notice('Signed out.');
}
$('logout').addEventListener('click',()=>logout());
$('loginForm').addEventListener('submit',async e=>{
  e.preventDefault(); $('loginError').classList.add('hidden');
  const btn=$('loginBtn');btn.disabled=true;btn.textContent='Signing in…';
  try {
    const data=await request('/admin/auth/login',{method:'POST',protectedRoute:false,body:{email:$('loginEmail').value.trim(),password:$('loginPassword').value.trim()}});
    token=data.token;userEmail=data.email;
    sessionStorage.setItem('admin_token',token);sessionStorage.setItem('admin_email',userEmail);
    $('loginPassword').value=''; await initialize();
  }catch(err){
    $('loginError').textContent = /account_not_found|credentials|401/.test(err.message) ? 'Account not found or incorrect email/password.' : err.message;
    $('loginError').classList.remove('hidden'); notice($('loginError').textContent);
  } finally {btn.disabled=false;btn.textContent='Sign in securely →';}
});
for(const button of document.querySelectorAll('[data-tab]')) button.addEventListener('click',async()=>{
  document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('active',b===button));
  const records=button.dataset.tab==='records';
  $('recordsPanel').classList.toggle('hidden',!records);
  $('settingsPanel').classList.toggle('hidden',records);
  if(records) await loadSheets();
});
function from12(value) {
  const match=String(value||'').trim().match(/^(\d{1,2}):(\d{2})\s*(AM|PM)$/i);
  if(!match) return '10:00';
  return String((Number(match[1])%12)+(match[3].toUpperCase()==='PM'?12:0)).padStart(2,'0')+':'+match[2];
}
function to12(value) {
  if(!/^\d{2}:\d{2}$/.test(value)) throw Error('Please select a valid time');
  const h=Number(value.slice(0,2));
  return `${String(h%12||12).padStart(2,'0')}:${value.slice(3)} ${h>=12?'PM':'AM'}`;
}
let classState=[];
function classElement(c,i) {
  const row=document.createElement('div');row.className='class-row';
  for(const [label,key,type] of [['Class name','name','text'],['Start','start','time'],['End','end','time']]) {
    const wrap=document.createElement('div');
    const labelEl=document.createElement('label');labelEl.textContent=label;
    const input=document.createElement('input');input.type=type;input.required=true;input.maxLength=50;
    input.value=type==='time'?from12(c[key]):c[key];input.dataset.field=key;
    input.addEventListener('input',()=>{classState[i][key]=type==='time'&&/^\d{2}:\d{2}$/.test(input.value)?to12(input.value):input.value;});
    wrap.append(labelEl,input);row.append(wrap);
  }
  const del=document.createElement('button');del.type='button';del.className='btn btn-danger small';del.textContent='Remove';
  del.addEventListener('click',()=>{classState.splice(i,1);drawClasses();}); row.append(del);
  return row;
}
function drawClasses(){const wrap=$('classesList');wrap.replaceChildren();classState.forEach((c,i)=>wrap.append(classElement(c,i)));wrap.querySelectorAll('input').forEach(input=>input.disabled=!$('useClasses').checked);}
function mailUi() {
  $('mailPreviewDate').textContent=new Intl.DateTimeFormat('en-GB',{timeZone:'Asia/Dhaka',day:'numeric',month:'long',year:'numeric'}).format(new Date());
  const on=$('mailEnabled').checked;
  $('mailEnabledFields').style.opacity=on?'1':'0.58';
  const provider=document.querySelector('input[name="mailProvider"]:checked').value;
  $('smtpFields').classList.toggle('hidden',provider!=='SMTP');
  $('scriptMailHint').classList.toggle('hidden',provider==='SMTP');
  $('testNotification').disabled=!on;
}
$('mailEnabled').addEventListener('change',mailUi);
for(const radio of document.querySelectorAll('input[name="mailProvider"]'))radio.addEventListener('change',mailUi);
$('testNotification').addEventListener('click', async()=>{
  const button=$('testNotification');button.disabled=true;button.textContent='Sending test…';
  try{
    const data=await request('/admin/notifications/test',{method:'POST'});
    notice(data.emailStatus==='sent'?'Test email sent to your administrator email address.':'Test email failed. Check sender settings, Apps Script authorization or SMTP hosting restrictions.');
  }catch(e){notice(e.message)}finally{button.textContent='✉ Send test to my admin email';mailUi();}
});
function updateScheduleSections() {
  $('classesSection').classList.toggle('hidden',!$('useClasses').checked);
  $('simpleSchedule').classList.toggle('hidden',$('useClasses').checked);
  // HTML required controls inside hidden sections can otherwise block form submission.
  $('simpleSchedule').querySelectorAll('input').forEach(input=>input.disabled=$('useClasses').checked);
  $('classesSection').querySelectorAll('input').forEach(input=>input.disabled=!$('useClasses').checked);
  updateScheduleBadge();
}
$('useClasses').addEventListener('change',updateScheduleSections);
$('addClass').addEventListener('click',()=>{
  if(classState.length>=25) return notice('Maximum 25 classes allowed.');
  classState.push({name:'New Class',start:'09:00 AM',end:'10:00 AM'});drawClasses();
});
$('generateSecret').addEventListener('click',()=>{
  const bytes=new Uint8Array(40);crypto.getRandomValues(bytes);
  $('scriptSecret').value = Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
  $('scriptSecret').type='text';
  const toggleSec = $('toggleSecretVisibility');
  if (toggleSec) { toggleSec.textContent = '🔒'; toggleSec.title = 'Hide secret'; }
  notice(currentLang === 'bn' 
    ? 'নতুন Secret জেনারেট হয়েছে! কপি বাটনে চাপ দিয়ে কপি করুন এবং সেভ করুন।' 
    : 'New secret generated. Click Copy to copy it, then save.');
});

const toggleSecBtn = $('toggleSecretVisibility');
if (toggleSecBtn) {
  toggleSecBtn.addEventListener('click', () => {
    const inp = $('scriptSecret');
    if (inp.type === 'password') {
      inp.type = 'text';
      toggleSecBtn.textContent = '🔒';
      toggleSecBtn.title = currentLang === 'bn' ? 'সিক্রেট লুকান' : 'Hide secret';
    } else {
      inp.type = 'password';
      toggleSecBtn.textContent = '👁';
      toggleSecBtn.title = currentLang === 'bn' ? 'সিক্রেট দেখুন' : 'Show secret';
    }
  });
}

const copySecBtn = $('copySecretBtn');
if (copySecBtn) {
  copySecBtn.addEventListener('click', async () => {
    const inp = $('scriptSecret');
    const val = inp.value.trim();
    if (!val) {
      notice(currentLang === 'bn'
        ? 'কপি করার জন্য আগে 🎲 Generate বাটনে চাপ দিয়ে একটি Secret তৈরি করুন।'
        : 'Please click 🎲 Generate to create a new secret first, then copy.');
      return;
    }
    try {
      await navigator.clipboard.writeText(val);
      notice(currentLang === 'bn' ? '✓ Secret ক্লিপবোর্ডে কপি হয়েছে!' : '✓ Secret copied to clipboard!');
    } catch(e) {
      inp.select();
      document.execCommand('copy');
      notice(currentLang === 'bn' ? '✓ Secret কপি হয়েছে!' : '✓ Secret copied!');
    }
  });
}

const testConnBtn = $('testScriptConn');
const connBox = $('connTestResult');
if (testConnBtn) {
  testConnBtn.addEventListener('click', async () => {
    const unsavedSec = $('scriptSecret').value.trim();
    if (unsavedSec) {
      notice(currentLang === 'bn'
        ? '⚠️ আপনি নতুন Secret লিখেছেন বা Generate করেছেন। টেস্ট করার আগে নিচে "💾 Save configuration" চাপুন।'
        : '⚠️ You entered a new or generated secret. Please click "💾 Save configuration" below first, then test.');
      return;
    }
    testConnBtn.disabled = true;
    testConnBtn.textContent = currentLang === 'bn' ? 'পরীক্ষা হচ্ছে…' : 'Testing…';
    connBox.className = 'conn-test-box hidden';
    try {
      const res = await request('/admin/sheets');
      const sheetCount = (res.sheets || []).length;
      const sheetNames = (res.sheets || []).map(s => s.name).join(', ') || (currentLang === 'bn' ? 'কোনো ট্যাব নেই' : 'None');
      connBox.className = 'conn-test-box ok';
      connBox.innerHTML = currentLang === 'bn'
        ? `<div><strong>✅ সংযোগ সফল হয়েছে! (Connection Successful)</strong><br>গুগল অ্যাপস স্ক্রিপ্ট অনুমোদিত এবং স্প্রেডশিট সংযুক্ত আছে। মোট <strong>${sheetCount}</strong> টি ট্যাব পাওয়া গেছে (${sheetNames})।</div>`
        : `<div><strong>✅ Connection Successful!</strong><br>Apps Script is authorized and Google Sheet is connected. Found <strong>${sheetCount}</strong> tab(s) (${sheetNames}).</div>`;
    } catch(err) {
      connBox.className = 'conn-test-box err';
      const isUnauth = /unauthorized/i.test(err.message);
      if (isUnauth) {
        connBox.innerHTML = currentLang === 'bn'
          ? `<div><strong>❌ GATEWAY_SECRET মেলেনি! (Unauthorized)</strong><br>` +
            `Apps Script রিকোয়েস্টটি বাতিল করেছে কারণ Apps Script-এর <code>GATEWAY_SECRET</code> এর সাথে এই অ্যাডমিন সিক্রেট মেলেনি。<br><br>` +
            `<strong>করণীয়:</strong><br>` +
            `১. উপরের <strong>🎲 Generate</strong> চাপুন (যদি নতুন চান) এবং <strong>📋 Copy</strong> করুন。<br>` +
            `২. নিচে <strong>💾 Save configuration</strong> চাপুন。<br>` +
            `৩. গুগল শিটে <strong>Extensions ➔ Apps Script ➔ ⚙️ Project Settings ➔ Script Properties</strong>-এ গিয়ে <code>GATEWAY_SECRET</code>-এ পেস্ট করে Save করুন。<br>` +
            `৪. <strong>Deploy ➔ Manage deployments ➔ Edit ➔ New version</strong> দিয়ে Deploy করুন。<br>` +
            `৫. এরপর আবার <strong>⚡ Test Connection</strong> দিন।</div>`
          : `<div><strong>❌ Authorization Failed (Unauthorized)</strong><br>` +
            `Apps Script rejected the secret. Please ensure <code>GATEWAY_SECRET</code> is configured in Apps Script:<br><br>` +
            `1. Click <strong>🎲 Generate</strong> & <strong>📋 Copy</strong> secret.<br>` +
            `2. Click <strong>💾 Save configuration</strong> below.<br>` +
            `3. In Google Sheet, go to <strong>Extensions ➔ Apps Script ➔ ⚙️ Project Settings ➔ Script Properties</strong>, add <code>GATEWAY_SECRET</code> with this secret.<br>` +
            `4. Click <strong>Deploy ➔ Manage deployments ➔ Edit ➔ New version</strong> ➔ Deploy.<br>` +
            `5. Click <strong>⚡ Test Connection</strong> again!</div>`;
      } else {
        connBox.innerHTML = currentLang === 'bn'
          ? `<div><strong>❌ কানেকশন ব্যর্থ হয়েছে:</strong><br>${err.message}<br><small>নিশ্চিত করুন Apps Script-এ Deploy as "Web app", Execute as: "Me" এবং Who has access: "Anyone" দেওয়া আছে।</small></div>`
          : `<div><strong>❌ Connection Failed:</strong><br>${err.message}<br><small>Make sure Apps Script is deployed as Web App with Execute as: Me and Who has access: Anyone.</small></div>`;
      }
    } finally {
      testConnBtn.disabled = false;
      testConnBtn.innerHTML = '⚡ <span data-t="testConnBtn">' + (currentLang === 'bn' ? 'কানেকশন টেস্ট করুন' : 'Test Connection') + '</span>';
    }
  });
}

function updateScheduleBadge() {
  const badge = $('scheduleStatusBadge');
  if (!badge) return;
  const isTimerOn = $('scheduleEnabled').checked;
  const useMulti = $('useClasses').checked;
  if (!isTimerOn) {
    badge.className = 'schedule-status-badge allopen';
    badge.innerHTML = currentLang === 'bn'
      ? '<span>🟢 <strong>২৪ ঘণ্টা উন্মুক্ত:</strong> টাইমার বন্ধ আছে। মূল পেজে ২৪ ঘণ্টা ওপেন দেখাবে (কাউন্টডাউন বন্ধ থাকবে)।</span>'
      : '<span>🟢 <strong>24-Hour Open:</strong> Attendance timer is OFF. Public site is open anytime (no countdown).</span>';
    return;
  }
  badge.className = 'schedule-status-badge active';
  if (useMulti) {
    const count = classState.length;
    badge.innerHTML = currentLang === 'bn'
      ? `<span>⏰ <strong>টাইমার সক্রিয় (${count}টি ক্লাস):</strong> ক্লাসের সময়সূচী অনুযায়ী মূল পেজে কাউন্টডাউন চলবে।</span>`
      : `<span>⏰ <strong>Timer Active (${count} classes):</strong> Countdown runs according to class schedule on public site.</span>`;
  } else {
    const s = $('startTime').value ? to12($('startTime').value) : '10:00 PM';
    const e = $('endTime').value ? to12($('endTime').value) : '11:00 PM';
    badge.innerHTML = currentLang === 'bn'
      ? `<span>⏰ <strong>টাইমার সক্রিয় (${s} – ${e}):</strong> মূল পেজে এই সময়ের কাউন্টডাউন চলবে।</span>`
      : `<span>⏰ <strong>Timer Active (${s} – ${e}):</strong> Countdown runs for this window on public site.</span>`;
  }
}

$('scheduleEnabled').addEventListener('change', updateScheduleBadge);
$('startTime').addEventListener('input', () => { $('scheduleEnabled').checked = true; updateScheduleBadge(); });
$('endTime').addEventListener('input', () => { $('scheduleEnabled').checked = true; updateScheduleBadge(); });
function validateScriptUrl() {
  const input = $('scriptUrl'), box = $('scriptUrlFeedback');
  if (!input || !box) return;
  const val = input.value.trim();
  box.className = 'url-feedback hidden';
  input.style.borderColor = '';
  if (!val) return;

  if (val.includes('script.google.com') && (val.includes('/projects/') || val.includes('/edit') || val.includes('/home/'))) {
    box.className = 'url-feedback warn';
    box.innerHTML = currentLang === 'bn'
      ? `<span>👉 <strong>এটি এডিটর লিংক!</strong> আসল লিংক পেতে স্ক্রিপ্ট পেজের <strong>Deploy ➔ New deployment ➔ Web app</strong> থেকে কপি করুন।</span>`
      : `<span>👉 <strong>This is the editor project URL!</strong> To get the live Web App URL, go to <strong>Deploy ➔ New deployment ➔ Web app</strong>.</span>`;
    input.style.borderColor = '#f59e0b';
    return;
  }

  const isExec = /^https:\/\/script\.google\.com\/macros\/s\/[A-Za-z0-9_-]+\/exec\/?$/i.test(val);
  if (isExec) {
    box.className = 'url-feedback valid';
    box.innerHTML = currentLang === 'bn'
      ? `<span>✓ <strong>পারফেক্ট!</strong> এটি সঠিক Web App URL।</span>`
      : `<span>✓ <strong>Perfect!</strong> This is a valid Web App URL.</span>`;
    input.style.borderColor = 'var(--ok)';
    return;
  }

  if (val.length > 5) {
    box.className = 'url-feedback err';
    box.innerHTML = currentLang === 'bn'
      ? `<span>⚠️ দয়া করে <code>https://script.google.com/macros/s/.../exec</code> ফরম্যাটের লিংক দিন।</span>`
      : `<span>⚠️ Please enter a valid <code>https://script.google.com/macros/s/.../exec</code> URL.</span>`;
    input.style.borderColor = 'var(--bad)';
  }
}

function validateSheetUrl() {
  const input = $('sheetUrl'), box = $('sheetUrlFeedback');
  if (!input || !box) return;
  const val = input.value.trim();
  box.className = 'url-feedback hidden';
  input.style.borderColor = '';
  if (!val) return;

  const isSheet = /^https:\/\/docs\.google\.com\/spreadsheets\/d\/[A-Za-z0-9_-]+/i.test(val);
  if (isSheet) {
    box.className = 'url-feedback valid';
    box.innerHTML = currentLang === 'bn'
      ? `<span>✓ <strong>পারফেক্ট!</strong> গুগল স্প্রেডশিট লিংক শনাক্ত হয়েছে।</span>`
      : `<span>✓ <strong>Perfect!</strong> Google Spreadsheet link verified.</span>`;
    input.style.borderColor = 'var(--ok)';
    return;
  }

  if (val.length > 5) {
    box.className = 'url-feedback err';
    box.innerHTML = currentLang === 'bn'
      ? `<span>⚠️ দয়া করে <code>https://docs.google.com/spreadsheets/d/...</code> ফরম্যাটের লিংক দিন।</span>`
      : `<span>⚠️ Please enter a valid Google Spreadsheet URL.</span>`;
    input.style.borderColor = 'var(--bad)';
  }
}

const sUrlInput = $('scriptUrl');
if (sUrlInput) {
  sUrlInput.addEventListener('input', validateScriptUrl);
  sUrlInput.addEventListener('change', validateScriptUrl);
  sUrlInput.addEventListener('paste', () => setTimeout(validateScriptUrl, 50));
}
const shUrlInput = $('sheetUrl');
if (shUrlInput) {
  shUrlInput.addEventListener('input', validateSheetUrl);
  shUrlInput.addEventListener('change', validateSheetUrl);
  shUrlInput.addEventListener('paste', () => setTimeout(validateSheetUrl, 50));
}

async function loadConfig() {
  const cfg=await request('/admin/config');
  $('scriptUrl').value=cfg.SCRIPT_URL||'';
  $('sheetUrl').value=cfg.SHEET_URL||'';
  validateScriptUrl();
  validateSheetUrl();
  updateScheduleBadge();
  $('scriptSecret').value='';$('scriptSecret').type='password';
  $('secretStatus').textContent=cfg.SECRET_CONFIGURED?'✓ A gateway secret is saved. Leave blank to keep it.':'No secret configured yet. Generate one and add it to Apps Script.';
  $('ownerStatus').textContent=cfg.liveSiteOwner?'● LIVE SITE OWNER':'● Personal workspace (not live site owner)';
  $('ownerStatus').classList.toggle('off',!cfg.liveSiteOwner);
  $('openGoogleSheet').classList.toggle('hidden',!cfg.SHEET_URL);
  if(cfg.SHEET_URL) $('openGoogleSheet').href=cfg.SHEET_URL;
  const sch=cfg.SCHEDULE_CONFIG||{};
  $('scheduleEnabled').checked=!!sch.ENABLED;
  $('useClasses').checked=!!sch.USE_CLASSES;
  $('startTime').value=from12(sch.START_TIME||'10:00 PM');
  $('endTime').value=from12(sch.END_TIME||'11:00 PM');
  classState=(sch.CLASSES||[]).map(c=>({...c}));drawClasses();updateScheduleSections();updateScheduleBadge();
  const m=cfg.EMAIL_CONFIG||{};
  $('mailEnabled').checked=!!m.ENABLED;
  const provider=m.PROVIDER==='SMTP'?'SMTP':'APPS_SCRIPT';
  document.querySelector(`input[name="mailProvider"][value="${provider}"]`).checked=true;
  $('senderEmail').value=m.SENDER_EMAIL||'';
  $('senderName').value=m.SENDER_NAME||'Smart Attendance';
  $('appPassword').value='';$('clearAppPassword').checked=false;
  $('passwordStatus').textContent=m.APP_PASSWORD_CONFIGURED?'✓ App Password stored securely':'No App Password saved';
  $('passwordStatus').classList.toggle('off',!m.APP_PASSWORD_CONFIGURED);
  mailUi();
}
$('configForm').addEventListener('submit',async e=>{
  e.preventDefault();const btn=$('saveConfig');btn.disabled=true;btn.textContent='Saving…';
  try {
    const data={SCRIPT_URL:$('scriptUrl').value.trim(),SHEET_URL:$('sheetUrl').value.trim(),SCRIPT_SECRET:$('scriptSecret').value.trim(),SCHEDULE_CONFIG:{
      ENABLED:$('scheduleEnabled').checked,USE_CLASSES:$('useClasses').checked,
      START_TIME:to12($('startTime').value),END_TIME:to12($('endTime').value),
      CLASSES:classState.map(c=>({name:c.name.trim(),start:c.start,end:c.end}))
    },EMAIL_CONFIG:{
      ENABLED:$('mailEnabled').checked,
      PROVIDER:document.querySelector('input[name="mailProvider"]:checked').value,
      SENDER_EMAIL:$('senderEmail').value.trim(),
      SENDER_NAME:$('senderName').value.trim(),
      APP_PASSWORD:$('appPassword').value.replace(/\s+/g,''),
      CLEAR_APP_PASSWORD:$('clearAppPassword').checked
    }};
    await request('/admin/config',{method:'PUT',body:data});
    await loadConfig();notice('Configuration saved in MongoDB successfully.');
  }catch(err){notice(err.message)}finally{btn.disabled=false;btn.textContent='💾 Save configuration';}
});
async function initialize(){
  try {
    await loadConfig();
    showApp(true);
  } catch(e) {
    notice('Notice: ' + e.message);
    showApp(true);
  }
}
async function loadSheets(){
  $('rowsStatus').textContent='Loading Google Sheets…';
  try {
    const data=await request('/admin/sheets');sheets=data.sheets||[];
    const sel=$('sheetSelect'),selected=sel.value;sel.replaceChildren(new Option('Choose a tab',''));
    for(const s of sheets) sel.add(new Option(`${s.name} (${s.count})`,s.name));
    if(sheets.some(s=>s.name===selected)) sel.value=selected;
    else if(sheets.length) sel.value=sheets[0].name;
    if(sel.value) await loadRows();else{sheetData=null;drawTable();$('rowsStatus').textContent='No sheet tabs yet.';}
  }catch(e){
    $('rowsStatus').textContent=e.message;notice(e.message);
    const body = $('recordBody');
    if (body && /unauthorized/i.test(e.message)) {
      body.innerHTML = '<tr><td colspan="7" class="empty" style="color:var(--bad);padding:36px 20px">' +
        '<div style="font-size:1.1rem;font-weight:700;margin-bottom:8px">⚠️ Google Apps Script Authorization Failed (GATEWAY_SECRET মেলেনি)</div>' +
        '<div style="font-size:.88rem;color:var(--text);line-height:1.6;max-width:540px;margin:0 auto">' +
        (currentLang === 'bn' 
          ? 'Apps Script রিকোয়েস্টটি বাতিল করেছে কারণ Apps Script-এর <code>GATEWAY_SECRET</code>-এর সাথে এই অ্যাডমিন সিক্রেট মেলেনি। দয়া করে <strong>⚙️ সাধারণ সেটিংস</strong> ট্যাবে গিয়ে Secret কপি করে Apps Script-এর Script Properties-এ পেস্ট করে Deploy করুন।'
          : 'Apps Script rejected the request because the <code>GATEWAY_SECRET</code> does not match. Please switch to the <strong>⚙️ General Settings</strong> tab, copy the secret, paste it into Apps Script Script Properties and deploy.') +
        '</div></td></tr>';
    }
  }
}
$('reloadSheets').addEventListener('click',loadSheets);
$('sheetSelect').addEventListener('change',loadRows);
function updateSearchClearBtn() {
  const sInput = $('searchRows');
  const clearBtn = $('clearSearch');
  if (!clearBtn || !sInput) return;
  if (sInput.value.trim().length > 0) {
    clearBtn.classList.remove('hidden');
  } else {
    clearBtn.classList.add('hidden');
  }
}

const searchInput = $('searchRows');
const clearSearchBtn = $('clearSearch');
if (searchInput) {
  searchInput.addEventListener('input', () => {
    updateSearchClearBtn();
    drawTable();
  });
  searchInput.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && searchInput.value) {
      searchInput.value = '';
      updateSearchClearBtn();
      drawTable();
    }
  });
}
if (clearSearchBtn) {
  clearSearchBtn.addEventListener('click', () => {
    if (searchInput) {
      searchInput.value = '';
      searchInput.focus();
    }
    updateSearchClearBtn();
    drawTable();
  });
}

function highlightMatch(text, tokens) {
  if (!tokens || !tokens.length || text === null || text === undefined) {
    return document.createTextNode(String(text ?? ''));
  }
  const str = String(text ?? '');
  const escaped = tokens.map(t => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).filter(Boolean).join('|');
  if (!escaped) return document.createTextNode(str);
  try {
    const regex = new RegExp(`(${escaped})`, 'gi');
    const span = document.createElement('span');
    let lastIndex = 0;
    str.replace(regex, (match, p1, offset) => {
      if (offset > lastIndex) {
        span.appendChild(document.createTextNode(str.slice(lastIndex, offset)));
      }
      const mark = document.createElement('mark');
      mark.className = 'search-match';
      mark.textContent = match;
      span.appendChild(mark);
      lastIndex = offset + match.length;
      return match;
    });
    if (lastIndex < str.length) {
      span.appendChild(document.createTextNode(str.slice(lastIndex)));
    }
    return span;
  } catch(e) {
    return document.createTextNode(str);
  }
}
async function loadRows(){
  const name=$('sheetSelect').value;sheetData=null;
  $('addRecord').disabled=!name;
  if(!name){drawTable();return;}
  $('rowsStatus').textContent='Loading records…';
  try {sheetData=await request('/admin/sheets/rows?sheet='+encodeURIComponent(name));drawTable();}
  catch(e){$('rowsStatus').textContent=e.message;notice(e.message);drawTable();}
}
function mk(tag,text){const el=document.createElement(tag);el.textContent=String(text??'');return el;}
function drawTable(){
  const head=$('recordHead'),body=$('recordBody');head.replaceChildren();body.replaceChildren();
  if(!sheetData){body.append(mk('tr',''));$('rowCount').textContent='0 records';return;}
  const headers=sheetData.headers||[],all=sheetData.rows||[];
  const tr=document.createElement('tr');tr.append(mk('th','Row'));
  for(const h of headers) tr.append(mk('th',h));tr.append(mk('th','Actions'));head.append(tr);
  updateSearchClearBtn();
  const query=$('searchRows')?$('searchRows').value.trim():'';
  const tokens=query.toLowerCase().split(/\s+/).filter(Boolean);
  const filtered=all.filter(row=>{
    if(!tokens.length) return true;
    const combined=(row.rowIndex+' '+(row.values||[]).join(' ')).toLowerCase();
    return tokens.every(token=>combined.includes(token));
  });

  for(const row of filtered){
    const line=document.createElement('tr');
    const tdRow=document.createElement('td');tdRow.appendChild(highlightMatch(row.rowIndex,tokens));line.append(tdRow);
    for(const v of row.values){
      const td=document.createElement('td');
      td.appendChild(highlightMatch(v,tokens));
      line.append(td);
    }
    const actions=document.createElement('td');
    const edit=mk('button','Edit');edit.className='btn btn-light small';edit.style.marginRight='8px';edit.addEventListener('click',()=>editRow(row));
    const del=mk('button','Delete');del.className='btn btn-danger small';del.addEventListener('click',()=>deleteRow(row));
    actions.append(edit,del);line.append(actions);body.append(line);
  }
  if(!filtered.length){
    const line=document.createElement('tr'),cell=document.createElement('td');
    if(all.length>0 && query){
      cell.textContent=currentLang==='bn'?`"${query}" এর জন্য কোনো রেকর্ড পাওয়া যায়নি।`:`No matching records found for "${query}".`;
    }else{
      cell.textContent=all.length?'No matching records.':'This sheet has no records yet.';
    }
    cell.colSpan=headers.length+2;cell.className='empty';line.append(cell);body.append(line);
  }
  if(query){
    $('rowCount').textContent=currentLang==='bn'?`${filtered.length} / ${all.length} টি মিল রয়েছে`:`${filtered.length} of ${all.length} matching`;
  }else{
    $('rowCount').textContent=currentLang==='bn'?`${all.length} টি রেকর্ড`:`${all.length} records`;
  }
  $('rowsStatus').textContent=`Viewing: ${sheetData.sheet}`;
}
function editRow(row){openEditor(row);}
$('addRecord').addEventListener('click',()=>openEditor(null));
function openEditor(row){
  if(!sheetData)return;
  currentEdit=row;
  $('modalTitle').textContent=row?'Edit attendance record':'Add attendance record';
  $('modalSheet').textContent=`Sheet: ${sheetData.sheet}${row?' · Row '+row.rowIndex:''}`;
  const container=$('modalFields');container.replaceChildren();
  sheetData.headers.forEach((header,i)=>{
    const wrap=document.createElement('div'),label=mk('label',header),input=document.createElement('input');
    input.value=row?String(row.values[i]||''):header.toLowerCase()==='attendance'?'1':header.toLowerCase()==='date'?String(sheetData.sheet).slice(0,10):'';
    input.dataset.index=String(i);input.placeholder='Enter '+header;
    if(header.toLowerCase()==='sl'){input.disabled=true;input.value=row?row.values[i]:'Auto';}
    if(header.toLowerCase()==='id'){input.maxLength=13;input.inputMode='numeric';input.required=true;}
    if(header.toLowerCase()==='name')input.required=true;
    wrap.append(label,input);container.append(wrap);
  });
  $('editModal').classList.remove('hidden');
}
function closeEditor(){$('editModal').classList.add('hidden');currentEdit=null;}
$('closeModal').addEventListener('click',closeEditor);$('cancelModal').addEventListener('click',closeEditor);
$('editModal').addEventListener('click',e=>{if(e.target===$('editModal'))closeEditor();});
$('recordForm').addEventListener('submit',async e=>{
  e.preventDefault();if(!sheetData)return;
  const values=Array.from($('modalFields').querySelectorAll('[data-index]'),el=>el.disabled?'':el.value.trim());
  const headers=sheetData.headers.map(h=>h.toLowerCase()),idIndex=headers.indexOf('id'),nameIndex=headers.indexOf('name');
  if(idIndex>=0&&!/^\d{13}$/.test(values[idIndex]))return notice('Student ID must be exactly 13 digits.');
  if(nameIndex>=0&&values[nameIndex].length<2)return notice('Student name is required.');
  const btn=$('saveRecord');btn.disabled=true;btn.textContent='Saving…';
  try{
    await request('/admin/sheets/rows/'+(currentEdit?'edit':'add'),{method:'POST',body:{sheet:sheetData.sheet,rowIndex:currentEdit?.rowIndex||null,values}});
    closeEditor();await loadRows();notice('Record saved to Google Sheets.');
  }catch(err){notice(err.message)}finally{btn.disabled=false;btn.textContent='Save record';}
});
function deleteRow(row){currentDelete=row;$('confirmModal').classList.remove('hidden');}
$('cancelDelete').addEventListener('click',()=>{$('confirmModal').classList.add('hidden');currentDelete=null;});
$('confirmDelete').addEventListener('click',async()=>{
  if(!currentDelete||!sheetData)return;
  const btn=$('confirmDelete');btn.disabled=true;btn.textContent='Deleting…';
  try{
    await request('/admin/sheets/rows/delete',{method:'POST',body:{sheet:sheetData.sheet,rowIndex:currentDelete.rowIndex}});
    $('confirmModal').classList.add('hidden');currentDelete=null;
    await loadRows();notice('Record deleted from Google Sheets.');
  }catch(e){notice(e.message)}finally{btn.disabled=false;btn.textContent='Delete permanently';}
});
document.addEventListener('keydown',e=>{if(e.key==='Escape'){$('confirmModal').classList.add('hidden');closeEditor();}});
if(token)initialize();else showApp(false);

const toggleLoginPassword = $('toggleLoginPassword');
if (toggleLoginPassword) {
  toggleLoginPassword.addEventListener('click', () => {
    const pwdInput = $('loginPassword');
    const isPass = pwdInput.type === 'password';
    pwdInput.type = isPass ? 'text' : 'password';
    $('eyeIcon').classList.toggle('hidden', isPass);
    $('eyeSlashIcon').classList.toggle('hidden', !isPass);
    toggleLoginPassword.setAttribute('aria-label', isPass ? 'Hide password' : 'Show password');
    toggleLoginPassword.setAttribute('title', isPass ? 'Hide password' : 'Show password');
  });
}


// ==========================================
// 🌐 Language & 🌓 Theme Support (Default: English)
// ==========================================
const T_ADMIN = {
  en: {
    adminTitle: "Administrator • Smart Attendance",
    brandSub: "/ Admin",
    publicSite: "← Public site",
    logout: "Logout",
    loginTitle: "Administrator sign in",
    loginSubtitle: "Access is restricted to administrator accounts configured on the backend.",
    loginEmailLabel: "Administrator email",
    loginEmailPh: "admin@example.com",
    loginPasswordLabel: "Password",
    loginPasswordPh: "Enter your password",
    loginBtn: "Sign in securely →",
    loginHint: "No account? Contact the site owner. Public registration is disabled.",
    dashTitle: "Administrator Dashboard",
    dashSubtitle: "Manage your Google Sheets connection, attendance schedule, email confirmations and records.",
    tabSettings: "⚙️ General Settings",
    tabRecords: "📊 Attendance Records",
    sheetsCardTitle: "Google Sheets Integration",
    scriptUrlLabel: "Apps Script Web App URL",
    sheetUrlLabel: "Google Sheet URL",
    openSheetLink: "Open in Google Sheets ↗",
    scriptSecretLabel: "Apps Script gateway secret",
    generateBtn: "Generate",
    copyBtn: "Copy",
    testConnBtn: "Test Connection",
    testConnHint: "Verify Apps Script & Secret connectivity",
    guideTitle: "Where and how to add this secret?",
    scheduleCardTitle: "Attendance Schedule",
    enableTimer: "Enable attendance timer",
    multiClass: "Multiple class schedules",
    startTimeLabel: "Start time",
    endTimeLabel: "End time",
    addClassBtn: "+ Add class",
    emailCardTitle: "Student Confirmation Emails",
    enableEmail: "Enable automatic email notifications",
    senderNameLabel: "Sender display name",
    senderEmailLabel: "Sender Gmail address",
    appPasswordLabel: "Google App Password",
    testEmailBtn: "✉ Send test email",
    testEmailHint: "Save settings first, then test.",
    studentEmailPreview: "Student email preview",
    saveConfigBtn: "💾 Save configuration",
    recordsTitle: "Google Sheet Records",
    selectSheetLabel: "Select sheet / class tab",
    searchRecordsLabel: "Search records",
    clearSearchTitle: "Clear search",
    searchPh: "Name, ID, email, date…",
    addRecordBtn: "+ Add record",
    reloadSheetsBtn: "↻ Refresh",
    previewGreeting: "Hello, Rabbi",
    previewSummary: "Your attendance has been successfully recorded. Here is your confirmation summary.",
    emailSecurityHint: "Student ID is masked in email. Notification is attempted only after Google Sheets confirms attendance. Email delivery failure never deletes a saved attendance record.",
    dark: "🌙",
    light: "☀️"
  },
  bn: {
    adminTitle: "প্রশাসক • স্মার্ট অ্যাটেনডেন্স",
    brandSub: "/ অ্যাডমিন",
    publicSite: "← মূল ওয়েবসাইট",
    logout: "লগআউট",
    loginTitle: "প্রশাসক সাইন ইন",
    loginSubtitle: "শুধুমাত্র ব্যাকএন্ডে কনফিগার করা অ্যাডমিন অ্যাকাউন্টের জন্য সংরক্ষিত।",
    loginEmailLabel: "প্রশাসকের ইমেইল",
    loginEmailPh: "admin@example.com",
    loginPasswordLabel: "পাসওয়ার্ড",
    loginPasswordPh: "আপনার পাসওয়ার্ড লিখুন",
    loginBtn: "নিরাপদে সাইন ইন করুন →",
    loginHint: "অ্যাকাউন্ট নেই? সাইটের সত্ত্বাধিকারীর সাথে যোগাযোগ করুন। উন্মুক্ত রেজিস্ট্রেশন বন্ধ।",
    dashTitle: "প্রশাসক ড্যাশবোর্ড",
    dashSubtitle: "গুগল শিট কানেকশন, ক্লাসের সময়সূচী, ইমেইল নোটিফিকেশন ও রেকর্ড পরিচালনা করুন।",
    tabSettings: "⚙️ সাধারণ সেটিংস",
    tabRecords: "📊 হাজিরা রেকর্ড",
    sheetsCardTitle: "গুগল শিট ইন্টিগ্রেশন",
    scriptUrlLabel: "অ্যাপস স্ক্রিপ্ট ওয়েব অ্যাপ URL",
    sheetUrlLabel: "গুগল স্প্রেডশিট URL",
    openSheetLink: "গুগল শিট খুলুন ↗",
    scriptSecretLabel: "অ্যাপস স্ক্রিপ্ট সিক্রেট কি",
    generateBtn: "জেনারেট",
    copyBtn: "কপি",
    testConnBtn: "কানেকশন টেস্ট করুন",
    testConnHint: "অ্যাপস স্ক্রিপ্ট ও সিক্রেটের সংযোগ পরীক্ষা করুন",
    guideTitle: "Secret টি কোথায় ও কীভাবে যুক্ত করবেন?",
    scheduleCardTitle: "হাজিরার সময়সূচী",
    enableTimer: "হাজিরা নেওয়ার সময়সীমা সক্রিয় করুন",
    multiClass: "একাধিক ক্লাসের সময়সূচী ব্যবহার করুন",
    startTimeLabel: "শুরুর সময়",
    endTimeLabel: "শেষের সময়",
    addClassBtn: "+ ক্লাস যোগ করুন",
    emailCardTitle: "শিক্ষার্থীদের কনফার্মেশন ইমেইল",
    enableEmail: "স্বয়ংক্রিয় ইমেইল নোটিফিকেশন চালু করুন",
    senderNameLabel: "প্রেরকের নাম",
    senderEmailLabel: "প্রেরকের জিমেইল ঠিকানা",
    appPasswordLabel: "গুগল অ্যাপ পাসওয়ার্ড",
    testEmailBtn: "✉ টেস্ট ইমেইল পাঠান",
    testEmailHint: "আগে সেটিংস সেভ করুন, তারপর টেস্ট করুন।",
    studentEmailPreview: "ইমেইল নোটিফিকেশন প্রিভিউ",
    saveConfigBtn: "💾 কনফিগারেশন সংরক্ষণ করুন",
    recordsTitle: "গুগল শিট রেকর্ডস",
    selectSheetLabel: "শিট / ক্লাসের ট্যাব নির্বাচন করুন",
    searchRecordsLabel: "রেকর্ড খুঁজুন",
    clearSearchTitle: "সার্চ মুছুন",
    searchPh: "নাম, আইডি, ইমেইল, তারিখ দিয়ে খুঁজুন…",
    addRecordBtn: "+ নতুন রেকর্ড যোগ করুন",
    reloadSheetsBtn: "↻ রিফ্রেশ",
    previewGreeting: "হ্যালো, রাব্বি",
    previewSummary: "আপনার উপস্থিতি সফলভাবে সংরক্ষিত হয়েছে। এখানে আপনার নিশ্চিতকরণ সারাংশ।",
    emailSecurityHint: "ইমেইলে স্টুডেন্ট আইডি গোপন রাখা হয়। গুগল শিটে উপস্থিতি নিশ্চিত হওয়ার পরই নোটিফিকেশন পাঠানো হয়।",
    dark: "🌙",
    light: "☀️"
  }
};

let currentLang = "en";
try { currentLang = localStorage.getItem("lang") || "en"; } catch(e) {}

function applyAdminLang() {
  document.documentElement.lang = currentLang;
  const dict = T_ADMIN[currentLang] || T_ADMIN.en;
  document.querySelectorAll('[data-t]').forEach(el => {
    const k = el.dataset.t;
    if (dict[k]) el.textContent = dict[k];
  });
  document.querySelectorAll('[data-p]').forEach(el => {
    const k = el.dataset.p;
    if (dict[k]) el.placeholder = dict[k];
  });
  const langText = $('adminLangText');
  if (langText) langText.textContent = currentLang === 'en' ? 'বাংলা' : 'English';
  const clearBtn = $('clearSearch');
  if (clearBtn && dict.clearSearchTitle) clearBtn.title = dict.clearSearchTitle;
  setAdminTheme(document.documentElement.dataset.theme || 'light');
  document.title = dict.adminTitle;
  validateScriptUrl();
  validateSheetUrl();
  updateScheduleBadge();
}

function setAdminTheme(m) {
  document.documentElement.dataset.theme = m;
  const themeBtn = $('adminTheme');
  if (themeBtn) {
    const isDark = m === 'dark';
    const label = isDark 
      ? (currentLang === 'bn' ? 'লাইট' : 'Light') 
      : (currentLang === 'bn' ? 'ডার্ক' : 'Dark');
    const icon = isDark ? '☀️' : '🌙';
    themeBtn.innerHTML = `<span style="font-size:15px;line-height:1">${icon}</span> <span style="font-size:12px;font-weight:600;letter-spacing:.2px">${label}</span>`;
    themeBtn.title = isDark ? 'Switch to Light Mode' : 'Switch to Dark Mode';
  }
  try { localStorage.setItem('theme', m); } catch(e) {}
}

const adminLangBtn = $('adminLang');
if (adminLangBtn) {
  adminLangBtn.addEventListener('click', () => {
    currentLang = currentLang === 'en' ? 'bn' : 'en';
    try { localStorage.setItem('lang', currentLang); } catch(e) {}
    applyAdminLang();
    if (sheetData) drawTable();
  });
}

const adminThemeBtn = $('adminTheme');
if (adminThemeBtn) {
  adminThemeBtn.addEventListener('click', () => {
    const nextTheme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    setAdminTheme(nextTheme);
  });
}

// Initial theme & language setup
let savedTheme = null;
try { savedTheme = localStorage.getItem('theme'); } catch(e) {}
setAdminTheme(savedTheme || (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'));
applyAdminLang();
