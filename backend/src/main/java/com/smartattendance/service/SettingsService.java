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
  @Value("${app.emailjs-template-id:}") String serverTemplateId;

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
                            String EMAILJS_SERVICE_ID, String EMAILJS_TEMPLATE_ID,
                            String EMAILJS_PUBLIC_KEY, String EMAILJS_PRIVATE_KEY,
                            boolean CLEAR_EMAILJS_KEYS) {
    public EmailUpdate(boolean ENABLED, String PROVIDER, String SENDER_EMAIL, String SENDER_NAME,
                       String APP_PASSWORD, boolean CLEAR_APP_PASSWORD,
                       String RESEND_API_KEY, String BREVO_API_KEY,
                       String EMAILJS_SERVICE_ID, String EMAILJS_PUBLIC_KEY,
                       boolean CLEAR_EMAILJS_KEYS) {
      this(ENABLED, PROVIDER, SENDER_EMAIL, SENDER_NAME, APP_PASSWORD, CLEAR_APP_PASSWORD,
           RESEND_API_KEY, BREVO_API_KEY, EMAILJS_SERVICE_ID, "", EMAILJS_PUBLIC_KEY, "", CLEAR_EMAILJS_KEYS);
    }
  }
  public record ConfigUpdate(ScheduleConfig SCHEDULE_CONFIG, EmailUpdate EMAIL_CONFIG) {}
  private static String clean(String value) { return value == null ? "" : value.trim(); }
  private static String provider(String value) { return clean(value).toUpperCase(Locale.ROOT); }

  public AdminSettings save(String email, ConfigUpdate input) {
    if (input == null || input.SCHEDULE_CONFIG() == null) throw new IllegalArgumentException("Schedule configuration is required");
    input.SCHEDULE_CONFIG().validate();

    AdminSettings old=forUser(email), updated=new AdminSettings(siteOwner);
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
    updated.emailJsPrivateKey=old.emailJsPrivateKey;
    if (input.EMAIL_CONFIG() != null) {
      EmailUpdate m=input.EMAIL_CONFIG();
      String delivery=provider(m.PROVIDER());
      if (!Set.of("EMAILJS","SMTP","RESEND","BREVO").contains(delivery))
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
      if (!clean(m.EMAILJS_PRIVATE_KEY()).isEmpty()) updated.emailJsPrivateKey="enc:"+encryption.encrypt(clean(m.EMAILJS_PRIVATE_KEY()));
      // Explicit removal only — blank fields never wipe saved keys (they stay until the user removes them).
      if (m.CLEAR_EMAILJS_KEYS()) {
        updated.emailJsServiceId="";
        updated.emailJsTemplateId="";
        updated.emailJsPublicKey="";
        updated.emailJsPrivateKey="";
      }
    }
    if (updated.emailEnabled) {
      switch (updated.emailProvider) {
        case "SMTP" -> {
          if (!updated.senderEmail.endsWith("@gmail.com") && !updated.senderEmail.endsWith("@googlemail.com"))
            throw new IllegalArgumentException("Gmail SMTP requires a Gmail sender address");
          if (clean(updated.encryptedAppPassword).isEmpty()) throw new IllegalArgumentException("Gmail App Password is required");
          encryption.decrypt(updated.encryptedAppPassword);
        }
        case "EMAILJS" -> {
          String templateId = !clean(updated.emailJsTemplateId).isEmpty() ? clean(updated.emailJsTemplateId) : clean(serverTemplateId);
          if (clean(updated.emailJsServiceId).isEmpty() || clean(updated.emailJsPublicKey).isEmpty())
            throw new IllegalArgumentException("EmailJS Service ID and Public Key are required");
          if (templateId.isEmpty())
            throw new IllegalArgumentException("EmailJS Template ID is required");
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

  public record PublicConfig(boolean configured,ScheduleConfig schedule,String timezone,boolean emailEnabled) {}
  public PublicConfig publicConfig() {
    AdminSettings s=publicSettings();
    return new PublicConfig(true,s.schedule == null ? ScheduleConfig.defaults() : s.schedule,"Asia/Dhaka",s.emailEnabled);
  }
  public record EmailConfig(boolean ENABLED,String PROVIDER,String SENDER_EMAIL,String SENDER_NAME,
                            boolean APP_PASSWORD_CONFIGURED,String RESEND_API_KEY,String BREVO_API_KEY,
                            String EMAILJS_SERVICE_ID,String EMAILJS_TEMPLATE_ID,String EMAILJS_PUBLIC_KEY,
                            boolean EMAILJS_PRIVATE_KEY_CONFIGURED,
                            boolean RESEND_KEY_CONFIGURED,boolean BREVO_KEY_CONFIGURED) {
    public EmailConfig(boolean ENABLED,String PROVIDER,String SENDER_EMAIL,String SENDER_NAME,
                       boolean APP_PASSWORD_CONFIGURED,String RESEND_API_KEY,String BREVO_API_KEY,
                       String EMAILJS_SERVICE_ID,String EMAILJS_PUBLIC_KEY,
                       boolean RESEND_KEY_CONFIGURED,boolean BREVO_KEY_CONFIGURED) {
      this(ENABLED,PROVIDER,SENDER_EMAIL,SENDER_NAME,APP_PASSWORD_CONFIGURED,RESEND_API_KEY,BREVO_API_KEY,
           EMAILJS_SERVICE_ID,"",EMAILJS_PUBLIC_KEY,false,RESEND_KEY_CONFIGURED,BREVO_KEY_CONFIGURED);
    }
  }
  public record AdminConfig(ScheduleConfig SCHEDULE_CONFIG,EmailConfig EMAIL_CONFIG,
                            String owner,boolean liveSiteOwner) {}
  public AdminConfig adminConfig(String email) {
    AdminSettings s=forUser(email);
    // The retired APPS_SCRIPT provider degrades to the new EMAILJS default.
    String provider=s.emailProvider == null || "APPS_SCRIPT".equals(s.emailProvider)?"EMAILJS":s.emailProvider;
    return new AdminConfig(s.schedule == null?ScheduleConfig.defaults():s.schedule,
        new EmailConfig(s.emailEnabled,provider,
            clean(s.senderEmail),clean(s.senderName).isBlank()?"Smart Attendance":s.senderName,
            !clean(s.encryptedAppPassword).isEmpty(),"","",clean(s.emailJsServiceId),
            clean(s.emailJsTemplateId),clean(s.emailJsPublicKey),
            !clean(s.emailJsPrivateKey).isEmpty(),
            !clean(s.resendApiKey).isEmpty(),!clean(s.brevoApiKey).isEmpty()),
        siteOwner,true);
  }
}
