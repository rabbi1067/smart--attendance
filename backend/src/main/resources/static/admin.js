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
  t.replaceChildren();
  const symbol=document.createElement('span');symbol.textContent=icon;symbol.style.fontSize='1.05rem';
  const msg=document.createElement('span');msg.textContent=String(message);
  t.append(symbol,msg);
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
  if (!show) {
    if ($('loginPassword')) $('loginPassword').value = '';
    if ($('loginEmail')) $('loginEmail').value = '';
    if ($('loginForm')) $('loginForm').reset();
  }
}
function logout(showMessage=true) {
  token = '';
  userEmail = '';
  try {
    sessionStorage.removeItem('admin_token');
    sessionStorage.removeItem('admin_email');
    localStorage.removeItem('admin_token');
    localStorage.removeItem('admin_email');
    localStorage.removeItem('token');
  } catch(e) {}
  if ($('loginForm')) $('loginForm').reset();
  if ($('loginEmail')) $('loginEmail').value = '';
  if ($('loginPassword')) $('loginPassword').value = '';
  showApp(false);
  if (showMessage) {
    notice(currentLang === 'bn' ? 'সফলভাবে সাইন আউট করা হয়েছে।' : 'Signed out successfully.');
  }
}
$('logout').addEventListener('click',()=>logout());
$('loginForm').addEventListener('submit',async e=>{
  e.preventDefault(); $('loginError').classList.add('hidden');
  const emailInput = $('loginEmail').value.trim();
  const passwordInput = $('loginPassword').value;
  if (!emailInput || !passwordInput) {
    $('loginError').textContent = currentLang === 'bn' ? 'ইমেইল এবং পাসওয়ার্ড আবশ্যক।' : 'Email and password are required.';
    $('loginError').classList.remove('hidden');
    return;
  }
  const btn=$('loginBtn');btn.disabled=true;btn.textContent=currentLang==='bn'?'সাইন ইন করা হচ্ছে…':'Signing in…';
  try {
    const data=await request('/admin/auth/login',{method:'POST',protectedRoute:false,body:{email:emailInput,password:passwordInput}});
    token=data.token;userEmail=data.email;
    sessionStorage.setItem('admin_token',token);sessionStorage.setItem('admin_email',userEmail);
    $('loginPassword').value='';
    await initialize();
  }catch(err){
    $('loginError').textContent = /account_not_found|credentials|401/.test(err.message) ? (currentLang==='bn'?'অ্যাকাউন্ট বা পাসওয়ার্ড সঠিক নয়।':'Account not found or incorrect email/password.') : err.message;
    $('loginError').classList.remove('hidden'); notice($('loginError').textContent);
  } finally {btn.disabled=false;btn.textContent=currentLang==='bn'?'নিরাপদে সাইন ইন করুন →':'Sign in securely →';}
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
  const checkedRadio = document.querySelector('input[name="mailProvider"]:checked');
  const provider = checkedRadio ? checkedRadio.value : 'EMAILJS';
  $('smtpFields').classList.toggle('hidden',provider!=='SMTP');
  $('apiMailFields').classList.toggle('hidden',!['RESEND','BREVO'].includes(provider));
  if ($('emailJsFields')) $('emailJsFields').classList.toggle('hidden',provider!=='EMAILJS');
  $('testNotification').disabled=!on;
}
$('mailEnabled').addEventListener('change',mailUi);
for(const radio of document.querySelectorAll('input[name="mailProvider"]'))radio.addEventListener('change',mailUi);
$('testNotification').addEventListener('click', async()=>{
  const button=$('testNotification');button.disabled=true;
  button.textContent=currentLang==='bn'?'টেস্ট পাঠানো হচ্ছে…':'Sending test…';
  try{
    // Tests use the saved server-side provider configuration only; no browser-side mail dispatch.
    const data=await request('/admin/notifications/test',{method:'POST'});
    if(data.emailStatus==='sent'){
      notice(data.message || (currentLang==='bn'?'অ্যাডমিন ইমেইলে টেস্ট ইমেইল সফলভাবে পাঠানো হয়েছে।':'Test email sent to your administrator email address.'));
    } else {
      notice(data.message || (currentLang==='bn'?'টেস্ট ইমেইল পাঠানো যায়নি। সেটিংস ও অনুমতি পরীক্ষা করুন।':'Test email failed. Check sender settings or SMTP hosting restrictions.'));
    }
  }catch(e){
    notice((currentLang==='bn'?'ইমেইল পাঠাতে ব্যর্থ: ':'Failed to send test email: ')+(e.message||'network'));
  }finally{
    button.textContent=currentLang==='bn'?'✉ টেস্ট ইমেইল পাঠান':'✉ Send test email';
    mailUi();
  }
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

async function loadConfig() {
  const cfg=await request('/admin/config');
  updateScheduleBadge();
  $('ownerStatus').textContent='● SHARED LIVE CONFIGURATION';
  $('ownerStatus').classList.remove('off');
  const sch=cfg.SCHEDULE_CONFIG||{};
  $('scheduleEnabled').checked=!!sch.ENABLED;
  $('useClasses').checked=!!sch.USE_CLASSES;
  $('startTime').value=from12(sch.START_TIME||'10:00 PM');
  $('endTime').value=from12(sch.END_TIME||'11:00 PM');
  classState=(sch.CLASSES||[]).map(c=>({...c}));drawClasses();updateScheduleSections();updateScheduleBadge();
  const m=cfg.EMAIL_CONFIG||{};
  $('mailEnabled').checked=!!m.ENABLED;
  const provider=['SMTP','EMAILJS','RESEND','BREVO'].includes(m.PROVIDER)?m.PROVIDER:'EMAILJS';
  const targetRadio = document.querySelector(`input[name="mailProvider"][value="${provider}"]`);
  if (targetRadio) targetRadio.checked = true;
  $('senderEmail').value=m.SENDER_EMAIL||'';
  $('apiSenderEmail').value=m.SENDER_EMAIL||'';
  $('providerApiKey').value='';
  $('providerKeyStatus').textContent=(m.RESEND_KEY_CONFIGURED?'Resend API key saved. ':'')+(m.BREVO_KEY_CONFIGURED?'Brevo API key saved. ':'')+'Leave blank to keep the stored key.';
  $('senderName').value=m.SENDER_NAME||'Smart Attendance';
  $('appPassword').value='';$('clearAppPassword').checked=false;
  const cek=$('clearEmailJsKeys'); if (cek) cek.checked=false;
  if ($('emailJsServiceId')) $('emailJsServiceId').value=m.EMAILJS_SERVICE_ID||'';
  if ($('emailJsTemplateId')) $('emailJsTemplateId').value=m.EMAILJS_TEMPLATE_ID||'';
  if ($('emailJsPublicKey')) $('emailJsPublicKey').value=m.EMAILJS_PUBLIC_KEY||'';
  if ($('emailJsPrivateKey')) {
    $('emailJsPrivateKey').value='';
    $('emailJsPrivateKey').placeholder = m.EMAILJS_PRIVATE_KEY_CONFIGURED ? (currentLang==='bn'?'✓ এনক্রিপ্টেড সেভ আছে · অপরিবর্তিত রাখতে খালি রাখুন':'✓ Saved encrypted · blank keeps saved key') : (currentLang==='bn'?'strict mode বন্ধ থাকলে খালি রাখুন':'Leave blank if strict mode is disabled in EmailJS');
  }
  $('passwordStatus').textContent=m.APP_PASSWORD_CONFIGURED?'✓ App Password stored securely':'No App Password saved';
  $('passwordStatus').classList.toggle('off',!m.APP_PASSWORD_CONFIGURED);
  mailUi();
}
$('configForm').addEventListener('submit',async e=>{
  e.preventDefault();const btn=$('saveConfig');btn.disabled=true;btn.textContent=currentLang==='bn'?'সংরক্ষণ করা হচ্ছে…':'Saving…';
  try {
    const checkedRadio = document.querySelector('input[name="mailProvider"]:checked');
    const activeProvider = checkedRadio ? checkedRadio.value : 'EMAILJS';
    const data={
      SCHEDULE_CONFIG:{
        ENABLED:$('scheduleEnabled').checked,
        USE_CLASSES:$('useClasses').checked,
        START_TIME:to12($('startTime').value),
        END_TIME:to12($('endTime').value),
        CLASSES:classState.map(c=>({name:c.name.trim(),start:c.start,end:c.end}))
      },
      EMAIL_CONFIG:{
        ENABLED:$('mailEnabled').checked,
        PROVIDER:activeProvider,
        SENDER_EMAIL: ['RESEND','BREVO'].includes(activeProvider) ? $('apiSenderEmail').value.trim() : $('senderEmail').value.trim(),
        SENDER_NAME:$('senderName').value.trim(),
        APP_PASSWORD:$('appPassword').value.replace(/\s+/g,''),
        CLEAR_APP_PASSWORD:$('clearAppPassword').checked,
        RESEND_API_KEY:activeProvider==='RESEND' ? $('providerApiKey').value.trim() : '',
        BREVO_API_KEY:activeProvider==='BREVO' ? $('providerApiKey').value.trim() : '',
        EMAILJS_SERVICE_ID:$('emailJsServiceId') ? $('emailJsServiceId').value.trim() : '',
        EMAILJS_TEMPLATE_ID:$('emailJsTemplateId') ? $('emailJsTemplateId').value.trim() : '',
        EMAILJS_PUBLIC_KEY:$('emailJsPublicKey') ? $('emailJsPublicKey').value.trim() : '',
        EMAILJS_PRIVATE_KEY:$('emailJsPrivateKey') ? $('emailJsPrivateKey').value.trim() : '',
        CLEAR_EMAILJS_KEYS:$('clearEmailJsKeys') ? $('clearEmailJsKeys').checked : false
      }
    };
    await request('/admin/config',{method:'PUT',body:data});
    await loadConfig();
    await refreshHealth();
    notice(currentLang==='bn'?'কনফিগারেশন সফলভাবে সেভ হয়েছে।':'Configuration saved in MongoDB successfully.');
  }catch(err){notice(err.message)}finally{btn.disabled=false;btn.textContent=currentLang==='bn'?'💾 কনফিগারেশন সংরক্ষণ করুন':'💾 Save configuration';}
});
async function refreshHealth(){
  const status=$('dbHealth'), detail=$('dbHealthDetail');
  if(!status)return;
  status.textContent='Checking…';
  try {
    const response=await fetch(API+'/public/db-status',{cache:'no-store'});
    const data=await response.json();
    if(!response.ok || !data.connected)throw Error('Database not connected');
    const dot=document.createElement('span');dot.className='db-dot ok';
    status.replaceChildren(dot,document.createTextNode('Connected'));
    $('recordMetric').textContent=Number(data.mongoCount||0).toLocaleString();
    detail.textContent='Database read verified';
  } catch(e) {
    const dot=document.createElement('span');dot.className='db-dot fail';
    status.replaceChildren(dot,document.createTextNode('Unavailable'));
    detail.textContent='Operations require MongoDB connectivity';
    $('recordMetric').textContent='—';
  }
  const provider=document.querySelector('input[name="mailProvider"]:checked')?.value||'EMAILJS';
  $('deliveryMetric').textContent=$('mailEnabled').checked?'Enabled · '+provider:'Disabled';
  $('lastHealth').textContent='Status checked '+new Date().toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
}
$('refreshHealth').addEventListener('click',refreshHealth);
async function initialize(){
  try {
    await loadConfig();
    showApp(true);
    await refreshHealth();
  } catch(e) {
    logout(false);
  }
}
async function loadSheets(){
  $('rowsStatus').textContent=currentLang==='bn'?'রেকর্ড লোড হচ্ছে…':'Loading attendance records…';
  try {
    const data=await request('/admin/sheets');sheets=data.sheets||[];
    const sel=$('sheetSelect'),selected=sel.value;sel.replaceChildren(new Option('Choose a tab',''));
    for(const s of sheets) sel.add(new Option(`${s.name} (${s.count})`,s.name));
    if(sheets.some(s=>s.name===selected)) sel.value=selected;
    else if(sheets.length) sel.value=sheets[0].name;
    if(sel.value) await loadRows();
    await refreshHealth();
    if(!sel.value){sheetData=null;drawTable();$('rowsStatus').textContent='No sheet tabs yet.';}
  }catch(e){
    $('rowsStatus').textContent=e.message;notice(e.message);
    const body=$('recordBody');
    if(body){const row=document.createElement('tr'),cell=document.createElement('td');cell.colSpan=9;cell.className='empty';cell.textContent='Database records could not be loaded. Please check MongoDB Atlas connectivity and retry.';row.append(cell);body.replaceChildren(row);}
  }
}
$('reloadSheets').addEventListener('click',loadSheets);
$('sheetSelect').addEventListener('change',()=>{ currentPage=1; loadRows(); });
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
    currentPage = 1;
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
let currentPage = 1;
let pageSize = 10;
try {
  const savedSize = localStorage.getItem('admin_page_size');
  if (savedSize) pageSize = savedSize === 'all' ? 'all' : parseInt(savedSize, 10) || 10;
} catch(e) {}

function renderPagination(totalItems) {
  const bar = $('paginationBar');
  const info = $('pageRangeInfo');
  const nav = $('paginationNav');
  const sizeSelect = $('pageSizeSelect');
  if (!bar || !info || !nav) return;

  if (sizeSelect) sizeSelect.value = String(pageSize);

  if (totalItems === 0) {
    info.textContent = currentLang === 'bn' ? 'কোনো রেকর্ড নেই' : '0 records';
    nav.replaceChildren();
    return;
  }

  const totalPages = pageSize === 'all' ? 1 : Math.max(1, Math.ceil(totalItems / pageSize));
  if (currentPage > totalPages) currentPage = totalPages;
  if (currentPage < 1) currentPage = 1;

  const start = pageSize === 'all' ? 1 : (currentPage - 1) * pageSize + 1;
  const end = pageSize === 'all' ? totalItems : Math.min(currentPage * pageSize, totalItems);

  if (currentLang === 'bn') {
    const toBn = n => Number(n).toLocaleString('bn-BD');
    info.textContent = `মোট ${toBn(totalItems)} টির মধ্যে ${toBn(start)}–${toBn(end)} দেখানো হচ্ছে (পৃষ্ঠা ${toBn(currentPage)}/${toBn(totalPages)})`;
  } else {
    info.textContent = `Showing ${start}–${end} of ${totalItems} records (Page ${currentPage} of ${totalPages})`;
  }

  nav.replaceChildren();
  if (totalPages <= 1) return;

  const addBtn = (label, targetPage, disabled = false, active = false, title = '') => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'page-btn' + (active ? ' active' : '');
    btn.textContent = label;
    if (title) btn.title = title;
    if (disabled) btn.disabled = true;
    else {
      btn.addEventListener('click', () => {
        currentPage = targetPage;
        drawTable();
      });
    }
    nav.appendChild(btn);
  };

  const addEllipsis = () => {
    const el = document.createElement('span');
    el.className = 'page-ellipsis';
    el.textContent = '…';
    nav.appendChild(el);
  };

  addBtn('⏮', 1, currentPage === 1, false, currentLang === 'bn' ? 'প্রথম পাতা' : 'First page');
  addBtn('◀', currentPage - 1, currentPage === 1, false, currentLang === 'bn' ? 'আগের পাতা' : 'Previous page');

  const toPNum = n => currentLang === 'bn' ? Number(n).toLocaleString('bn-BD') : String(n);
  if (totalPages <= 7) {
    for (let p = 1; p <= totalPages; p++) {
      addBtn(toPNum(p), p, false, p === currentPage);
    }
  } else {
    addBtn(toPNum(1), 1, false, currentPage === 1);
    if (currentPage > 3) addEllipsis();

    const startP = Math.max(2, currentPage - 1);
    const endP = Math.min(totalPages - 1, currentPage + 1);
    for (let p = startP; p <= endP; p++) {
      addBtn(toPNum(p), p, false, p === currentPage);
    }

    if (currentPage < totalPages - 2) addEllipsis();
    addBtn(toPNum(totalPages), totalPages, false, currentPage === totalPages);
  }

  addBtn('▶', currentPage + 1, currentPage === totalPages, false, currentLang === 'bn' ? 'পরের পাতা' : 'Next page');
  addBtn('⏭', totalPages, currentPage === totalPages, false, currentLang === 'bn' ? 'শেষ পাতা' : 'Last page');
}

if ($('pageSizeSelect')) {
  $('pageSizeSelect').addEventListener('change', (e) => {
    pageSize = e.target.value === 'all' ? 'all' : parseInt(e.target.value, 10) || 10;
    try { localStorage.setItem('admin_page_size', String(pageSize)); } catch(err) {}
    currentPage = 1;
    drawTable();
  });
}

function drawTable(){
  const head=$('recordHead'),body=$('recordBody');head.replaceChildren();body.replaceChildren();
  if(!sheetData){
    body.append(mk('tr',''));
    $('rowCount').textContent='0 records';
    renderPagination(0);
    return;
  }
  const headers=sheetData.headers||[],all=sheetData.rows||[];
  const tr=document.createElement('tr');
  tr.append(mk('th', currentLang === 'bn' ? 'সারি' : 'Row'));
  for(const h of headers) {
    let title = h;
    if (currentLang === 'bn') {
      const hMap = {
        'SL': 'ক্রমিক',
        'Date': 'তারিখ',
        'Name': 'নাম',
        'ID': 'আইডি',
        'Email': 'ইমেইল',
        'Attendance': 'উপস্থিতি',
        'Local Time': 'সময়'
      };
      title = hMap[h] || h;
    }
    tr.append(mk('th', title));
  }
  tr.append(mk('th', currentLang === 'bn' ? 'অ্যাকশন' : 'Actions'));
  head.append(tr);
  updateSearchClearBtn();
  const query=$('searchRows')?$('searchRows').value.trim():'';
  const tokens=query.toLowerCase().split(/\s+/).filter(Boolean);
  const filtered=all.filter(row=>{
    if(!tokens.length) return true;
    const combined=(row.rowIndex+' '+(row.values||[]).join(' ')).toLowerCase();
    return tokens.every(token=>combined.includes(token));
  });

  const totalItems = filtered.length;
  const totalPages = pageSize === 'all' ? 1 : Math.max(1, Math.ceil(totalItems / pageSize));
  if (currentPage > totalPages) currentPage = totalPages;
  if (currentPage < 1) currentPage = 1;

  const startIndex = pageSize === 'all' ? 0 : (currentPage - 1) * pageSize;
  const endIndex = pageSize === 'all' ? totalItems : Math.min(startIndex + pageSize, totalItems);
  const pageRows = filtered.slice(startIndex, endIndex);

  for(const row of pageRows){
    const line=document.createElement('tr');
    const tdRow=document.createElement('td');
    const rIdxStr = currentLang === 'bn' ? Number(row.rowIndex).toLocaleString('bn-BD') : String(row.rowIndex);
    tdRow.appendChild(highlightMatch(rIdxStr,tokens));
    line.append(tdRow);
    for(const v of row.values){
      const td=document.createElement('td');
      td.appendChild(highlightMatch(v,tokens));
      line.append(td);
    }
    const actions=document.createElement('td');
    const edit=mk('button', currentLang === 'bn' ? 'সম্পাদনা' : 'Edit');
    edit.className='btn btn-light small';
    edit.style.marginRight='8px';
    edit.addEventListener('click',()=>editRow(row));

    const del=mk('button', currentLang === 'bn' ? 'মুছুন' : 'Delete');
    del.className='btn btn-danger small';
    del.addEventListener('click',()=>deleteRow(row));
    actions.append(edit,del);
    line.append(actions);
    body.append(line);
  }
  if(!filtered.length){
    const line=document.createElement('tr'),cell=document.createElement('td');
    if(all.length>0 && query){
      cell.textContent=currentLang==='bn'?'"'+query+'" এর জন্য কোনো রেকর্ড পাওয়া যায়নি।':'No matching records found for "'+query+'".';
    }else{
      cell.textContent=all.length?'No matching records.':'This sheet has no records yet.';
    }
    cell.colSpan=headers.length+2;cell.className='empty';line.append(cell);body.append(line);
  }
  if(query){
    $('rowCount').textContent=currentLang==='bn'?filtered.length+' / '+all.length+' টি মিল রয়েছে':filtered.length+' of '+all.length+' matching';
  }else{
    $('rowCount').textContent=currentLang==='bn'?all.length+' টি রেকর্ড':all.length+' records';
  }
  $('rowsStatus').textContent='Viewing: '+sheetData.sheet;
  renderPagination(totalItems);
}
function editRow(row){openEditor(row);}
$('addRecord').addEventListener('click',()=>openEditor(null));
function todayDhakaISO() {
  const parts=new Intl.DateTimeFormat('en-US',{timeZone:'Asia/Dhaka',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date());
  const obj=Object.fromEntries(parts.map(v=>[v.type,v.value]));
  return `${obj.year}-${obj.month}-${obj.day}`;
}
function nowLocalTimeStr() {
  return new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Dhaka',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: true
  }).format(new Date());
}
function openEditor(row){
  if(!sheetData)return;
  currentEdit=row;
  $('modalTitle').textContent=row?'Edit attendance record':'Add attendance record';
  $('modalSheet').textContent=`Sheet: ${sheetData.sheet}${row?' · Row '+row.rowIndex:''}`;
  const container=$('modalFields');container.replaceChildren();
  sheetData.headers.forEach((header,i)=>{
    const wrap=document.createElement('div'),label=mk('label',header),input=document.createElement('input');
    const hLower=header.toLowerCase();
    input.value=row?String(row.values[i]||''):hLower==='attendance'?'1':hLower==='date'?todayDhakaISO():(hLower==='local time'||hLower==='time')?nowLocalTimeStr():'';
    input.dataset.index=String(i);input.placeholder='Enter '+header;
    if(hLower==='sl'){input.disabled=true;input.value=row?row.values[i]:'Auto';}
    if(hLower==='id'){input.maxLength=13;input.inputMode='numeric';input.required=true;}
    if(hLower==='date'){
      input.type='date';input.required=true;
      if(!row){
        input.value=todayDhakaISO();
        input.readOnly=true;
        input.tabIndex=-1;
        input.style.pointerEvents='none';
        input.style.background='var(--bg)';
        input.style.opacity='0.85';
        input.style.cursor='not-allowed';
      }
    }
    if(hLower==='local time'||hLower==='time'){
      if(!row){
        input.value=nowLocalTimeStr();
        input.readOnly=true;
        input.tabIndex=-1;
        input.style.pointerEvents='none';
        input.style.background='var(--bg)';
        input.style.opacity='0.85';
        input.style.cursor='not-allowed';
      }
    }
    if(hLower==='email'){input.type='email';input.maxLength=100;}
    if(hLower==='name')input.required=true;
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
  if(!currentEdit){
    const dateIdx=headers.indexOf('date');
    const timeIdx=headers.findIndex(h=>h==='local time'||h==='time');
    if(dateIdx>=0)values[dateIdx]=todayDhakaISO();
    if(timeIdx>=0)values[timeIdx]=nowLocalTimeStr();
  }
  const btn=$('saveRecord');btn.disabled=true;btn.textContent='Saving…';
  try{
    let targetSheet=sheetData.sheet;
    if(!currentEdit&&targetSheet!=='All Records'&&!targetSheet.startsWith(todayDhakaISO())){
      targetSheet='All Records';
    }
    await request('/admin/sheets/rows/'+(currentEdit?'edit':'add'),{method:'POST',body:{sheet:targetSheet,recordId:currentEdit?.recordId||null,values}});
    closeEditor();await loadSheets();notice('✓ Record saved to MongoDB.');
  }catch(err){notice(err.message)}finally{btn.disabled=false;btn.textContent='Save record';}
});
function deleteRow(row){currentDelete=row;$('confirmModal').classList.remove('hidden');}
$('cancelDelete').addEventListener('click',()=>{$('confirmModal').classList.add('hidden');currentDelete=null;});
$('confirmDelete').addEventListener('click',async()=>{
  if(!currentDelete||!sheetData)return;
  const btn=$('confirmDelete');btn.disabled=true;btn.textContent='Deleting…';
  try{
    await request('/admin/sheets/rows/delete',{method:'POST',body:{sheet:sheetData.sheet,recordId:currentDelete.recordId}});
    $('confirmModal').classList.add('hidden');currentDelete=null;
    await loadSheets();notice('✓ Record deleted from MongoDB.');
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
    dashSubtitle: "Manage MongoDB records, schedules and student email notifications.",
    tabSettings: "⚙️ General Settings",
    tabRecords: "📊 Attendance Records",
    scheduleCardTitle: "Attendance Schedule",
    enableTimer: "Enable attendance timer",
    multiClass: "Multiple class schedules",
    startTimeLabel: "Start time",
    endTimeLabel: "End time",
    addClassBtn: "+ Add class",
    emailCardTitle: "Student Confirmation Emails",
    enableEmail: "Enable automatic email notifications",
    providerEmailJs: "EmailJS API (100% Free Frontend & Backend API)",
    providerEmailJsHint: "100% Free · 200 free emails/month forever. Sent from the backend. Zero port blocking.",
    providerSmtp: "Gmail SMTP",
    providerSmtpHint: "Gmail + App Password · Requires VPS or paid Render; port 587 is blocked on Render Free.",
    smtpRenderWarnTitle: "⚠️ Render Free Server Alert",
    smtpRenderWarnDesc: "Render Free tier blocks outbound SMTP ports (587, 465). Gmail SMTP will NOT connect on Render Free. Please select EmailJS API or another HTTPS provider (Resend / Brevo).",
    emailJsHelpTitle: "💡 100% Free Frontend & Backend Email:",
    emailJsHelpDesc: "Create a free account at emailjs.com (no credit card needed). Add your Gmail service, create an email template, then paste the Service ID, Template ID, and Public Key below.",
    emailJsServiceIdLabel: "EmailJS Service ID",
    emailJsTemplateIdLabel: "EmailJS Template ID",
    emailJsPublicKeyLabel: "EmailJS Public Key (User ID)",
    emailJsPrivateKeyLabel: "EmailJS Private Key / Access Token (Optional)",
    senderNameLabel: "Sender display name",
    senderEmailLabel: "Sender Gmail address",
    clearEmailJsKeysLabel: "Clear saved EmailJS keys",
    appPasswordLabel: "Google App Password",
    testEmailBtn: "✉ Send test email",
    testEmailHint: "Save settings first, then test.",
    studentEmailPreview: "Student email preview",
    saveConfigBtn: "💾 Save configuration",
    recordsTitle: "MongoDB Attendance Records",
    selectSheetLabel: "Select date / class group",
    searchRecordsLabel: "Search records",
    clearSearchTitle: "Clear search",
    searchPh: "Name, ID, email, date…",
    addRecordBtn: "+ Add record",
    reloadSheetsBtn: "↻ Refresh",
    previewGreeting: "Hello, Rabbi",
    previewSummary: "Your attendance has been successfully recorded. Here is your confirmation summary.",
    emailSecurityHint: "Student ID is masked in email. Notifications are attempted only after successful MongoDB writes. Email delivery failure does not remove attendance.",
    pageSizeLabel: "Per page:",
    downloadCsvBtn: "Download CSV ▾",
    csvCurrentTab: "Selected Tab CSV",
    csvAllRecords: "All Records CSV",
    csvDateWise: "Date-wise CSV…",
    csvClassWise: "Class-wise CSV…",
    csvDateClassWise: "Date + Class-wise CSV…",
    csvDateLabel: "Select Date",
    csvClassLabel: "Select or Enter Class",
    csvDownloadNow: "Download CSV",
    recordsTitle: "Attendance Records",
    recordsSubtitle: "Stored in MongoDB. Add, edit and delete apply directly to the database.",
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
    dashSubtitle: "MongoDB রেকর্ড, ক্লাসের সময়সূচি ও শিক্ষার্থীদের ইমেইল নোটিফিকেশন পরিচালনা করুন।",
    tabSettings: "⚙️ সাধারণ সেটিংস",
    tabRecords: "📊 হাজিরা রেকর্ড",
    scheduleCardTitle: "হাজিরার সময়সূচী",
    enableTimer: "হাজিরা নেওয়ার সময়সীমা সক্রিয় করুন",
    multiClass: "একাধিক ক্লাসের সময়সূচী ব্যবহার করুন",
    startTimeLabel: "শুরুর সময়",
    endTimeLabel: "শেষের সময়",
    addClassBtn: "+ ক্লাস যোগ করুন",
    emailCardTitle: "শিক্ষার্থীদের কনফার্মেশন ইমেইল",
    enableEmail: "স্বয়ংক্রিয় ইমেইল নোটিফিকেশন চালু করুন",
    providerEmailJs: "EmailJS API (১০০% ফ্রি ফ্রন্টএন্ড ও ব্যাকএন্ড API)",
    providerEmailJsHint: "১০০% ফ্রি · প্রতি মাসে ২০০টি ফ্রি ইমেইল। সার্ভার থেকে HTTPS API ব্যবহার করে পাঠানো হয়। কোনো পোর্ট ব্লক নেই।",
    providerSmtp: "জিমেইল এসএমটিপি (Gmail SMTP)",
    providerSmtpHint: "Gmail + App Password · VPS বা পেইড রেন্ডার প্রয়োজন; Render Free-তে SMTP পোর্ট ৫৮৭ বন্ধ থাকে।",
    smtpRenderWarnTitle: "⚠️ রেন্ডার ফ্রি সার্ভার সতর্কতা",
    smtpRenderWarnDesc: "Render Free টিয়ারে আউটবাউন্ড SMTP পোর্ট ৫৮৭ ব্লক থাকে। Render Free-তে Gmail SMTP কাজ করবে না। অনুগ্রহ করে EmailJS API বা Resend / Brevo-র মতো HTTPS প্রোভাইডার নির্বাচন করুন।",
    emailJsHelpTitle: "💡 ১০০% ফ্রি ফ্রন্টএন্ড ও ব্যাকএন্ড ইমেইল:",
    emailJsHelpDesc: "emailjs.com-এ সম্পূর্ণ বিনামূল্যে অ্যাকাউন্ট খুলুন (কোনো ক্রেডিট কার্ড লাগে না)। জিমেইল service connect করুন, email template তৈরি করুন, তারপর নিচে Service ID, Template ID ও Public Key দিন।",
    emailJsServiceIdLabel: "EmailJS সার্ভিস আইডি (যেমন: service_xxxx)",
    emailJsTemplateIdLabel: "EmailJS টেমপ্লেট আইডি (যেমন: template_xxxx)",
    emailJsPublicKeyLabel: "EmailJS পাবলিক কি (User ID)",
    emailJsPrivateKeyLabel: "EmailJS প্রাইভেট কি / অ্যাক্সেস টোকেন (ঐচ্ছিক)",
    senderNameLabel: "প্রেরকের নাম",
    clearEmailJsKeysLabel: "সেভ করা EmailJS কি মুছে ফেলুন",
    senderEmailLabel: "প্রেরকের জিমেইল ঠিকানা",
    appPasswordLabel: "গুগল অ্যাপ পাসওয়ার্ড",
    testEmailBtn: "✉ টেস্ট ইমেইল পাঠান",
    testEmailHint: "আগে সেটিংস সেভ করুন, তারপর টেস্ট করুন।",
    studentEmailPreview: "ইমেইল নোটিফিকেশন প্রিভিউ",
    saveConfigBtn: "💾 কনফিগারেশন সংরক্ষণ করুন",
    recordsTitle: "MongoDB হাজিরা রেকর্ড",
    selectSheetLabel: "তারিখ / ক্লাস নির্বাচন করুন",
    searchRecordsLabel: "রেকর্ড খুঁজুন",
    clearSearchTitle: "সার্চ মুছুন",
    searchPh: "নাম, আইডি, ইমেইল, তারিখ দিয়ে খুঁজুন…",
    addRecordBtn: "+ নতুন রেকর্ড যোগ করুন",
    reloadSheetsBtn: "↻ রিফ্রেশ",
    previewGreeting: "হ্যালো, রাব্বি",
    previewSummary: "আপনার উপস্থিতি সফলভাবে সংরক্ষিত হয়েছে। এখানে আপনার নিশ্চিতকরণ সারাংশ।",
    emailSecurityHint: "MongoDB-তে উপস্থিতি সেভ হওয়ার পর ইমেইল পাঠানোর চেষ্টা হয়। ইমেইল পাঠাতে ব্যর্থ হলেও হাজিরা রেকর্ড ঠিক থাকবে।",
    pageSizeLabel: "প্রতি পাতায়:",
    downloadCsvBtn: "CSV ডাউনলোড ▾",
    csvCurrentTab: "নির্বাচিত ট্যাবের CSV",
    csvAllRecords: "সকল রেকর্ডের CSV",
    csvDateWise: "তারিখ অনুযায়ী CSV…",
    csvClassWise: "ক্লাস অনুযায়ী CSV…",
    csvDateClassWise: "তারিখ + ক্লাস অনুযায়ী CSV…",
    csvDateLabel: "তারিখ নির্বাচন করুন",
    csvClassLabel: "ক্লাসের নাম লিখুন বা নির্বাচন করুন",
    csvDownloadNow: "CSV ডাউনলোড করুন",
    recordsTitle: "হাজিরা রেকর্ডসমূহ",
    recordsSubtitle: "MongoDB-তে সংরক্ষিত রেকর্ড; যোগ, সম্পাদনা ও ডিলিট সরাসরি ডেটাবেসে হয়।",
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

// --- CSV Export Logic ---
const downloadCsvBtn = $('downloadCsvBtn');
const csvDropdownMenu = $('csvDropdownMenu');
if (downloadCsvBtn && csvDropdownMenu) {
  downloadCsvBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    csvDropdownMenu.classList.toggle('hidden');
  });
  document.addEventListener('click', (e) => {
    if (!csvDropdownMenu.contains(e.target) && e.target !== downloadCsvBtn) {
      csvDropdownMenu.classList.add('hidden');
    }
  });
}

let currentExportMode = 'date';

async function downloadCsv(params) {
  if (csvDropdownMenu) csvDropdownMenu.classList.add('hidden');
  const authToken = token || sessionStorage.getItem('admin_token') || '';
  const qs = new URLSearchParams(params).toString();
  notice(currentLang === 'bn' ? 'CSV ফাইল প্রস্তুত হচ্ছে…' : 'Preparing CSV download…');
  try {
    const res = await fetch((window.ATTENDANCE_API_BASE || '') + '/api/admin/sheets/export/csv?' + qs, {
      headers: {
        'Authorization': 'Bearer ' + authToken
      }
    });
    if (!res.ok) throw new Error('Failed to generate CSV (HTTP ' + res.status + ')');
    const blob = await res.blob();
    let filename = 'attendance.csv';
    const dispo = res.headers.get('content-disposition');
    if (dispo && dispo.includes('filename=')) {
      const m = dispo.match(/filename="?([^"]+)"?/);
      if (m && m[1]) filename = m[1];
    }
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    notice(currentLang === 'bn' ? '✓ CSV ডাউনলোড সফল হয়েছে!' : '✓ CSV downloaded successfully!');
  } catch (e) {
    notice('CSV Download error: ' + e.message);
  }
}

if ($('exportCurrentTab')) {
  $('exportCurrentTab').addEventListener('click', () => {
    const curSheet = $('sheetSelect') ? $('sheetSelect').value : '';
    if (!curSheet) {
      notice(currentLang === 'bn' ? 'অনুগ্রহ করে প্রথমে একটি ট্যাব সিলেক্ট করুন।' : 'Please select a sheet tab first.');
      return;
    }
    downloadCsv({ type: 'sheet', sheet: curSheet });
  });
}

if ($('exportAllRecords')) {
  $('exportAllRecords').addEventListener('click', () => {
    downloadCsv({ type: 'all' });
  });
}

function openExportModal(mode) {
  if (csvDropdownMenu) csvDropdownMenu.classList.add('hidden');
  currentExportMode = mode;
  const modal = $('exportFilterModal');
  if (!modal) return;
  const title = $('exportModalTitle');
  const dateGrp = $('exportDateGroup');
  const clsGrp = $('exportClassGroup');
  if (mode === 'date') {
    if (title) title.textContent = currentLang === 'bn' ? 'তারিখ অনুযায়ী CSV ডাউনলোড' : 'Export CSV by Date';
    if (dateGrp) dateGrp.classList.remove('hidden');
    if (clsGrp) clsGrp.classList.add('hidden');
    if ($('exportDateInput')) $('exportDateInput').value = new Date().toISOString().slice(0, 10);
  } else if (mode === 'dateclass') {
    if (title) title.textContent = currentLang === 'bn' ? 'তারিখ + ক্লাস অনুযায়ী CSV ডাউনলোড' : 'Export CSV by Date + Class';
    if (dateGrp) dateGrp.classList.remove('hidden');
    if (clsGrp) clsGrp.classList.remove('hidden');
    if ($('exportDateInput')) $('exportDateInput').value = new Date().toISOString().slice(0, 10);
    if ($('exportClassInput')) { $('exportClassInput').value = ''; setTimeout(() => $('exportClassInput').focus(), 80); }
  } else {
    if (title) title.textContent = currentLang === 'bn' ? 'ক্লাস অনুযায়ী CSV ডাউনলোড' : 'Export CSV by Class';
    if (dateGrp) dateGrp.classList.add('hidden');
    if (clsGrp) clsGrp.classList.remove('hidden');
    if ($('exportClassInput')) { $('exportClassInput').value = ''; setTimeout(() => $('exportClassInput').focus(), 80); }
  }
  modal.classList.remove('hidden');
}

function closeExportModal() {
  const modal = $('exportFilterModal');
  if (modal) modal.classList.add('hidden');
}

if ($('exportDateWise')) {
  $('exportDateWise').addEventListener('click', () => openExportModal('date'));
}
if ($('exportClassWise')) {
  $('exportClassWise').addEventListener('click', () => openExportModal('class'));
}
if ($('exportDateClassWise')) {
  $('exportDateClassWise').addEventListener('click', () => openExportModal('dateclass'));
}
if ($('closeExportModal')) $('closeExportModal').addEventListener('click', closeExportModal);
if ($('cancelExportModal')) $('cancelExportModal').addEventListener('click', closeExportModal);
if ($('confirmExportBtn')) {
  $('confirmExportBtn').addEventListener('click', () => {
    if (currentExportMode === 'date') {
      const dateVal = $('exportDateInput') ? $('exportDateInput').value : '';
      if (!dateVal) {
        notice(currentLang === 'bn' ? 'একটি তারিখ নির্বাচন করুন।' : 'Please select a date.');
        return;
      }
      closeExportModal();
      downloadCsv({ type: 'date', date: dateVal });
    } else if (currentExportMode === 'dateclass') {
      const dateVal = $('exportDateInput') ? $('exportDateInput').value : '';
      const clsVal = $('exportClassInput') ? $('exportClassInput').value.trim() : '';
      if (!dateVal) {
        notice(currentLang === 'bn' ? 'একটি তারিখ নির্বাচন করুন।' : 'Please select a date.');
        return;
      }
      if (!clsVal) {
        notice(currentLang === 'bn' ? 'ক্লাসের নাম লিখুন।' : 'Please enter a class name.');
        return;
      }
      // Backend combines date + cls into one filter (class names never contain '|').
      closeExportModal();
      downloadCsv({ type: 'date', date: dateVal, cls: clsVal });
    } else {
      const clsVal = $('exportClassInput') ? $('exportClassInput').value.trim() : '';
      if (!clsVal) {
        notice(currentLang === 'bn' ? 'ক্লাসের নাম লিখুন।' : 'Please enter a class name.');
        return;
      }
      closeExportModal();
      downloadCsv({ type: 'class', cls: clsVal });
    }
  });
}

// --- Developer footer year ---
const afYear = $('afYear');
if (afYear) afYear.textContent = String(new Date().getFullYear());
