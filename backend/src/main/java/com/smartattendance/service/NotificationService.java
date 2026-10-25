package com.smartattendance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartattendance.model.AdminSettings;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

@Service
public class NotificationService {
  private final EncryptionService crypto;
  private final AppsScriptClient script;
  private static final ZoneId TIMEZONE=ZoneId.of("Asia/Dhaka");
  public NotificationService(EncryptionService crypto, AppsScriptClient script){this.crypto=crypto;this.script=script;}
  public boolean usingScript(AdminSettings s){return s.emailEnabled && !"SMTP".equalsIgnoreCase(s.emailProvider);}
  public String receiptStatus(AdminSettings s, String recipient, String studentName, String id,
                              String className, JsonNode savedResult) {
    if(recipient==null||recipient.isBlank())return "no_address";
    if(!s.emailEnabled)return "disabled";
    if(usingScript(s))return savedResult.path("emailStatus").asText("failed");
    try {
      String date=DateTimeFormatter.ofPattern("d MMMM yyyy",Locale.ENGLISH).format(ZonedDateTime.now(TIMEZONE));
      sendSmtp(s,recipient,studentName,id,date,className,false);
      return "sent";
    }catch(Exception e){return "failed";} // attendance remains saved even if email fails
  }
  public Map<String,Object> test(AdminSettings s,String adminEmail) {
    if(!s.emailEnabled) throw new IllegalArgumentException("Enable email notifications and save settings before sending a test");
    if(usingScript(s)) {
      JsonNode result=script.post(s,Map.of("action","adminTestMail","email",adminEmail,
          "senderName",safeSender(s)));
      return Map.of("ok",result.path("ok").asBoolean(false),"emailStatus",result.path("emailStatus").asText("failed"));
    }
    try {
      sendSmtp(s,adminEmail,"Administrator","—","Preview", "Test Email",true);
      return Map.of("ok",true,"emailStatus","sent");
    } catch (Exception e) {return Map.of("ok",true,"emailStatus","failed","message","SMTP delivery failed. Check Gmail App Password and hosting restrictions.");}
  }
  public String safeSender(AdminSettings s){return s.senderName == null||s.senderName.isBlank()?"Smart Attendance":s.senderName;}
  public void sendSmtp(AdminSettings s,String recipient,String name,String studentId,String date,String className,boolean test) throws Exception {
    if(s.encryptedAppPassword==null||s.encryptedAppPassword.isBlank())throw new IllegalStateException("No Gmail App Password");
    JavaMailSenderImpl mail=new JavaMailSenderImpl();
    mail.setHost("smtp.gmail.com");mail.setPort(587);mail.setUsername(s.senderEmail);
    mail.setPassword(crypto.decrypt(s.encryptedAppPassword));
    mail.setDefaultEncoding("UTF-8");
    Properties opts=mail.getJavaMailProperties();
    opts.put("mail.smtp.auth","true");opts.put("mail.smtp.starttls.enable","true");
    opts.put("mail.smtp.starttls.required","true");opts.put("mail.smtp.ssl.checkserveridentity","true");
    opts.put("mail.smtp.connectiontimeout","5000");opts.put("mail.smtp.timeout","7000");opts.put("mail.smtp.writetimeout","7000");
    MimeMessage msg=mail.createMimeMessage();MimeMessageHelper helper=new MimeMessageHelper(msg,true,"UTF-8");
    helper.setTo(recipient);helper.setFrom(s.senderEmail,safeSender(s));
    helper.setSubject(test?"Smart Attendance • Email Test":"Attendance Confirmed • Smart Attendance");
    String dateText=escape(date), display=escape(name), cls=escape(className==null||className.isBlank()?"General Attendance":className);
    String masked=studentId==null||studentId.length()<8?"—":escape(studentId.substring(0,4)+"•••••"+studentId.substring(studentId.length()-4));
    String plain=(test?"Test notification":"Attendance confirmation")+"\nHello "+name+",\nYour attendance has been recorded.\nDate: "+date+"\nClass: "+className+"\nStudent ID: "+masked+"\nThis is an automated confirmation.";
    helper.setText(plain,html(display,dateText,cls,masked,test));
    mail.send(msg);
  }
  private String escape(String s) {return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
  private String html(String name,String date,String cls,String id,boolean test) {
    return "<!doctype html><html><body style='margin:0;padding:36px 14px;background:#f3f5fb;font-family:Arial,Helvetica,sans-serif;color:#1c2944'>"
      +"<div style='max-width:540px;margin:auto;background:white;border:1px solid #e4e9f3;border-radius:20px;overflow:hidden'>"
      +"<div style='background:#4f46e5;padding:28px 32px;color:#fff'><div style='font-size:13px;letter-spacing:2px;opacity:.9'>SMART ATTENDANCE</div>"
      +"<h1 style='font-size:23px;margin:12px 0 0'>"+(test?"Test email delivered":"Attendance confirmed")+"</h1></div>"
      +"<div style='padding:28px 32px'><div style='font-size:28px;margin-bottom:12px'>✓</div><p style='font-size:17px;font-weight:bold'>Hello, "+name+"</p>"
      +"<p style='font-size:14px;color:#627089;line-height:1.7'>"+(test?"Your notification settings are working correctly.":"Your attendance has been successfully recorded. Here is your confirmation summary.")+"</p>"
      +"<div style='background:#f7f8ff;border:1px solid #edf0fa;border-radius:14px;padding:18px 22px'>"
      +"<div style='margin:0 0 14px'><span style='color:#748199'>DATE</span><br><b>"+date+"</b></div>"
      +"<div style='margin:0 0 14px'><span style='color:#748199'>CLASS / SESSION</span><br><b>"+cls+"</b></div>"
      +"<div><span style='color:#748199'>STUDENT ID</span><br><b>"+id+"</b></div></div>"
      +"<p style='font-size:12px;color:#8993a8;margin-top:22px'>Automatic notification · Please do not reply</p></div></div>"
      +"<p style='text-align:center;font-size:12px;color:#8993a8'>Smart Attendance • Digital attendance records</p></body></html>";
  }
}
