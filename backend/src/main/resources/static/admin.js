'use strict';
const $ = id => document.getElementById(id);
const API = String(window.ATTENDANCE_API_BASE || '').replace(/\/$/, '') + '/api';
let token = sessionStorage.getItem('admin_token') || '';
let userEmail = sessionStorage.getItem('admin_email') || '';
let sheets = [], sheetData = null, currentEdit = null, currentDelete = null;
let toastTimer;
function notice(message) {
  $('toast').textContent = String(message); $('toast').classList.remove('hidden');
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
  notice('New secret generated. Copy it into Apps Script Script Properties, then save.');
});
async function loadConfig() {
  const cfg=await request('/admin/config');
  $('scriptUrl').value=cfg.SCRIPT_URL||'';
  $('sheetUrl').value=cfg.SHEET_URL||'';
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
  classState=(sch.CLASSES||[]).map(c=>({...c}));drawClasses();updateScheduleSections();
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
  }catch(e){$('rowsStatus').textContent=e.message;notice(e.message);}
}
$('reloadSheets').addEventListener('click',loadSheets);
$('sheetSelect').addEventListener('change',loadRows);
$('searchRows').addEventListener('input',drawTable);
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
  const query=$('searchRows').value.trim().toLowerCase();
  const filtered=all.filter(row=>!query||row.values.some(v=>String(v).toLowerCase().includes(query)));
  for(const row of filtered){
    const line=document.createElement('tr');line.append(mk('td',row.rowIndex));
    for(const v of row.values)line.append(mk('td',v));
    const actions=document.createElement('td');
    const edit=mk('button','Edit');edit.className='btn btn-light small';edit.style.marginRight='8px';edit.addEventListener('click',()=>editRow(row));
    const del=mk('button','Delete');del.className='btn btn-danger small';del.addEventListener('click',()=>deleteRow(row));
    actions.append(edit,del);line.append(actions);body.append(line);
  }
  if(!filtered.length){const line=document.createElement('tr'),cell=mk('td',all.length?'No matching records.':'This sheet has no records yet.');cell.colSpan=headers.length+2;cell.className='empty';line.append(cell);body.append(line);}
  $('rowCount').textContent=`${filtered.length} / ${all.length} records`;
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
  document.title = dict.adminTitle;
}

function setAdminTheme(m) {
  document.documentElement.dataset.theme = m;
  const themeBtn = $('adminTheme');
  if (themeBtn) themeBtn.textContent = m === 'dark' ? '☀️' : '🌙';
  try { localStorage.setItem('theme', m); } catch(e) {}
}

const adminLangBtn = $('adminLang');
if (adminLangBtn) {
  adminLangBtn.addEventListener('click', () => {
    currentLang = currentLang === 'en' ? 'bn' : 'en';
    try { localStorage.setItem('lang', currentLang); } catch(e) {}
    applyAdminLang();
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
