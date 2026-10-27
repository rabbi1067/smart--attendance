package com.smartattendance.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartattendance.model.*;
import com.smartattendance.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ApiControllerTest {
  final ObjectMapper mapper = new ObjectMapper();
  final SettingsService settings=mock(SettingsService.class);
  final AppsScriptClient script=mock(AppsScriptClient.class);
  final NotificationService notifications=mock(NotificationService.class);
  final AttendanceStorageService storage=mock(AttendanceStorageService.class);
  final ApiController api=new ApiController(settings,script,null,null,notifications,storage,mapper);

  private void setup() {
    var s=new AdminSettings("owner@example.test");
    s.scriptUrl="https://script.google.com/macros/s/test/exec";
    s.scriptSecret="enc:encrypted-secret";
    s.emailEnabled=true;s.emailProvider="APPS_SCRIPT";
    when(settings.publicSettings()).thenReturn(s);
    when(notifications.usingScript(s)).thenReturn(true);
    when(notifications.safeSender(s)).thenReturn("Smart Attendance");
    // Run the background mirror/email delivery synchronously so tests are deterministic.
    api.deliveryExecutor=Runnable::run;
  }
  @Test void mongoSaveSucceedsDespiteMirrorFailureAndReportsIt() throws Exception {
    setup();
    when(script.post(any(),anyMap())).thenReturn(mapper.readTree("{\"ok\":false,\"error\":\"server\"}"));
    when(notifications.receiptStatus(any(),anyString(),anyString(),anyString(),anyString(),any()))
        .thenReturn("failed");
    JsonNode result=(JsonNode)api.submit(new ApiController.Attendance("Student Name","2023200000686","student@example.test"));
    assertTrue(result.path("ok").asBoolean());
    // Mirroring now continues in the background; the student immediately learns the save succeeded.
    assertEquals("pending",result.path("mirrorStatus").asText());
    assertEquals("queued",result.path("emailStatus").asText());
    verify(storage,times(1)).save(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    verify(script,times(1)).post(any(),anyMap());
  }
  @Test void mirrorTimeoutDoesNotRetryAndMongoWriteRemainsAuthoritative() {
    setup();
    when(script.post(any(),anyMap())).thenThrow(new IllegalStateException("Script not available"));
    when(notifications.receiptStatus(any(),anyString(),anyString(),anyString(),anyString(),isNull()))
        .thenReturn("failed");
    JsonNode result=(JsonNode)api.submit(new ApiController.Attendance("Student Name","2023200000686","student@example.test"));
    assertTrue(result.path("ok").asBoolean());
    assertEquals("pending",result.path("mirrorStatus").asText());
    verify(script,times(1)).post(any(),anyMap());
    verify(storage).save(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
  }
}
