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
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
  private final ObjectMapper mapper;
  public AppsScriptClient(ObjectMapper mapper) { this.mapper=mapper; }
  private void ensureConfigured(AdminSettings s) {
    if(s.scriptUrl==null||s.scriptUrl.isBlank()||s.scriptSecret==null||s.scriptSecret.isBlank())
      throw new IllegalStateException("Google Apps Script URL and gateway secret have not been configured");
  }
  public JsonNode get(AdminSettings s, Map<String,String> params) {
    ensureConfigured(s);
    try {
      Map<String,String> all=new LinkedHashMap<>(params);all.put("key",s.scriptSecret);
      StringJoiner joiner=new StringJoiner("&");
      all.forEach((k,v)->joiner.add(URLEncoder.encode(k,java.nio.charset.StandardCharsets.UTF_8)+"="+URLEncoder.encode(v,java.nio.charset.StandardCharsets.UTF_8)));
      var req=HttpRequest.newBuilder(URI.create(s.scriptUrl+"?"+joiner)).timeout(Duration.ofSeconds(27)).GET().build();
      return send(req);
    } catch(IllegalArgumentException e) { throw e; }
  }
  public JsonNode post(AdminSettings s, Map<String,Object> payload) {
    ensureConfigured(s);
    try {
      Map<String,Object> all=new LinkedHashMap<>(payload);all.put("key",s.scriptSecret);
      var req=HttpRequest.newBuilder(URI.create(s.scriptUrl)).timeout(Duration.ofSeconds(27))
          .header("Content-Type","text/plain;charset=utf-8")
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(all))).build();
      return send(req);
    } catch(Exception e) { throw new IllegalStateException("Unable to send Apps Script request", e); }
  }
  private JsonNode send(HttpRequest req) {
    try {
      var resp=client.send(req,HttpResponse.BodyHandlers.ofString());
      if(resp.statusCode()<200||resp.statusCode()>=300) throw new IllegalStateException("Apps Script returned HTTP "+resp.statusCode());
      JsonNode node=mapper.readTree(resp.body());
      if(!node.isObject()) throw new IllegalStateException("Apps Script returned invalid response");
      return node;
    }catch(Exception e) { throw new IllegalStateException("Google Sheets service unavailable; check deployment and secret",e); }
  }
}
