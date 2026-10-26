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
    var update=new SettingsService.ConfigUpdate("","","",ScheduleConfig.defaults(),null);
    assertThrows(DataAccessResourceFailureException.class,()->settings.save(OWNER,update));
  }
  @Test void publicConfigNeverContainsEmailJsCredentials() {
    SettingsRepository repo=mock(SettingsRepository.class);
    AdminSettings stored=new AdminSettings(OWNER);
    stored.emailJsPublicKey="private-reference";
    stored.emailJsServiceId="service-reference";
    when(repo.findById(OWNER)).thenReturn(Optional.of(stored));
    var publicConfig=new SettingsService(repo,null,OWNER).publicConfig();
    assertTrue(publicConfig.emailJsPublicKey().isEmpty());
    assertTrue(publicConfig.emailJsServiceId().isEmpty());
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
  @Test void newGatewaySecretsAreEncryptedInMongoSettings() {
    SettingsRepository repo=mock(SettingsRepository.class);
    when(repo.findById(OWNER)).thenReturn(Optional.empty());
    when(repo.save(any(AdminSettings.class))).thenAnswer(call->call.getArgument(0));
    String key=Base64.getEncoder().encodeToString(new byte[32]);
    EncryptionService crypto=new EncryptionService(key);
    var settings=new SettingsService(repo,crypto,OWNER);
    var saved=settings.save(OWNER,new SettingsService.ConfigUpdate("https://script.google.com/macros/s/abc-123/exec","",
        "X".repeat(40),ScheduleConfig.defaults(),null));
    assertTrue(saved.scriptSecret.startsWith("enc:"));
    assertFalse(saved.scriptSecret.contains("X".repeat(40)));
    assertEquals("X".repeat(40),crypto.decrypt(saved.scriptSecret.substring(4)));
  }
  @Test void legacyGatewaySecretIsMigratedOnSettingsSave() {
    SettingsRepository repo=mock(SettingsRepository.class);
    var old=new AdminSettings(OWNER);old.scriptSecret="Y".repeat(40);
    when(repo.findById(OWNER)).thenReturn(Optional.of(old));
    when(repo.save(any(AdminSettings.class))).thenAnswer(call->call.getArgument(0));
    String key=Base64.getEncoder().encodeToString(new byte[32]);
    var crypto=new EncryptionService(key);
    var saved=new SettingsService(repo,crypto,OWNER).save(OWNER,
        new SettingsService.ConfigUpdate("https://script.google.com/macros/s/abc-123/exec","","",ScheduleConfig.defaults(),null));
    assertTrue(saved.scriptSecret.startsWith("enc:"));
    assertEquals("Y".repeat(40),crypto.decrypt(saved.scriptSecret.substring(4)));
  }
  @Test void ownerSettingsAreCanonicalAcrossAdmins() {
    SettingsRepository repo=mock(SettingsRepository.class);
    AdminSettings stored=new AdminSettings(OWNER);
    when(repo.findById(OWNER)).thenReturn(Optional.of(stored));
    assertSame(stored,new SettingsService(repo,null,OWNER).forUser("secondary@example.com"));
    verify(repo).findById(OWNER);
  }
}
