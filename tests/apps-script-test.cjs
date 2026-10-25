'use strict';
/* Minimal Apps Script mocks: validates ON/OFF and duplicate-write notification guards. */
const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const code=fs.readFileSync(require('node:path').join(__dirname,'..','Code.gs'),'utf8');
let mails=[];let sheets=new Map();const cache=new Map();
const calls={};
function fakeSheet(name){
 const rows=[['SL','Date','Name','ID','Email','Attendance','Local Time']];
 return {getName:()=>name,getLastRow:()=>rows.length,
  getRange:(r,c,n=1,m=1)=>({getValue:()=>rows[r-1][c-1],getDisplayValues:()=>rows.slice(r-1,r-1+n).map(x=>x.slice(c-1,c-1+m))}),
  appendRow:(values)=>rows.push(values),_rows:rows};
}
const spreadsheet={getSheetByName:n=>sheets.get(n)||null,insertSheet:n=>{const sh=fakeSheet(n);sheets.set(n,sh);return sh;}};
const sandbox={
 console,Date,JSON,Math,Number,String,Object,Array,RegExp,Error,parseInt,
 ContentService:{MimeType:{JSON:'json'},createTextOutput:text=>({setMimeType:()=>({body:text})})},
 PropertiesService:{getScriptProperties:()=>({getProperty:()=> 'A'.repeat(40)})},
 LockService:{getScriptLock:()=>({waitLock:()=>{},releaseLock:()=>{}})},
 CacheService:{getScriptCache:()=>({get:key=>cache.get(key),put:(key,value)=>cache.set(key,value)})},
 SpreadsheetApp:{getActiveSpreadsheet:()=>spreadsheet},
 Utilities:{formatDate:(_now,_tz,pattern)=>pattern==='yyyy-MM-dd'?'2026-10-24':'7:40:00 PM'},
 MailApp:{sendEmail:message=>mails.push(message)},
};
vm.createContext(sandbox);vm.runInContext(code,sandbox);
const name='2026-10-24';sheets.set(name,fakeSheet(name));
const post=(data)=>JSON.parse(sandbox.doPost({postData:{contents:JSON.stringify({key:'A'.repeat(40),...data})}}).body);
const student={name:'Test Student',id:'2023200000686',email:'student@example.com',cls:'',senderName:'Smart Attendance'};
let result=post({...student,mailEnabled:false});
assert.equal(result.ok,true);assert.equal(result.emailStatus,'disabled');assert.equal(mails.length,0);
result=post({...student,id:'2023200000687',mailEnabled:true});
assert.equal(result.ok,true);assert.equal(result.emailStatus,'sent');assert.equal(mails.length,1);
assert.match(mails[0].htmlBody,/Attendance confirmed/);assert.doesNotMatch(mails[0].htmlBody,/2023200000687/);
result=post({...student,id:'2023200000687',mailEnabled:true});
assert.equal(result.ok,false);assert.equal(mails.length,1);
result=post({action:'adminTestMail',email:'admin@example.com',senderName:'Smart Attendance'});
assert.equal(result.emailStatus,'sent');assert.equal(mails.length,2);
console.log('Apps Script tests passed: OFF no email, ON receipt, duplicate no email, admin test.');
