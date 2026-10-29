package com.smartattendance.model;

import com.smartattendance.service.EncryptionService;
import com.smartattendance.service.NotificationService;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class NotificationSettingsTest {
  @Test void encryptionRoundTripAndNoPlaintextStorage() {
    byte[] raw=new byte[32];for(int i=0;i<32;i++)raw[i]=(byte)(i*7+11);
    var crypto=new EncryptionService(Base64.getEncoder().encodeToString(raw));
    String password="abcdefghijklmnop";
    String encrypted=crypto.encrypt(password);
    assertFalse(encrypted.contains(password));
    assertEquals(password,crypto.decrypt(encrypted));
    assertNotEquals(encrypted,crypto.encrypt(password)); // randomized nonce
  }
  @Test void noEmailIsTriggeredWhenDisabled() {
    var settings=new AdminSettings("admin@example.com");
    settings.emailEnabled=false;
    var notifier=new NotificationService(null);
    assertEquals("disabled",notifier.receiptStatus(settings,"student@example.com","Student","2023200000686","",null));
  }
  @Test void noEmailWhenStudentDidNotProvideAddress() {
    var settings=new AdminSettings("admin@example.com");
    settings.emailEnabled=true;
    var notifier=new NotificationService(null);
    assertEquals("no_address",notifier.receiptStatus(settings,"","Student","2023200000686","",null));
  }
  @Test void defaultProviderIsEmailJs() {
    var settings=new AdminSettings("admin@example.com");
    assertEquals("EMAILJS",settings.emailProvider);
  }
}
