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
  // Transport options: APPS_SCRIPT works on Render Free; SMTP requires unblocked egress.
  public boolean emailEnabled = false;
  public String emailProvider = "APPS_SCRIPT";
  public String senderEmail = "";
  public String senderName = "Smart Attendance";
  // AES-256-GCM; never return this field to the browser.
  public String encryptedAppPassword = "";
  public AdminSettings() {}
  public AdminSettings(String id) { this.id = id; }
}
