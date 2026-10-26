package com.smartattendance.service;

import com.fasterxml.jackson.databind.*;
import com.smartattendance.model.AdminSettings;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
public class AppsScriptClient {
  private final HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(7))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();
  private final ObjectMapper mapper;
  private final EncryptionService encryption;

  public AppsScriptClient(ObjectMapper mapper, EncryptionService encryption) {
    this.mapper = mapper;
    this.encryption = encryption;
  }
  private String scriptKey(AdminSettings s) {
    String stored=s.scriptSecret;
    if (stored == null || stored.isBlank()) throw new IllegalStateException("Apps Script secret not configured");
    // Compatible with existing plaintext MongoDB settings until the next settings save migrates them.
    return stored.startsWith("enc:") ? encryption.decrypt(stored.substring(4)) : stored;
  }

  private void ensureConfigured(AdminSettings s) {
    if (s.scriptUrl == null || s.scriptUrl.isBlank() || s.scriptSecret == null || s.scriptSecret.isBlank())
      throw new IllegalStateException("Google Apps Script URL and gateway secret have not been configured");
  }

  public JsonNode post(AdminSettings s, Map<String, Object> payload) {
    ensureConfigured(s);
    try {
      Map<String, Object> all = new LinkedHashMap<>(payload);
      all.put("key", scriptKey(s));
      var req = HttpRequest.newBuilder(URI.create(s.scriptUrl))
          .timeout(Duration.ofSeconds(12))
          .header("Content-Type", "text/plain;charset=utf-8")
          .header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(all)))
          .build();
      return send(req);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to send Apps Script request: " + e.getMessage(), e);
    }
  }

  private JsonNode send(HttpRequest req) {
    try {
      HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
      int status = resp.statusCode();

      // Handle Google Apps Script 301/302/303/307 redirect to user content echo URL
      int maxRedirects = 5;
      while ((status == 301 || status == 302 || status == 303 || status == 307) && maxRedirects-- > 0) {
        String loc = resp.headers().firstValue("Location").orElse(null);
        if (loc == null || loc.isBlank()) break;
        URI location = URI.create(loc);
        String redirectHost = location.getHost();
        if (!"https".equalsIgnoreCase(location.getScheme()) || redirectHost == null ||
            !(redirectHost.equals("script.googleusercontent.com") || redirectHost.equals("script.google.com") ||
              redirectHost.endsWith(".googleusercontent.com"))) {
          throw new IllegalStateException("Google Apps Script redirected to an untrusted address");
        }
        HttpRequest redirectReq = HttpRequest.newBuilder(location)
            .timeout(Duration.ofSeconds(9))
            .header("Accept", "application/json")
            .GET()
            .build();
        resp = client.send(redirectReq, HttpResponse.BodyHandlers.ofString());
        status = resp.statusCode();
      }

      if (status < 200 || status >= 300) {
        throw new IllegalStateException("Apps Script returned HTTP " + status + (resp.body() != null && !resp.body().isBlank() ? ": " + resp.body() : ""));
      }

      JsonNode node = mapper.readTree(resp.body());
      if (!node.isObject()) throw new IllegalStateException("Apps Script returned non-JSON response: " + resp.body());
      return node;
    } catch (Exception e) {
      throw new IllegalStateException("Google Sheets / Apps Script request failed: " + e.getMessage(), e);
    }
  }
}
