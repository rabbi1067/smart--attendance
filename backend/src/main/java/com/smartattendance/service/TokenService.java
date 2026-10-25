package com.smartattendance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class TokenService {
  @Value("${app.jwt-secret}") private String jwtSecret;
  private final ObjectMapper mapper;
  public TokenService(ObjectMapper mapper) { this.mapper=mapper; }
  @PostConstruct public void validateSecret() {
    if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalStateException("JWT_SECRET must be at least 32 UTF-8 bytes");
  }
  private String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
  private byte[] sign(String data) throws Exception {
    Mac mac=Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
  }
  public String issue(String email) {
    try {
      String header=b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
      String payload=b64(mapper.writeValueAsBytes(Map.of("sub",email,"iat",Instant.now().getEpochSecond(),"exp",Instant.now().plusSeconds(4*3600).getEpochSecond())));
      String raw=header+"."+payload;
      return raw+"."+b64(sign(raw));
    } catch(Exception e) { throw new IllegalStateException("Could not sign token",e); }
  }
  public String verify(String token) {
    try {
      String[] parts=token.split("\\.");
      if (parts.length!=3) return null;
      byte[] expected=sign(parts[0]+"."+parts[1]);
      if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) return null;
      var head=mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
      if (!"HS256".equals(head.path("alg").asText())) return null;
      var p=mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
      if (p.path("exp").asLong(0) <= Instant.now().getEpochSecond()) return null;
      return p.path("sub").asText(null);
    } catch(Exception e) { return null; }
  }
}
