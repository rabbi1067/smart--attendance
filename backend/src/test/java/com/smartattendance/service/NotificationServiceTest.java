package com.smartattendance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartattendance.model.AdminSettings;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NotificationServiceTest {
  private final NotificationService service = new NotificationService(null, null);
  private AdminSettings settings() {
    AdminSettings s = new AdminSettings("owner@example.test");
    s.emailEnabled = true;
    s.emailProvider = "APPS_SCRIPT";
    s.scriptUrl = "https://script.google.com/macros/s/test/exec";
    s.scriptSecret = "enc:secret";
    return s;
  }
  @Test void successfulMirrorAndEmailReportsSent() throws Exception {
    String status = service.receiptStatus(settings(), "student@example.test", "Student", "2023200000686", "CSE",
        new ObjectMapper().readTree("{\"ok\":true,\"emailStatus\":\"sent\"}"));
    assertEquals("sent", status);
  }
  @Test void upstreamErrorOrTimeoutDoesNotSendDuplicateEmail() throws Exception {
    var s=settings();
    assertEquals("failed", service.receiptStatus(s, "student@example.test", "Student", "2023200000686", "CSE", null));
    assertEquals("failed", service.receiptStatus(s, "student@example.test", "Student", "2023200000686", "CSE",
        new ObjectMapper().readTree("{\"ok\":false,\"emailStatus\":\"sent\"}")));
    assertEquals("failed", service.receiptStatus(s, "student@example.test", "Student", "2023200000686", "CSE",
        new ObjectMapper().readTree("{\"ok\":true,\"emailStatus\":\"failed\"}")));
  }
  @Test void emailDisabledNeverDeliversEvenIfMirrorSaysSent() throws Exception {
    var s=settings();s.emailEnabled=false;
    assertEquals("disabled", service.receiptStatus(s,"student@example.test","Student","2023200000686","CSE",
        new ObjectMapper().readTree("{\"ok\":true,\"emailStatus\":\"sent\"}")));
  }
}
