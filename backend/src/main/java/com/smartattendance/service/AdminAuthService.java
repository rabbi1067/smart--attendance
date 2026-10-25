package com.smartattendance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

@Service
public class AdminAuthService {
  private final Map<String,String> accounts = new HashMap<>();
  private final ConcurrentHashMap<String,Attempt> attempts = new ConcurrentHashMap<>();
  private final ObjectMapper mapper;
  @Value("${app.admin-email:}") private String email;
  @Value("${app.admin-password:}") private String password;
  @Value("${app.admin-users-json:[]}") private String usersJson;

  public record Account(String email, String password) {}
  private record Attempt(int count, long resetAt) {}
  public AdminAuthService(ObjectMapper mapper) { this.mapper = mapper; }

  @PostConstruct public void init() {
    String mainEmail = (email != null && !email.isBlank()) ? email.trim().toLowerCase(Locale.ROOT) : "fazlerabbicse65@gmail.com";
    String mainPass = (password != null && !password.isBlank()) ? password.trim() : "admin";
    accounts.put(mainEmail, mainPass);
    accounts.put("admin@example.com", mainPass);

    try {
      if (usersJson != null && !usersJson.isBlank()) {
        for (Account a : mapper.readValue(usersJson, new TypeReference<List<Account>>(){})) {
          if (a.email() != null && a.password() != null && !a.password().isBlank()) {
            accounts.put(a.email().toLowerCase(Locale.ROOT).trim(), a.password().trim());
          }
        }
      }
    } catch(Exception ignored) {}
  }

  public boolean exists(String user) {
    return user != null && accounts.containsKey(user.toLowerCase(Locale.ROOT).trim());
  }

  public boolean login(String suppliedEmail, String suppliedPassword, String ip) {
    if (suppliedEmail == null || suppliedPassword == null) return false;
    String user = suppliedEmail.trim().toLowerCase(Locale.ROOT);
    String pass = suppliedPassword.trim();

    String key = ip + "|" + user;
    long now = System.currentTimeMillis();
    Attempt a = attempts.compute(key, (k, old) -> old == null || now >= old.resetAt() ? new Attempt(1, now + 15 * 60_000L) : new Attempt(old.count() + 1, old.resetAt()));
    if (a.count() > 40) throw new TooManyAttemptsException();

    String expected = accounts.get(user);
    if (expected == null) return false;

    boolean match = expected.equals(pass) || expected.equals(suppliedPassword);
    if (match) attempts.remove(key);
    return match;
  }
  public static class TooManyAttemptsException extends RuntimeException {}
}
