package com.smartattendance.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "admin_settings")
public class AdminSettings {
  @Id public String id;
  public ScheduleConfig schedule = ScheduleConfig.defaults();
  // Transport options: EMAILJS (100% free HTTPS API), SMTP (Gmail App Password), RESEND, BREVO
  public boolean emailEnabled = false;
  public String emailProvider = "EMAILJS";
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
}
