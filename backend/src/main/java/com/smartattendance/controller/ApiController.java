package com.smartattendance.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartattendance.model.*;
import com.smartattendance.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final SettingsService settings;
  private final AppsScriptClient script;
  private final AdminAuthService auth;
  private final TokenService tokens;
  private final NotificationService notifications;
  private static final ZoneId DHAKA=ZoneId.of("Asia/Dhaka");
  public ApiController(SettingsService settings, AppsScriptClient script, AdminAuthService auth, TokenService tokens, NotificationService notifications) {
    this.settings=settings;this.script=script;this.auth=auth;this.tokens=tokens;this.notifications=notifications;
  }
  @GetMapping("/public/config") public Object publicConfig() { return settings.publicConfig(); }
  @GetMapping("/public/rows") public JsonNode rows(@RequestParam(defaultValue="") String cls) {
    if (cls.length()>50) throw new IllegalArgumentException("Invalid class");
    return script.get(settings.publicSettings(),Map.of("date",LocalDate.now(DHAKA).toString(),"cls",cls,"force","1"));
  }
  public record Attendance(@NotBlank @Size(min=2,max=80) String name,
                           @NotBlank @Pattern(regexp="[0-9]{13}") String id,
                           @Size(max=100) @Email String email) {}
  @PostMapping("/public/attendance") public JsonNode submit(@Valid @RequestBody Attendance data) {
    AdminSettings s=settings.publicSettings();
    String cls=s.schedule.activeClass(ZonedDateTime.now(DHAKA));
    if (cls==null) throw new ClosedException();
    String recipient=data.email()==null?"":data.email().trim();
    JsonNode recorded=script.post(s,Map.of("name",data.name().trim(),"id",data.id(),"email",recipient,"cls",cls,
         "mailEnabled",notifications.usingScript(s),"senderName",notifications.safeSender(s)));
    if(!recorded.path("ok").asBoolean(false))return recorded;
    String status=notifications.receiptStatus(s,recipient,data.name().trim(),data.id(),cls,recorded);
    ObjectNode result=((ObjectNode)recorded).deepCopy();result.put("emailStatus",status);
    return result;
  }
  public record Login(@NotBlank @Email String email,@NotBlank String password) {}
  @PostMapping("/admin/auth/login") public Object login(@Valid @RequestBody Login input,HttpServletRequest request) {
    // Trust only the real TCP peer, not client-supplied X-Forwarded-For values.
    if(!auth.login(input.email(),input.password(),request.getRemoteAddr())) throw new InvalidLoginException();
    String email=input.email().trim().toLowerCase(Locale.ROOT);
    return Map.of("ok",true,"token",tokens.issue(email),"email",email,"expiresInSeconds",14400);
  }
  @GetMapping("/admin/config") public Object getAdminConfig(Authentication principal) { return settings.adminConfig(principal.getName()); }
  @PutMapping("/admin/config") public Object saveAdminConfig(Authentication principal, @RequestBody SettingsService.ConfigUpdate update) {
    settings.save(principal.getName(),update);
    return settings.adminConfig(principal.getName());
  }
  @PostMapping("/admin/notifications/test") public Object sendTest(Authentication principal) {
    return notifications.test(settings.forUser(principal.getName()),principal.getName());
  }
  @GetMapping("/admin/sheets") public JsonNode sheets(Authentication principal) {
    return script.get(settings.forUser(principal.getName()),Map.of("action","adminSheets"));
  }
  @GetMapping("/admin/sheets/rows") public JsonNode adminRows(Authentication principal,@RequestParam String sheet) {
    if(sheet.isBlank()||sheet.length()>100) throw new IllegalArgumentException("Invalid sheet name");
    return script.get(settings.forUser(principal.getName()),Map.of("action","adminRows","sheet",sheet));
  }
  public record RowChange(String sheet,Integer rowIndex,List<String> values) {}
  @PostMapping("/admin/sheets/rows/add") public JsonNode add(Authentication principal,@RequestBody RowChange data) {
    return mutate(principal,"adminAdd",data);
  }
  @PostMapping("/admin/sheets/rows/edit") public JsonNode edit(Authentication principal,@RequestBody RowChange data) {
    return mutate(principal,"adminEdit",data);
  }
  @PostMapping("/admin/sheets/rows/delete") public JsonNode delete(Authentication principal,@RequestBody RowChange data) {
    return mutate(principal,"adminDelete",data);
  }
  private JsonNode mutate(Authentication p,String action,RowChange d) {
    if(d.sheet()==null||d.sheet().isBlank()||d.sheet().length()>100) throw new IllegalArgumentException("Invalid sheet");
    if(!action.equals("adminAdd")&&(d.rowIndex()==null||d.rowIndex()<2)) throw new IllegalArgumentException("Invalid row number");
    if(!action.equals("adminDelete")&&(d.values()==null||d.values().size()>12)) throw new IllegalArgumentException("Invalid row values");
    return script.post(settings.forUser(p.getName()),Map.of("action",action,"sheet",d.sheet(),"rowIndex",d.rowIndex()==null?0:d.rowIndex(),"values",d.values()==null?List.of():d.values()));
  }
  static class ClosedException extends RuntimeException {}
  static class InvalidLoginException extends RuntimeException {}
  @RestControllerAdvice public static class Errors {
    @ExceptionHandler(ClosedException.class) ResponseEntity<?> closed() {return ResponseEntity.status(403).body(Map.of("ok",false,"error","closed"));}
    @ExceptionHandler(InvalidLoginException.class) ResponseEntity<?> invalid() {return ResponseEntity.status(401).body(Map.of("ok",false,"error","account_not_found_or_wrong_password","message","Account not found or invalid credentials"));}
    @ExceptionHandler(AdminAuthService.TooManyAttemptsException.class) ResponseEntity<?> rate() {return ResponseEntity.status(429).body(Map.of("ok",false,"error","too_many_attempts"));}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.bind.MethodArgumentNotValidException.class})
    ResponseEntity<?> bad(Exception e) {return ResponseEntity.badRequest().body(Map.of("ok",false,"error","validation","message",e.getMessage()==null?"Invalid data":e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> service(Exception e) {
      return ResponseEntity.status(503).body(Map.of("ok",false,"error","upstream_unavailable","message",e.getMessage()==null?"Service unavailable":e.getMessage()));
    }
  }
}
