package com.smartattendance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

@Service
public class AdminAuthService {
  private final Map<String,String> accounts = new HashMap<>();
  private final ConcurrentHashMap<String,Attempt> attempts = new ConcurrentHashMap<>();
  private final ObjectMapper mapper;
  @Value("${app.admin-email}") private String email;
  @Value("${app.admin-password}") private String password;
  @Value("${app.admin-users-json}") private String usersJson;
  public record Account(String email, String password) {}
  private record Attempt(int count, long resetAt) {}
  public AdminAuthService(ObjectMapper mapper) { this.mapper = mapper; }
  @PostConstruct public void init() throws Exception {
    if (!email.isBlank() && !password.isBlank()) accounts.put(email.toLowerCase(Locale.ROOT).trim(), password);
    for (Account a : mapper.readValue(usersJson, new TypeReference<List<Account>>(){})) {
      if (a.email() == null || a.password() == null || a.password().isBlank() || !a.email().contains("@"))
        throw new IllegalStateException("Invalid ADMIN_USERS_JSON entry");
      accounts.put(a.email().toLowerCase(Locale.ROOT).trim(), a.password());
    }
    if (accounts.isEmpty()) throw new IllegalStateException("Configure ADMIN_EMAIL/ADMIN_PASSWORD or ADMIN_USERS_JSON");
  }
  public boolean exists(String user) { return user != null && accounts.containsKey(user.toLowerCase(Locale.ROOT)); }
  public boolean login(String suppliedEmail, String suppliedPassword, String ip) {
    String user = suppliedEmail == null ? "" : suppliedEmail.trim().toLowerCase(Locale.ROOT);
    String key = ip+"|"+user; long now = System.currentTimeMillis();
    Attempt a = attempts.compute(key, (k, old) -> old == null || now >= old.resetAt() ? new Attempt(1, now+15*60_000L) : new Attempt(old.count()+1, old.resetAt()));
    if (a.count() > 6) throw new TooManyAttemptsException();
    String expected = accounts.getOrDefault(user, "unknown-admin-secret");
    byte[] one = expected.getBytes(StandardCharsets.UTF_8);
    byte[] two = (suppliedPassword == null ? "" : suppliedPassword).getBytes(StandardCharsets.UTF_8);
    boolean match = MessageDigest.isEqual(one, two) && accounts.containsKey(user);
    if (match) attempts.remove(key);
    return match;
  }
  public static class TooManyAttemptsException extends RuntimeException {}
}
