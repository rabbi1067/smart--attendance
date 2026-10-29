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
  final NotificationService notifications=mock(NotificationService.class);
  final AttendanceStorageService storage=mock(AttendanceStorageService.class);
  final ApiController api=new ApiController(settings,null,null,notifications,storage,mapper);

  private void setup() {
    var s=new AdminSettings("owner@example.test");
    s.emailEnabled=true;s.emailProvider="EMAILJS";
    when(settings.publicSettings()).thenReturn(s);
    // Run the background email delivery synchronously so tests are deterministic.
    api.deliveryExecutor=Runnable::run;
  }
  @Test void submitSavesInstantlyAndEmailsInBackend() throws Exception {
    setup();
    when(notifications.receiptStatus(any(),anyString(),anyString(),anyString(),anyString(),isNull()))
        .thenReturn("sent");
    JsonNode result=(JsonNode)api.submit(new ApiController.Attendance("Student Name","2023200000686","student@example.test"));
    assertTrue(result.path("ok").asBoolean());
    // Email happens in the background; the student immediately learns the save succeeded.
    assertEquals("queued",result.path("emailStatus").asText());
    // Mirror reporting is fully retired: the field no longer exists in the response.
    assertTrue(result.path("mirrorStatus").isMissingNode());
    verify(storage,times(1)).save(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    verify(notifications,times(1)).receiptStatus(any(),anyString(),anyString(),anyString(),anyString(),isNull());
  }
  @Test void duplicateEmailRecordYieldsNoAddressStatus() throws Exception {
    setup();
    JsonNode result=(JsonNode)api.submit(new ApiController.Attendance("Student Name","2023200000686",""));
    assertTrue(result.path("ok").asBoolean());
    assertEquals("no_address",result.path("emailStatus").asText());
    verify(notifications,never()).receiptStatus(any(),anyString(),anyString(),anyString(),anyString(),any());
  }
  @Test void disabledEmailNeverQueuesDelivery() throws Exception {
    var s=new AdminSettings("owner@example.test");
    s.emailEnabled=false;
    when(settings.publicSettings()).thenReturn(s);
    api.deliveryExecutor=Runnable::run;
    JsonNode result=(JsonNode)api.submit(new ApiController.Attendance("Student Name","2023200000686","student@example.test"));
    assertTrue(result.path("ok").asBoolean());
    assertEquals("disabled",result.path("emailStatus").asText());
    verify(notifications,never()).receiptStatus(any(),anyString(),anyString(),anyString(),anyString(),any());
  }
}
