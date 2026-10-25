/**
 * SMART ATTENDANCE — server-gated Google Sheets adapter.
 * Paste into Extensions > Apps Script on your Google Sheet, replacing the old Code.gs.
 * In Apps Script > Project Settings > Script Properties set:
 *   GATEWAY_SECRET = same 32+ character value as in the administrator panel.
 * Deploy as Web app: Execute as Me; Who has access Anyone.
 * Browser -> Spring Boot -> this adapter. Never place GATEWAY_SECRET in the browser.
 */
const HEADERS = ["SL", "Date", "Name", "ID", "Email", "Attendance", "Local Time"];
const TIMEZONE = "Asia/Dhaka";
const RATE_SECONDS = 3;
const GLOBAL_LIMIT = 80;

function json_(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }
function today_() { return Utilities.formatDate(new Date(), TIMEZONE, "yyyy-MM-dd"); }
function now_() { return Utilities.formatDate(new Date(), TIMEZONE, "h:mm:ss a"); }
function mask_(id) { return String(id).slice(0, 4) + "•••••" + String(id).slice(-4); }
function authorized_(key) {
  const saved = PropertiesService.getScriptProperties().getProperty('GATEWAY_SECRET');
  return !!saved && saved.length >= 32 && String(key || '') === saved;
}
function validSheetName_(name) {
  const n = String(name || '');
  if (!n || n.length > 100 || /[\\/?*\[\]:]/.test(n)) throw Error('Invalid sheet name');
  return n;
}
function safeValue_(value) {
  const str = String(value === null || value === undefined ? '' : value).slice(0, 500);
  // A user-supplied cell can never be executed as a Sheets formula.
  return /^[=+@\-\t\r]/.test(str) ? "'" + str : str;
}
function cols_(sh) {
  const first = String(sh.getRange(1,1).getValue()).trim();
  return first === 'SL' || first === '#' ? {sl:true,id:4,count:7} : {sl:false,id:3,count:6};
}
function createSheet_(ss, name) {
  let sh = ss.getSheetByName(name);
  if (sh) return sh;
  sh = ss.insertSheet(name, 0);
  sh.getRange('A:G').setNumberFormat('@');
  sh.getRange(1,1,1,7).setValues([HEADERS]).setBackground('#4f46e5').setFontColor('#ffffff')
    .setFontWeight('bold').setHorizontalAlignment('center');
  sh.setFrozenRows(1); sh.setRowHeight(1,36);
  [60,110,200,150,240,110,120].forEach((w,i)=>sh.setColumnWidth(i+1,w));
  sh.getRange('I1').setValue('Total:');
  sh.getRange('J1').setFormula('=COUNT(F2:F)');
  sh.setTabColor('#4f46e5');
  return sh;
}
function validateRow_(values, hasSl) {
  if (!Array.isArray(values) || values.length !== (hasSl ? 7 : 6)) throw Error('Invalid column count');
  const name = String(values[hasSl ? 2 : 1] || '').trim();
  const id = String(values[hasSl ? 3 : 2] || '').trim();
  const email = String(values[hasSl ? 4 : 3] || '').trim();
  if (name.length < 2 || name.length > 80) throw Error('Name must be 2–80 characters');
  if (!/^\d{13}$/.test(id)) throw Error('ID must contain exactly 13 digits');
  if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw Error('Invalid email');
  return values.map(safeValue_);
}
function duplicate_(sh, id, idCol, ignoreRow) {
  if (sh.getLastRow() <= 1) return false;
  const ids=sh.getRange(2,idCol,sh.getLastRow()-1,1).getDisplayValues().flat();
  return ids.some((x,i)=>x===id && i+2!==ignoreRow);
}
function renumber_(sh) {
  const n=sh.getLastRow()-1;
  if (n>0 && cols_(sh).sl) sh.getRange(2,1,n,1).setValues(Array.from({length:n},(_,i)=>[String(i+1)]));
}
function adminRows_(ss, sheetName) {
  const sh=ss.getSheetByName(validSheetName_(sheetName));
  if (!sh) return {ok:false,error:'sheet_not_found'};
  const columnCount=cols_(sh).count;
  const header=sh.getRange(1,1,1,columnCount).getDisplayValues()[0];
  const n=Math.max(0,sh.getLastRow()-1);
  const rows=n ? sh.getRange(2,1,n,columnCount).getDisplayValues().map((v,i)=>({rowIndex:i+2,values:v})) : [];
  return {ok:true,sheet:sheetName,headers:header,rows};
}
function adminMutation_(ss,d) {
  const action=String(d.action);
  const sheetName=validSheetName_(d.sheet);
  let sh=ss.getSheetByName(sheetName);
  if (action==='adminAdd' && !sh) sh=createSheet_(ss,sheetName);
  if (!sh) return {ok:false,error:'sheet_not_found'};
  const c=cols_(sh);
  const index=Number(d.rowIndex);
  if (action==='adminDelete' || action==='adminEdit') {
    if (!Number.isInteger(index) || index<2 || index>sh.getLastRow()) return {ok:false,error:'invalid_row'};
  }
  if (action==='adminDelete') {
    sh.deleteRow(index);renumber_(sh);return {ok:true};
  }
  if (sh.getLastColumn()<c.count) return {ok:false,error:'unsupported_sheet_layout'};
  const values=validateRow_(d.values,c.sl);
  const id=String(values[c.id-1]);
  if (duplicate_(sh,id,c.id,action==='adminEdit'?index:-1)) return {ok:false,error:'dup'};
  if (action==='adminEdit') {
    if (c.sl) values[0]=String(index-1);
    sh.getRange(index,1,1,c.count).setValues([values]);
  } else if (action==='adminAdd') {
    if (c.sl) values[0]=String(sh.getLastRow());
    sh.appendRow(values);
  } else return {ok:false,error:'unknown_action'};
  return {ok:true};
}
function doGet(e) {
  try {
    const p=e&&e.parameter?e.parameter:{};
    if (!authorized_(p.key)) return json_({ok:false,error:'unauthorized'});
    const ss=SpreadsheetApp.getActiveSpreadsheet();
    if (p.action==='adminSheets') {
      return json_({ok:true,sheets:ss.getSheets().map(sh=>({name:sh.getName(),count:Math.max(0,sh.getLastRow()-1)}))});
    }
    if (p.action==='adminRows') return json_(adminRows_(ss,p.sheet));
    if (p.action) return json_({ok:false,error:'unknown_action'});
    const date=today_();
    const cls=String(p.cls||'').trim().replace(/[\\/?*\[\]:]/g,'_').slice(0,50);
    const name=cls?date+' - '+cls:date;
    const sh=ss.getSheetByName(name);
    const rows=[];
    if (sh && sh.getLastRow()>1) {
      const c=cols_(sh);
      sh.getRange(2,1,sh.getLastRow()-1,c.count).getDisplayValues().forEach(v=>rows.push({
        name:v[c.sl?2:1],idm:mask_(v[c.sl?3:2]),time:v[c.sl?6:5]
      }));
    }
    return json_({ok:true,sheet:name,rows});
  } catch(err) { return json_({ok:false,error:'server',message:String(err.message||err)}); }
}

function escapeHtml_(val) {
  return String(val === null || val === undefined ? '' : val)
    .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
    .replace(/"/g,'&quot;').replace(/'/g,'&#39;');
}
function sendReceipt_(to, person, studentId, date, cls, displayName, isTest) {
  const recipient=String(to||'').trim();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(recipient) || recipient.length>150) return 'failed';
  const name=escapeHtml_(String(person||'Student').slice(0,80));
  const masked=studentId && String(studentId).length>=8 ? escapeHtml_(String(studentId).slice(0,4)+'•••••'+String(studentId).slice(-4)) : '—';
  const dateHtml=escapeHtml_(date);
  const classHtml=escapeHtml_(cls||'General Attendance');
  const title=isTest?'Test email delivered':'Attendance confirmed';
  const description=isTest ? 'Your email notification settings are working correctly.' : 'Your attendance has been successfully recorded. Here is your confirmation summary.';
  const html='<div style="margin:0;padding:36px 14px;background:#f3f5fb;font-family:Arial,Helvetica,sans-serif;color:#1c2944">'
    +'<div style="max-width:540px;margin:auto;background:#fff;border:1px solid #e4e9f3;border-radius:20px;overflow:hidden">'
    +'<div style="background:#4f46e5;padding:28px 32px;color:#fff"><div style="font-size:13px;letter-spacing:2px;opacity:.9">SMART ATTENDANCE</div><h1 style="font-size:23px;margin:12px 0 0">'+title+'</h1></div>'
    +'<div style="padding:28px 32px"><div style="font-size:28px;margin-bottom:12px">✓</div><p style="font-size:17px;font-weight:bold">Hello, '+name+'</p>'
    +'<p style="font-size:14px;color:#627089;line-height:1.7">'+description+'</p>'
    +'<div style="background:#f7f8ff;border:1px solid #edf0fa;border-radius:14px;padding:18px 22px">'
    +'<div style="margin-bottom:14px"><span style="color:#748199">DATE</span><br><b>'+dateHtml+'</b></div>'
    +'<div style="margin-bottom:14px"><span style="color:#748199">CLASS / SESSION</span><br><b>'+classHtml+'</b></div>'
    +'<div><span style="color:#748199">STUDENT ID</span><br><b>'+masked+'</b></div></div>'
    +'<p style="font-size:12px;color:#8993a8;margin-top:22px">Automatic notification · Please do not reply</p></div></div>'
    +'<p style="text-align:center;font-size:12px;color:#8993a8">Smart Attendance • Digital attendance records</p></div>';
  const subject = isTest ? 'Smart Attendance • Email Test' : 'Attendance Confirmed • Smart Attendance';
  try {
    MailApp.sendEmail({to:recipient,subject:subject,
      body:'Hello '+person+'\n'+description+'\nDate: '+date+'\nClass: '+(cls||'General Attendance')+'\nStudent ID: '+masked,
      htmlBody:html, name:String(displayName||'Smart Attendance').replace(/[\r\n]/g,'').slice(0,55)});
    return 'sent';
  }catch(err){ console.error('Email delivery error'); return 'failed'; }
}

function doPost(e) {
  let d;
  try { d=JSON.parse(e.postData.contents); } catch(err) {return json_({ok:false,error:'invalid_json'});}
  if (!authorized_(d.key)) return json_({ok:false,error:'unauthorized'});
  const lock=LockService.getScriptLock();
  try {lock.waitLock(25000);} catch(err) {return json_({ok:false,error:'busy'});}
  try {
    const ss=SpreadsheetApp.getActiveSpreadsheet();
    if (d.action === 'adminTestMail') {
      const email=String(d.email||'').trim();
      if (email.length>150 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return json_({ok:false,error:'bad_email'});
      const status=sendReceipt_(email,'Administrator','',today_(),'Email settings test',d.senderName,true);
      return json_({ok:true,emailStatus:status});
    }
    if (String(d.action||'').startsWith('admin')) return json_(adminMutation_(ss,d));
    if (d.action) return json_({ok:false,error:'unknown_action'});
    const cache=CacheService.getScriptCache();
    const name=String(d.name||'').trim().slice(0,80);
    const id=String(d.id||'').trim();
    const email=String(d.email||'').trim().slice(0,100);
    const cls=String(d.cls||'').trim().replace(/[\\/?*\[\]:]/g,'_').slice(0,50);
    if (!/^\d{13}$/.test(id)) return json_({ok:false,error:'bad_id'});
    if (name.length<2) return json_({ok:false,error:'bad_name'});
    if (email&&!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return json_({ok:false,error:'bad_email'});
    const rateKey='r_'+id;
    if (cache.get(rateKey)) return json_({ok:false,error:'rate'});
    const globalKey='g_'+Math.floor(Date.now()/10000);
    const total=Number(cache.get(globalKey)||0)+1;
    cache.put(globalKey,String(total),30);
    if (total>GLOBAL_LIMIT) return json_({ok:false,error:'busy'});
    const date=today_();
    const sheetName=cls?date+' - '+cls:date;
    const sh=createSheet_(ss,sheetName);
    const c=cols_(sh);
    if (duplicate_(sh,id,c.id,-1)) return json_({ok:false,error:'dup'});
    let data=c.sl ? [sh.getLastRow(),date,name,id,email,'1',now_()] : [date,name,id,email,'1',now_()];
    data=data.map(safeValue_);
    sh.appendRow(data);
    cache.put(rateKey,'1',RATE_SECONDS);
    const mailStatus=(d.mailEnabled===true && email) ?
      sendReceipt_(email,name,id,date,cls,d.senderName,false) : 'disabled';
    return json_({ok:true,sheet:sheetName,emailStatus:mailStatus});
  } catch(err) {return json_({ok:false,error:'server',message:String(err.message||err)});}
  finally {lock.releaseLock();}
}
