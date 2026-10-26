package com.smartattendance.service;

import com.smartattendance.model.*;
import com.smartattendance.repo.SettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;

/** Settings are shared by administrators and committed to MongoDB before success is returned. */
@Service
public class SettingsService {
  private final SettingsRepository repo;
  private final String siteOwner;
  private final EncryptionService encryption;

  public SettingsService(SettingsRepository repo, EncryptionService encryption, @Value("${app.site-owner}") String siteOwner) {
    this.repo=repo; this.encryption=encryption;
    if (siteOwner == null || siteOwner.isBlank()) throw new IllegalStateException("SITE_OWNER_EMAIL must be configured");
    this.siteOwner=siteOwner.trim().toLowerCase(Locale.ROOT);
  }
  public AdminSettings forUser(String email) {
    // One canonical persisted configuration prevents live users and admins from reading different settings.
    return repo.findById(siteOwner).orElseGet(() -> new AdminSettings(siteOwner));
  }
  public AdminSettings publicSettings() { return forUser(siteOwner); }

  public record EmailUpdate(boolean ENABLED, String PROVIDER, String SENDER_EMAIL, String SENDER_NAME,
                            String APP_PASSWORD, boolean CLEAR_APP_PASSWORD,
                            String RESEND_API_KEY, String BREVO_API_KEY,
                            String EMAILJS_SERVICE_ID, String EMAILJS_TEMPLATE_ID, String EMAILJS_PUBLIC_KEY) {}
  public record ConfigUpdate(String SCRIPT_URL, String SHEET_URL, String SCRIPT_SECRET,
                             ScheduleConfig SCHEDULE_CONFIG, EmailUpdate EMAIL_CONFIG) {}
  private static String clean(String value) { return value == null ? "" : value.trim(); }
  private static String provider(String value) { return clean(value).toUpperCase(Locale.ROOT); }

  public AdminSettings save(String email, ConfigUpdate input) {
    if (input == null || input.SCHEDULE_CONFIG() == null) throw new IllegalArgumentException("Schedule configuration is required");
    input.SCHEDULE_CONFIG().validate();
    String script=clean(input.SCRIPT_URL()), sheet=clean(input.SHEET_URL());
    if (!script.isEmpty() && !script.matches("https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec/?"))
      throw new IllegalArgumentException("Invalid HTTPS Google Apps Script /exec URL");
    if (!sheet.isEmpty() && !sheet.matches("https://docs\\.google\\.com/spreadsheets/d/[A-Za-z0-9_-]+(?:/.*)?"))
      throw new IllegalArgumentException("Invalid Google Sheets URL");

    AdminSettings old=forUser(email), updated=new AdminSettings(siteOwner);
    updated.scriptUrl=script;updated.sheetUrl=sheet;
    // New secrets are encrypted at rest, not stored as plaintext in MongoDB.
    // Older saved configurations are migrated to encryption on their next save.
    updated.scriptSecret=old.scriptSecret;
    String newSecret=clean(input.SCRIPT_SECRET());
    if (!newSecret.isEmpty()) {
      if (newSecret.length()<32 || newSecret.length()>256) throw new IllegalArgumentException("Apps Script secret must be 32–256 characters");
      updated.scriptSecret="enc:"+encryption.encrypt(newSecret);
    } else if (updated.scriptSecret != null && !updated.scriptSecret.isBlank() && !updated.scriptSecret.startsWith("enc:")) {
      updated.scriptSecret="enc:"+encryption.encrypt(updated.scriptSecret);
    }
    updated.schedule=input.SCHEDULE_CONFIG();
    updated.emailEnabled=old.emailEnabled;
    updated.emailProvider=old.emailProvider;
    updated.senderEmail=old.senderEmail;
    updated.senderName=old.senderName;
    updated.encryptedAppPassword=old.encryptedAppPassword;
    updated.resendApiKey=old.resendApiKey;
    updated.brevoApiKey=old.brevoApiKey;
    updated.emailJsServiceId=old.emailJsServiceId;
    updated.emailJsTemplateId=old.emailJsTemplateId;
    updated.emailJsPublicKey=old.emailJsPublicKey;
    if (input.EMAIL_CONFIG() != null) {
      EmailUpdate m=input.EMAIL_CONFIG();
      String delivery=provider(m.PROVIDER());
      if (!Set.of("APPS_SCRIPT","EMAILJS","SMTP","RESEND","BREVO").contains(delivery))
        throw new IllegalArgumentException("Invalid email delivery provider");
      String sender=clean(m.SENDER_EMAIL()).toLowerCase(Locale.ROOT), senderName=clean(m.SENDER_NAME());
      if (!sender.isEmpty() && (sender.length()>100 || !sender.matches("(?i)^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")))
        throw new IllegalArgumentException("Invalid sender email address");
      if (senderName.length()<2 || senderName.length()>55 || senderName.matches("(?s).*[\\r\\n].*"))
        throw new IllegalArgumentException("Sender name must contain 2–55 characters");
      updated.emailEnabled=m.ENABLED();updated.emailProvider=delivery;
      updated.senderEmail=sender;updated.senderName=senderName;
      if (m.CLEAR_APP_PASSWORD()) updated.encryptedAppPassword="";
      String appPass=clean(m.APP_PASSWORD()).replace(" ","");
      if (!appPass.isEmpty()) {
        if (!appPass.matches("[A-Za-z0-9]{16}")) throw new IllegalArgumentException("Gmail App Password must be 16 characters");
        updated.encryptedAppPassword=encryption.encrypt(appPass);
      }
      if (!clean(m.RESEND_API_KEY()).isEmpty()) updated.resendApiKey="enc:"+encryption.encrypt(clean(m.RESEND_API_KEY()));
      if (!clean(m.BREVO_API_KEY()).isEmpty()) updated.brevoApiKey="enc:"+encryption.encrypt(clean(m.BREVO_API_KEY()));
      if (!clean(m.EMAILJS_SERVICE_ID()).isEmpty()) updated.emailJsServiceId=clean(m.EMAILJS_SERVICE_ID());
      if (!clean(m.EMAILJS_TEMPLATE_ID()).isEmpty()) updated.emailJsTemplateId=clean(m.EMAILJS_TEMPLATE_ID());
      if (!clean(m.EMAILJS_PUBLIC_KEY()).isEmpty()) updated.emailJsPublicKey=clean(m.EMAILJS_PUBLIC_KEY());
    }
    if (updated.emailEnabled) {
      switch (updated.emailProvider) {
        case "APPS_SCRIPT" -> {
          if (!updated.hasGoogleSheets()) throw new IllegalArgumentException("Enable Apps Script email after configuring URL and gateway secret");
        }
        case "SMTP" -> {
          if (!updated.senderEmail.endsWith("@gmail.com") && !updated.senderEmail.endsWith("@googlemail.com"))
            throw new IllegalArgumentException("Gmail SMTP requires a Gmail sender address");
          if (clean(updated.encryptedAppPassword).isEmpty()) throw new IllegalArgumentException("Gmail App Password is required");
          encryption.decrypt(updated.encryptedAppPassword);
        }
        case "EMAILJS" -> {
          if (clean(updated.emailJsServiceId).isEmpty() || clean(updated.emailJsTemplateId).isEmpty() || clean(updated.emailJsPublicKey).isEmpty())
            throw new IllegalArgumentException("EmailJS Service ID, Template ID, and Public Key are required");
        }
        case "RESEND", "BREVO" -> {
          String key="RESEND".equals(updated.emailProvider)?updated.resendApiKey:updated.brevoApiKey;
          if (clean(key).isEmpty() || updated.senderEmail.isBlank()) throw new IllegalArgumentException("Configure provider API key and verified sender email");
        }
        default -> throw new IllegalArgumentException("Unsupported email provider");
      }
    }
    // Do not swallow failures or modify the published settings until MongoDB commits.
    return repo.save(updated);
  }

  public record PublicConfig(boolean configured,String sheetUrl,ScheduleConfig schedule,String timezone,
                             boolean emailEnabled,String emailProvider,
                             String emailJsServiceId,String emailJsTemplateId,String emailJsPublicKey) {}
  public PublicConfig publicConfig() {
    AdminSettings s=publicSettings();
    return new PublicConfig(true,"",s.schedule == null ? ScheduleConfig.defaults() : s.schedule,
        "Asia/Dhaka",s.emailEnabled,s.emailProvider == null?"APPS_SCRIPT":s.emailProvider,"","","");
  }
  public record EmailConfig(boolean ENABLED,String PROVIDER,String SENDER_EMAIL,String SENDER_NAME,
                            boolean APP_PASSWORD_CONFIGURED,String RESEND_API_KEY,String BREVO_API_KEY,
                            String EMAILJS_SERVICE_ID,String EMAILJS_TEMPLATE_ID,String EMAILJS_PUBLIC_KEY,
                            boolean RESEND_KEY_CONFIGURED,boolean BREVO_KEY_CONFIGURED) {}
  public record AdminConfig(String SCRIPT_URL,String SHEET_URL,boolean SECRET_CONFIGURED,
                            ScheduleConfig SCHEDULE_CONFIG,EmailConfig EMAIL_CONFIG,
                            String owner,boolean liveSiteOwner) {}
  public AdminConfig adminConfig(String email) {
    AdminSettings s=forUser(email);
    return new AdminConfig(clean(s.scriptUrl),clean(s.sheetUrl),!clean(s.scriptSecret).isEmpty(),
        s.schedule == null?ScheduleConfig.defaults():s.schedule,
        new EmailConfig(s.emailEnabled,s.emailProvider == null?"APPS_SCRIPT":s.emailProvider,
            clean(s.senderEmail),clean(s.senderName).isBlank()?"Smart Attendance":s.senderName,
            !clean(s.encryptedAppPassword).isEmpty(),"","",clean(s.emailJsServiceId),clean(s.emailJsTemplateId),
            clean(s.emailJsPublicKey),!clean(s.resendApiKey).isEmpty(),!clean(s.brevoApiKey).isEmpty()),
        siteOwner,true);
  }
}
