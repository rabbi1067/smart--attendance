package com.smartattendance.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "admin_settings")
public class AdminSettings {
  @Id public String id;
  public String scriptUrl = "";
  public String sheetUrl = "";
  public String scriptSecret = "";
  public ScheduleConfig schedule = ScheduleConfig.defaults();
  // Transport options: APPS_SCRIPT (100% Free Google), EMAILJS (100% Free Frontend/Backend API), SMTP (Gmail App Password)
  public boolean emailEnabled = false;
  public String emailProvider = "APPS_SCRIPT";
  public String senderEmail = "";
  public String senderName = "Smart Attendance";
  // AES-256-GCM; never return this field to the browser.
  public String encryptedAppPassword = "";
  public String resendApiKey = "";
  public String brevoApiKey = "";
  // 100% Free EmailJS configuration
  public String emailJsServiceId = "";
  public String emailJsTemplateId = "";
  public String emailJsPublicKey = "";

  public AdminSettings() {}
  public AdminSettings(String id) { this.id = id; }
  public boolean hasGoogleSheets() {
    return scriptUrl != null && !scriptUrl.isBlank() && scriptSecret != null && !scriptSecret.isBlank();
  }
}
