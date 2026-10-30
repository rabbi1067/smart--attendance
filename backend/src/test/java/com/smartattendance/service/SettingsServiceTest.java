package com.smartattendance.service;

import com.smartattendance.model.*;
import com.smartattendance.repo.SettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SettingsServiceTest {
  private static final String OWNER="admin@example.com";
  @Test void failedSettingsWriteThrowsAndNeverPretendsToSave() {
    SettingsRepository repo=mock(SettingsRepository.class);
    when(repo.findById(OWNER)).thenReturn(Optional.empty());
    when(repo.save(any(AdminSettings.class))).thenThrow(new DataAccessResourceFailureException("MongoDB unavailable"));
    SettingsService settings=new SettingsService(repo,null,OWNER);
    var update=new SettingsService.ConfigUpdate(ScheduleConfig.defaults(),null);
    assertThrows(DataAccessResourceFailureException.class,()->settings.save(OWNER,update));
  }
  @Test void publicConfigContainsNoEmailCredentialFields() {
    // The public bundle is served without auth on /api/public/config; only schedule
    // data may be exposed. EmailJS/API credentials must never appear there.
    SettingsRepository repo=mock(SettingsRepository.class);
    AdminSettings stored=new AdminSettings(OWNER);
    stored.emailJsPublicKey="private-reference";
    stored.emailJsServiceId="service-reference";
    when(repo.findById(OWNER)).thenReturn(Optional.of(stored));
    var publicConfig=new SettingsService(repo,null,OWNER).publicConfig();
    var components=Arrays.stream(publicConfig.getClass().getRecordComponents())
        .map(c->c.getName().toUpperCase(Locale.ROOT)).toList();
    assertEquals(List.of("CONFIGURED","SCHEDULE","TIMEZONE","EMAILENABLED"),components);
    assertTrue(components.stream().noneMatch(n->n.contains("EMAILJS")||n.contains("KEY")||n.contains("PASSWORD")||n.contains("SECRET")));
  }
  @Test void savedEmailApiKeysAreNotReturnedByAdminConfig() {
    SettingsRepository repo=mock(SettingsRepository.class);
    AdminSettings stored=new AdminSettings(OWNER);
    stored.resendApiKey="enc:encryptedkey";
    when(repo.findById(OWNER)).thenReturn(Optional.of(stored));
    var config=new SettingsService(repo,null,OWNER).adminConfig(OWNER);
    assertTrue(config.EMAIL_CONFIG().RESEND_API_KEY().isBlank());
    assertTrue(config.EMAIL_CONFIG().RESEND_KEY_CONFIGURED());
  }
  @Test void invalidProviderIsRejected() {
    SettingsRepository repo=mock(SettingsRepository.class);
    when(repo.findById(OWNER)).thenReturn(Optional.empty());
    when(repo.save(any(AdminSettings.class))).thenAnswer(call->call.getArgument(0));
    SettingsService settings=new SettingsService(repo,null,OWNER);
    var email=new SettingsService.EmailUpdate(false,"APPS_SCRIPT","a@b.test","Smart Attendance","",false,"","","","",false);
    assertThrows(IllegalArgumentException.class,()->
        settings.save(OWNER,new SettingsService.ConfigUpdate(ScheduleConfig.defaults(),email)));
  }
  @Test void unknownStoredProviderFallsBackToEmailJs() {
    SettingsRepository repo=mock(SettingsRepository.class);
    AdminSettings stored=new AdminSettings(OWNER);
    stored.emailProvider="APPS_SCRIPT";
    when(repo.findById(OWNER)).thenReturn(Optional.of(stored));
    var config=new SettingsService(repo,null,OWNER).adminConfig(OWNER);
    assertEquals("EMAILJS",config.EMAIL_CONFIG().PROVIDER());
  }
  @Test void ownerSettingsAreCanonicalAcrossAdmins() {
    SettingsRepository repo=mock(SettingsRepository.class);
    AdminSettings stored=new AdminSettings(OWNER);
    when(repo.findById(OWNER)).thenReturn(Optional.of(stored));
    assertSame(stored,new SettingsService(repo,null,OWNER).forUser("secondary@example.com"));
    verify(repo).findById(OWNER);
  }
}
