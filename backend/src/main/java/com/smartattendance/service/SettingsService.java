package com.smartattendance.service;

import com.smartattendance.model.*;
import com.smartattendance.repo.SettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.*;

@Service
public class SettingsService {
  private final SettingsRepository repo;
  private final String siteOwner;
  private final EncryptionService encryption;
  public SettingsService(SettingsRepository repo, EncryptionService encryption, @Value("${app.site-owner}") String siteOwner) {
    this.repo = repo; this.encryption=encryption; this.siteOwner = siteOwner.trim().toLowerCase(Locale.ROOT);
  }
  public AdminSettings forUser(String email) {
    return repo.findById(email.toLowerCase(Locale.ROOT)).orElseGet(() -> new AdminSettings(email.toLowerCase(Locale.ROOT)));
  }
  public AdminSettings publicSettings() { return forUser(siteOwner); }
  public record EmailUpdate(boolean ENABLED, String PROVIDER, String SENDER_EMAIL, String SENDER_NAME,
                            String APP_PASSWORD, boolean CLEAR_APP_PASSWORD) {}
  public record ConfigUpdate(String SCRIPT_URL, String SHEET_URL, String SCRIPT_SECRET,
                             ScheduleConfig SCHEDULE_CONFIG, EmailUpdate EMAIL_CONFIG) {}
  public AdminSettings save(String email, ConfigUpdate input) {
    if (input == null || input.SCHEDULE_CONFIG() == null) throw new IllegalArgumentException("Schedule configuration is required");
    input.SCHEDULE_CONFIG().validate();
    String script = clean(input.SCRIPT_URL()), sheet = clean(input.SHEET_URL());
    if (!script.isEmpty() && !script.matches("https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec/?"))
      throw new IllegalArgumentException("Enter a valid HTTPS Google Apps Script /exec URL");
    if (!sheet.isEmpty() && !sheet.matches("https://docs\\.google\\.com/spreadsheets/d/[A-Za-z0-9_-]+(?:/.*)?"))
      throw new IllegalArgumentException("Enter a valid Google Sheets URL");
    AdminSettings saved = forUser(email);
    saved.scriptUrl = script; saved.sheetUrl = sheet;
    String secret = clean(input.SCRIPT_SECRET());
    if (!secret.isEmpty()) {
      if (secret.length() < 32 || secret.length() > 256) throw new IllegalArgumentException("Apps Script secret must have 32–256 characters");
      saved.scriptSecret = secret;
    }
    saved.schedule = input.SCHEDULE_CONFIG();
    if (input.EMAIL_CONFIG() != null) {
      EmailUpdate m = input.EMAIL_CONFIG();
      String provider=clean(m.PROVIDER()).toUpperCase(Locale.ROOT);
      if (!Set.of("APPS_SCRIPT","SMTP").contains(provider)) throw new IllegalArgumentException("Invalid email provider");
      String address=clean(m.SENDER_EMAIL()).toLowerCase(Locale.ROOT);
      String senderName=clean(m.SENDER_NAME());
      if (!address.isEmpty() && !address.matches("(?i)^[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}$"))
        throw new IllegalArgumentException("Invalid sender email address");
      if (senderName.length() < 2 || senderName.length()>55 || senderName.matches(".*[\\r\\n].*"))
        throw new IllegalArgumentException("Sender display name must contain 2–55 characters");
      if (m.CLEAR_APP_PASSWORD()) saved.encryptedAppPassword="";
      String password=clean(m.APP_PASSWORD()).replace(" ", "");
      if (!password.isEmpty()) {
        if (!password.matches("[a-zA-Z0-9]{16}"))
          throw new IllegalArgumentException("Gmail App Password must be 16 characters (spaces optional)");
        saved.encryptedAppPassword=encryption.encrypt(password);
      }
      if (m.ENABLED() && "SMTP".equals(provider)) {
        if (!address.endsWith("@gmail.com") && !address.endsWith("@googlemail.com"))
          throw new IllegalArgumentException("Gmail SMTP mode requires a Gmail sender account");
        if (saved.encryptedAppPassword == null || saved.encryptedAppPassword.isBlank())
          throw new IllegalArgumentException("Add a Gmail App Password before enabling SMTP email");
        // Also reject an encryption key that cannot decrypt an existing password after key rotation.
        encryption.decrypt(saved.encryptedAppPassword);
      }
      saved.emailEnabled = m.ENABLED();
      saved.emailProvider = provider;
      saved.senderEmail = address;
      saved.senderName = senderName;
    }
    return repo.save(saved);
  }
  private String clean(String value) { return value == null ? "" : value.trim(); }
  public record PublicConfig(boolean configured, String sheetUrl, ScheduleConfig schedule, String timezone) {}
  public PublicConfig publicConfig() {
    AdminSettings s = publicSettings();
    return new PublicConfig(!s.scriptUrl.isBlank() && !s.scriptSecret.isBlank(), s.sheetUrl, s.schedule, "Asia/Dhaka");
  }
  public record EmailConfig(boolean ENABLED, String PROVIDER, String SENDER_EMAIL, String SENDER_NAME,
                            boolean APP_PASSWORD_CONFIGURED) {}
  public record AdminConfig(String SCRIPT_URL, String SHEET_URL, boolean SECRET_CONFIGURED,
                            ScheduleConfig SCHEDULE_CONFIG, EmailConfig EMAIL_CONFIG,
                            String owner, boolean liveSiteOwner) {}
  public AdminConfig adminConfig(String email) {
    AdminSettings s = forUser(email);
    return new AdminConfig(s.scriptUrl, s.sheetUrl, s.scriptSecret!=null && !s.scriptSecret.isBlank(), s.schedule,
      new EmailConfig(s.emailEnabled, s.emailProvider==null?"APPS_SCRIPT":s.emailProvider,
                      s.senderEmail==null?"":s.senderEmail,
                      s.senderName==null?"Smart Attendance":s.senderName,
                      s.encryptedAppPassword!=null&&!s.encryptedAppPassword.isBlank()),
      email, email.equalsIgnoreCase(siteOwner));
  }
}
