package com.smartattendance.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Service
public class EncryptionService {
  private final String secret;
  private final SecureRandom random = new SecureRandom();
  public EncryptionService(@Value("${app.mail-encryption-key:}") String secret) { this.secret=secret.trim(); }
  private SecretKeySpec key() {
    if(secret.isEmpty()) throw new IllegalStateException("MAIL_ENCRYPTION_KEY must be configured before saving a Gmail App Password");
    try {
      byte[] decoded=Base64.getDecoder().decode(secret);
      if(decoded.length!=32) throw new IllegalArgumentException();
      return new SecretKeySpec(decoded,"AES");
    }catch(IllegalArgumentException e){throw new IllegalStateException("MAIL_ENCRYPTION_KEY must be base64 of 32 random bytes");}
  }
  public String encrypt(String plain) {
    try {
      byte[] nonce=new byte[12]; random.nextBytes(nonce);
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,nonce));
      byte[] ciphertext=cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] combined=Arrays.copyOf(nonce,nonce.length+ciphertext.length);
      System.arraycopy(ciphertext,0,combined,nonce.length,ciphertext.length);
      return Base64.getEncoder().encodeToString(combined);
    }catch(Exception e){throw new IllegalStateException("Could not encrypt Gmail App Password");}
  }
  public String decrypt(String ciphertext) {
    try {
      byte[] combined=Base64.getDecoder().decode(ciphertext);
      if(combined.length<29) throw new IllegalArgumentException("Invalid encrypted secret");
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOfRange(combined,0,12)));
      return new String(cipher.doFinal(combined,12,combined.length-12),StandardCharsets.UTF_8);
    }catch(Exception e){throw new IllegalStateException("Stored email credentials are unavailable. Check MAIL_ENCRYPTION_KEY");}
  }
}
