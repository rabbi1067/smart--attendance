package com.smartattendance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartattendance.model.AdminSettings;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NotificationServiceTest {
  private final NotificationService service = new NotificationService(null);
  private AdminSettings settings() {
    AdminSettings s = new AdminSettings("owner@example.test");
    s.emailEnabled = true;
    s.emailProvider = "EMAILJS";
    return s;
  }

  @Test void missingAddressIsReportedWithoutSending() {
    assertEquals("no_address", service.receiptStatus(settings(), "", "Student", "2023200000686", "CSE", null));
    assertEquals("no_address", service.receiptStatus(settings(), null, "Student", "2023200000686", "CSE", null));
  }

  @Test void disabledEmailIsNeverSent() {
    AdminSettings s = settings();
    s.emailEnabled = false;
    assertEquals("disabled", service.receiptStatus(s, "student@example.test", "Student", "2023200000686", "CSE", null));
  }

  @Test void legacyAppsScriptProviderFallsBackToEmailJsOnly() {
    // Records saved with the retired APPS_SCRIPT provider must no longer route there;
    // they degrade to the default EmailJS path (and fail cleanly if it is unconfigured).
    AdminSettings s = settings();
    s.emailProvider = "APPS_SCRIPT";
    s.emailJsPublicKey = "";
    assertEquals("failed", service.receiptStatus(s, "student@example.test", "Student", "2023200000686", "CSE", null));
  }

  @Test void testUnknownProviderFailsCleanly() {
    AdminSettings s = settings();
    s.emailProvider = "CARRIER_PIGEON";
    assertEquals("failed", service.receiptStatus(s, "student@example.test", "Student", "2023200000686", "CSE", null));
  }
}
