package com.smartattendance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartattendance.model.AdminSettings;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  private final EncryptionService crypto;
  private final AppsScriptClient script;
  private static final ZoneId TIMEZONE = ZoneId.of("Asia/Dhaka");

  public NotificationService(EncryptionService crypto, AppsScriptClient script) {
    this.crypto = crypto;
    this.script = script;
  }

  public boolean usingScript(AdminSettings s) {
    return s.emailEnabled && !"SMTP".equalsIgnoreCase(s.emailProvider);
  }

  public String receiptStatus(AdminSettings s, String recipient, String studentName, String id,
                              String className, JsonNode savedResult) {
    if (recipient == null || recipient.isBlank()) return "no_address";
    if (!s.emailEnabled) return "disabled";

    if (usingScript(s)) {
      if (savedResult != null && savedResult.has("emailStatus")) {
        return savedResult.path("emailStatus").asText("failed");
      }
      if (s.hasGoogleSheets()) {
        try {
          JsonNode fallback = script.post(s, Map.of(
              "action", "sendReceipt",
              "name", studentName,
              "id", id,
              "email", recipient,
              "cls", className != null && !className.isBlank() ? className : "General Attendance",
              "senderName", safeSender(s)
          ));
          if (fallback != null && "sent".equalsIgnoreCase(fallback.path("emailStatus").asText())) {
            return "sent";
          }
        } catch (Exception e) {
          log.warn("Apps Script receipt send failed: {}", e.getMessage());
        }
      }
      return "failed";
    }

    // SMTP delivery with automatic Apps Script fallback if blocked by hosting
    try {
      String date = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).format(ZonedDateTime.now(TIMEZONE));
      sendSmtp(s, recipient, studentName, id, date, className, false);
      return "sent";
    } catch (Exception e) {
      log.warn("SMTP send failed ({}): {}. Checking for Apps Script fallback...", e.getClass().getSimpleName(), e.getMessage());
      if (s.hasGoogleSheets()) {
        try {
          JsonNode fallback = script.post(s, Map.of(
              "action", "sendReceipt",
              "name", studentName,
              "id", id,
              "email", recipient,
              "cls", className != null && !className.isBlank() ? className : "General Attendance",
              "senderName", safeSender(s)
          ));
          if (fallback != null && "sent".equalsIgnoreCase(fallback.path("emailStatus").asText())) {
            log.info("Email delivered successfully via Apps Script fallback!");
            return "sent";
          }
        } catch (Exception ex) {
          log.warn("Apps Script fallback also failed: {}", ex.getMessage());
        }
      }
      return "failed";
    }
  }

  public Map<String, Object> test(AdminSettings s, String adminEmail) {
    if (!s.emailEnabled) {
      throw new IllegalArgumentException("Enable email notifications and save settings before sending a test");
    }

    if (usingScript(s)) {
      if (!s.hasGoogleSheets()) {
        return Map.of("ok", false, "emailStatus", "failed", "message",
            "Google Apps Script Web App URL and Secret must be configured in Settings first.");
      }
      try {
        JsonNode result = script.post(s, Map.of(
            "action", "adminTestMail",
            "email", adminEmail,
            "senderName", safeSender(s)
        ));
        String status = result != null ? result.path("emailStatus").asText("failed") : "failed";
        String err = result != null ? result.path("error").asText("") : "";
        if ("sent".equalsIgnoreCase(status)) {
          return Map.of("ok", true, "emailStatus", "sent", "message",
              "Test email successfully sent via Google Apps Script to " + adminEmail);
        } else {
          String msg = "Google Apps Script delivery failed.";
          if (!err.isBlank()) msg += " (" + err + ")";
          msg += " Make sure you ran testMailAuthorization() in Apps Script to grant MailApp permissions.";
          return Map.of("ok", false, "emailStatus", "failed", "message", msg);
        }
      } catch (Exception e) {
        return Map.of("ok", false, "emailStatus", "failed", "message",
            "Apps Script connection failed: " + e.getMessage());
      }
    }

    // SMTP mode
    try {
      sendSmtp(s, adminEmail, "Admin", "—", "Preview", "Test Email", true);
      return Map.of("ok", true, "emailStatus", "sent", "message",
          "Test email successfully sent via Gmail SMTP to " + adminEmail);
    } catch (Exception e) {
      log.warn("SMTP test failed: {}", e.getMessage());
      String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      String detail = "SMTP failed: " + err;
      String lower = err.toLowerCase(Locale.ROOT);
      if (lower.contains("timeout") || lower.contains("connect") || lower.contains("refused") || lower.contains("587")) {
        detail = "SMTP Port 587 connection timed out (Render Free blocks all outbound SMTP ports). Please select 'Google Apps Script' delivery method (which works 100% over HTTPS).";
      } else if (lower.contains("authentication") || lower.contains("535") || lower.contains("badcredentials") || lower.contains("username and password not accepted")) {
        detail = "Gmail authentication failed. Verify your 16-character Google App Password (not your normal Gmail password).";
      }
      return Map.of("ok", false, "emailStatus", "failed", "message", detail);
    }
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
    String title = test ? "Test Email Delivered" : "Attendance Confirmed";
    String desc = test
        ? "Your notification settings are configured and operating properly. Test message verified."
        : "Your attendance record has been successfully recorded in the digital registry. Below is your official receipt summary.";
    StringBuilder sb = new StringBuilder();
    sb.append("<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>");
    sb.append("<title>Smart Attendance</title></head>");
    sb.append("<body style='margin:0;padding:28px 12px;background-color:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#0f172a;'>");
    sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='max-width:580px;margin:0 auto;background-color:#ffffff;border-radius:18px;overflow:hidden;box-shadow:0 10px 30px rgba(15,23,42,0.08);border:1px solid #e2e8f0;'>");
    sb.append("<tr><td style='background:linear-gradient(135deg, #090e1a 0%, #1e1b4b 100%);padding:30px 26px;text-align:left;'>");
    sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr><td>");
    sb.append("<span style='font-size:11px;font-weight:700;letter-spacing:1.5px;color:#38bdf8;text-transform:uppercase;'>SMART ATTENDANCE PORTAL</span>");
    sb.append("<h1 style='margin:8px 0 0;font-size:23px;font-weight:800;color:#ffffff;letter-spacing:-0.5px;'>").append(title).append("</h1>");
    sb.append("</td><td align='right' style='vertical-align:middle;'>");
    sb.append("<span style='display:inline-block;padding:6px 12px;background:rgba(52,211,153,0.18);border:1px solid rgba(52,211,153,0.35);border-radius:99px;color:#34d399;font-size:11px;font-weight:700;letter-spacing:0.5px;'>✓ VERIFIED</span>");
    sb.append("</td></tr></table></td></tr>");
    sb.append("<tr><td style='padding:28px 26px;'>");
    sb.append("<p style='margin:0 0 6px;font-size:18px;font-weight:700;color:#0f172a;'>Hello, ").append(name).append("</p>");
    sb.append("<p style='margin:0 0 22px;font-size:14px;line-height:1.6;color:#64748b;'>").append(desc).append("</p>");
    sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='background-color:#f8fafc;border:1px solid #e2e8f0;border-radius:14px;margin-bottom:24px;'>");
    sb.append("<tr><td style='padding:14px 18px;border-bottom:1px solid #e2e8f0;'><div style='font-size:11px;font-weight:700;color:#64748b;letter-spacing:0.5px;text-transform:uppercase;'>DATE</div><div style='font-size:14px;font-weight:700;color:#0f172a;margin-top:2px;'>").append(date).append("</div></td></tr>");
    sb.append("<tr><td style='padding:14px 18px;border-bottom:1px solid #e2e8f0;'><div style='font-size:11px;font-weight:700;color:#64748b;letter-spacing:0.5px;text-transform:uppercase;'>SESSION / CLASS</div><div style='font-size:14px;font-weight:700;color:#0f172a;margin-top:2px;'>").append(cls).append("</div></td></tr>");
    sb.append("<tr><td style='padding:14px 18px;border-bottom:1px solid #e2e8f0;'><div style='font-size:11px;font-weight:700;color:#64748b;letter-spacing:0.5px;text-transform:uppercase;'>STUDENT ID</div><div style='font-size:14px;font-weight:700;color:#0f172a;margin-top:2px;font-family:monospace;'>").append(id).append("</div></td></tr>");
    sb.append("<tr><td style='padding:14px 18px;'><div style='font-size:11px;font-weight:700;color:#64748b;letter-spacing:0.5px;text-transform:uppercase;'>RECORD STATUS</div><div style='font-size:13px;font-weight:700;color:#10b981;margin-top:2px;'>Recorded &amp; Confirmed</div></td></tr>");
    sb.append("</table>");
    sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='background:#080d1a;border-radius:14px;padding:20px;color:#ffffff;margin-top:16px;'>");
    sb.append("<tr><td><div style='font-size:11px;font-weight:700;letter-spacing:1px;color:#38bdf8;text-transform:uppercase;margin-bottom:3px;'>SYSTEM DEVELOPER</div>");
    sb.append("<div style='font-size:16px;font-weight:800;color:#ffffff;margin-bottom:5px;'>Fazley <span style='color:#38bdf8;'>.</span> Rabbi</div>");
    sb.append("<p style='margin:0 0 14px;font-size:12px;line-height:1.5;color:#94a3b8;'>Thoughtful code. Practical research. Meaningful work.</p>");
    sb.append("<table role='presentation' cellpadding='0' cellspacing='0' border='0' width='100%'><tr><td>");
    sb.append("<table role='presentation' cellpadding='0' cellspacing='0' border='0'><tr>");
    sb.append("<td style='padding-right:6px;padding-bottom:6px;'><a href='https://www.linkedin.com/in/fazleyrabbi1067/' target='_blank' rel='noopener' style='display:inline-block;padding:6px 12px;background:rgba(255,255,255,0.06);border:1px solid rgba(255,255,255,0.16);border-radius:7px;color:#ffffff;text-decoration:none;font-size:11px;font-weight:600;'>LinkedIn ↗</a></td>");
    sb.append("<td style='padding-right:6px;padding-bottom:6px;'><a href='https://github.com/rabbi1067' target='_blank' rel='noopener' style='display:inline-block;padding:6px 12px;background:rgba(255,255,255,0.06);border:1px solid rgba(255,255,255,0.16);border-radius:7px;color:#ffffff;text-decoration:none;font-size:11px;font-weight:600;'>GitHub ↗</a></td>");
    sb.append("<td style='padding-right:6px;padding-bottom:6px;'><a href='https://x.com/FazleRabbi56251' target='_blank' rel='noopener' style='display:inline-block;padding:6px 12px;background:rgba(255,255,255,0.06);border:1px solid rgba(255,255,255,0.16);border-radius:7px;color:#ffffff;text-decoration:none;font-size:11px;font-weight:600;'>X (Twitter) ↗</a></td>");
    sb.append("<td style='padding-bottom:6px;'><a href='https://www.facebook.com/fazleyrabbi1067/' target='_blank' rel='noopener' style='display:inline-block;padding:6px 12px;background:rgba(255,255,255,0.06);border:1px solid rgba(255,255,255,0.16);border-radius:7px;color:#ffffff;text-decoration:none;font-size:11px;font-weight:600;'>Facebook ↗</a></td>");
    sb.append("</tr></table></td></tr>");
    sb.append("<tr><td style='padding-top:4px;'><a href='mailto:fazlerabbicse65@gmail.com' style='color:#38bdf8;font-size:12px;text-decoration:none;font-weight:600;'>fazlerabbicse65@gmail.com ↗</a></td></tr>");
    sb.append("</table></td></tr></table>");
    sb.append("<p style='margin:20px 0 0;font-size:11px;color:#94a3b8;line-height:1.5;text-align:center;'>Automatic notification · Please do not reply to this email</p>");
    sb.append("</td></tr>");
    sb.append("<tr><td style='background-color:#f8fafc;padding:14px 26px;text-align:center;border-top:1px solid #e2e8f0;'>");
    sb.append("<span style='font-size:11px;color:#64748b;'>© 2026 Fazley Rabbi · Smart Attendance · All rights reserved</span>");
    sb.append("</td></tr></table></body></html>");
    return sb.toString();
  }
}
