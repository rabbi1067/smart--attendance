package com.smartattendance.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api")
public class ApiController {
  private static final Logger log = LoggerFactory.getLogger(ApiController.class);
  private final SettingsService settings;
  private final AppsScriptClient script;
  private final AdminAuthService auth;
  private final TokenService tokens;
  private final NotificationService notifications;
  private final AttendanceStorageService attendanceStorage;
  private final ObjectMapper mapper;
  private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
  private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH);

  public ApiController(SettingsService settings, AppsScriptClient script, AdminAuthService auth,
                       TokenService tokens, NotificationService notifications,
                       AttendanceStorageService attendanceStorage, ObjectMapper mapper) {
    this.settings = settings;
    this.script = script;
    this.auth = auth;
    this.tokens = tokens;
    this.notifications = notifications;
    this.attendanceStorage = attendanceStorage;
    this.mapper = mapper;
  }

  @GetMapping("/public/config")
  public Object publicConfig() {
    return settings.publicConfig();
  }

  @GetMapping("/public/rows")
  public Object rows(@RequestParam(defaultValue = "") String cls) {
    if (cls.length() > 50) throw new IllegalArgumentException("Invalid class");
    AdminSettings s = settings.publicSettings();
    String today = LocalDate.now(DHAKA).toString();

    // Public records are served from the same MongoDB source as the administrator dashboard.
    // Sheets is an optional outbound mirror; a stale external sheet never replaces Mongo records.
    return Map.of(
        "ok", true,
        "sheet", cls.isBlank() ? today : (today + " - " + cls),
        "rows", attendanceStorage.getPublicRows(today, cls)
    );
  }

  @GetMapping("/public/db-status")
  public Object dbStatus() {
    Map<String, Object> res = new LinkedHashMap<>();
    try {
      long c = attendanceStorage.mongoCount();
      res.put("ok", true);
      res.put("connected", true);
      res.put("mongoCount", c);
    } catch (Exception e) {
      res.put("ok", false);
      res.put("connected", false);
      res.put("error", "MongoDB is unavailable; verify Atlas credentials and Network Access.");
    }
    return res;
  }

  public record Attendance(
      @NotBlank @Size(min = 2, max = 80) String name,
      @NotBlank @Pattern(regexp = "[0-9]{13}") String id,
      @Size(max = 100) @Email String email
  ) {}

  @PostMapping("/public/attendance")
  public Object submit(@Valid @RequestBody Attendance data) {
    AdminSettings s = settings.publicSettings();
    String cls = (s.schedule == null ? ScheduleConfig.defaults() : s.schedule).activeClass(ZonedDateTime.now(DHAKA));
    if (cls == null) throw new ClosedException();

    String today = LocalDate.now(DHAKA).toString();
    String timeStr = LocalTime.now(DHAKA).format(TIME_FMT);
    String recipient = data.email() == null ? "" : data.email().trim();

    // 1. Primary save: MongoDB commit or failure with duplicate protection
    attendanceStorage.save(today, cls, data.name().trim(), data.id(), recipient, timeStr);

    // 2. If Google Sheets is configured, mirror to Google Apps Script
    JsonNode recorded = null;
    String mirrorStatus = s.hasGoogleSheets() ? "failed" : "not_configured";
    if (s.hasGoogleSheets()) {
      try {
        recorded = script.post(s, Map.of(
            "name", data.name().trim(),
            "id", data.id(),
            "email", recipient,
            "cls", cls,
            "mailEnabled", notifications.usingScript(s),
            "senderName", notifications.safeSender(s)
        ));
        if (recorded != null && recorded.path("ok").asBoolean(false)) {
          mirrorStatus = "synced";
        } else {
          // An upstream JSON error is not a successful Google Sheets write.
          log.warn("Apps Script mirror rejected an attendance write; MongoDB remains authoritative");
        }
      } catch (Exception e) {
        // The MongoDB commit already succeeded; never discard the attendance record.
        log.warn("Google Sheets mirror unavailable after MongoDB commit: {}", e.getClass().getSimpleName());
      }
    }

    // 3. Never retry a potentially completed mirror POST synchronously:
    //    doing so can create duplicate emails and exceed the public request timeout.
    String status = "disabled";
    try {
      status = notifications.receiptStatus(s, recipient, data.name().trim(), data.id(), cls, recorded);
    } catch (Exception e) {
      log.warn("Email receipt process failed after MongoDB commit: {}", e.getClass().getSimpleName());
      status = "failed";
    }

    ObjectNode result = mapper.createObjectNode();
    result.put("ok", true);
    result.put("emailStatus", status);
    result.put("mirrorStatus", mirrorStatus);
    return result;
  }

  public record Login(@NotBlank String email, @NotBlank String password) {}

  @PostMapping("/admin/auth/login")
  public Object login(@Valid @RequestBody Login input, HttpServletRequest request) {
    String email = input.email() == null ? "" : input.email().trim().toLowerCase(Locale.ROOT);
    String password = input.password() == null ? "" : input.password();
    if (!auth.login(email, password, request.getRemoteAddr())) throw new InvalidLoginException();
    return Map.of("ok", true, "token", tokens.issue(email), "email", email, "expiresInSeconds", 14400);
  }

  @GetMapping("/admin/config")
  public Object getAdminConfig(Authentication principal) {
    return settings.adminConfig(principal.getName());
  }

  @PutMapping("/admin/config")
  public Object saveAdminConfig(Authentication principal, @RequestBody SettingsService.ConfigUpdate update) {
    settings.save(principal.getName(), update);
    return settings.adminConfig(principal.getName());
  }

  @PostMapping("/admin/notifications/test")
  public Object sendTest(Authentication principal) {
    return notifications.test(settings.forUser(principal.getName()), principal.getName());
  }

  // The administrator dashboard always reads MongoDB; Google Sheets is an optional external mirror.
  @GetMapping("/admin/script/health")
  public Object scriptHealth(Authentication principal) {
    AdminSettings config=settings.forUser(principal.getName());
    if (!config.hasGoogleSheets()) throw new IllegalStateException("Save the Apps Script URL and gateway secret before testing the connection.");
    // POST keeps gateway secrets out of query strings, proxy access logs and browser history.
    JsonNode response=script.post(config, Map.of("action","adminSheets"));
    if (response==null || !response.path("ok").asBoolean(false)) {
      throw new IllegalStateException("Google Apps Script rejected the connection. Check Web App deployment and GATEWAY_SECRET.");
    }
    return Map.of("ok",true,"sheets",response.path("sheets"));
  }

  @GetMapping("/admin/sheets")
  public Object sheets(Authentication principal) {
    return attendanceStorage.getAdminSheets();
  }

  @GetMapping("/admin/sheets/rows")
  public Object adminRows(Authentication principal, @RequestParam String sheet) {
    if (sheet.isBlank() || sheet.length() > 100) throw new IllegalArgumentException("Invalid sheet name");
    return attendanceStorage.getAdminRows(sheet);
  }

  public record RowChange(String sheet, String recordId, Integer rowIndex, List<String> values) {}

  @PostMapping("/admin/sheets/rows/add")
  public Object add(Authentication principal, @RequestBody RowChange data) {
    if (data == null) throw new IllegalArgumentException("Record details are required");
    var saved = attendanceStorage.addRow(data.sheet(), data.values());
    return Map.of("ok", true, "recordId", saved.id, "message", "Record saved to MongoDB");
  }

  @PostMapping("/admin/sheets/rows/edit")
  public Object edit(Authentication principal, @RequestBody RowChange data) {
    if (data == null) throw new IllegalArgumentException("Record details are required");
    var saved = attendanceStorage.editRow(data.sheet(), data.recordId(), data.rowIndex(), data.values());
    return Map.of("ok", true, "recordId", saved.id, "message", "Record updated in MongoDB");
  }

  @PostMapping("/admin/sheets/rows/delete")
  public Object delete(Authentication principal, @RequestBody RowChange data) {
    if (data == null) throw new IllegalArgumentException("Record details are required");
    attendanceStorage.deleteRow(data.sheet(), data.recordId(), data.rowIndex());
    return Map.of("ok", true, "message", "Record deleted from MongoDB");
  }

  @GetMapping(value = "/admin/sheets/export/csv")
  public ResponseEntity<byte[]> exportCsv(
      Authentication principal,
      @RequestParam(defaultValue = "all") String type,
      @RequestParam(required = false) String filter,
      @RequestParam(required = false) String sheet,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String cls) {

    String actualFilter = filter;
    String actualType = type;
    if (sheet != null && !sheet.isBlank()) {
      actualType = "sheet";
      actualFilter = sheet;
    } else if (date != null && !date.isBlank()) {
      actualType = "date";
      actualFilter = date;
    } else if (cls != null && !cls.isBlank()) {
      actualType = "class";
      actualFilter = cls;
    }

    String csvContent = attendanceStorage.exportCsv(actualType, actualFilter);
    String filterTag = (actualFilter == null || actualFilter.isBlank()) ? "all-records" : actualFilter.replaceAll("[^a-zA-Z0-9._-]", "_");
    String filename = "attendance-" + filterTag + ".csv";

    byte[] bytes = csvContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
        .contentLength(bytes.length)
        .body(bytes);
  }

  static class ClosedException extends RuntimeException {}
  static class InvalidLoginException extends RuntimeException {}

  @RestControllerAdvice
  public static class Errors {
    @ExceptionHandler(ClosedException.class)
    ResponseEntity<?> closed() {
      return ResponseEntity.status(403).body(Map.of("ok", false, "error", "closed"));
    }

    @ExceptionHandler(InvalidLoginException.class)
    ResponseEntity<?> invalid() {
      return ResponseEntity.status(401).body(Map.of(
          "ok", false,
          "error", "account_not_found_or_wrong_password",
          "message", "Account not found or invalid credentials"
      ));
    }

    @ExceptionHandler(AdminAuthService.TooManyAttemptsException.class)
    ResponseEntity<?> rate() {
      return ResponseEntity.status(429).body(Map.of("ok", false, "error", "too_many_attempts"));
    }

    @ExceptionHandler(AttendanceStorageService.DuplicateAttendanceException.class)
    ResponseEntity<?> duplicate(Exception e) {
      return ResponseEntity.status(409).body(Map.of("ok",false,"error","dup","message",e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<?> missing(Exception e) {
      return ResponseEntity.status(404).body(Map.of("ok",false,"error","not_found","message",e.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<?> database() {
      return ResponseEntity.status(503).body(Map.of("ok",false,"error","database_unavailable","message",
          "MongoDB operation failed. Nothing was saved. Check database access and retry."));
    }

    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.bind.MethodArgumentNotValidException.class})
    ResponseEntity<?> bad(Exception e) {
      return ResponseEntity.badRequest().body(Map.of(
          "ok", false,
          "error", "validation",
          "message", e.getMessage() == null ? "Invalid data" : e.getMessage()
      ));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> service(Exception e) {
      return ResponseEntity.status(503).body(Map.of(
          "ok", false,
          "error", "upstream_unavailable",
          "message", e.getMessage() == null ? "Service unavailable" : e.getMessage()
      ));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> general(Exception e) {
      return ResponseEntity.status(500).body(Map.of(
          "ok", false,
          "error", "server_error",
          "message", "An unexpected server error occurred. Check Render logs."
      ));
    }
  }
}
