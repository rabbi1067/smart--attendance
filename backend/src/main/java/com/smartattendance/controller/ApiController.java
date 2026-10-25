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

@RestController
@RequestMapping("/api")
public class ApiController {
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

    // If Google Sheets is configured, attempt to read from Apps Script
    if (s.hasGoogleSheets()) {
      try {
        JsonNode node = script.get(s, Map.of("date", today, "cls", cls, "force", "1"));
        if (node != null && node.path("ok").asBoolean(false) && node.has("rows") && node.path("rows").isArray()) {
          return node;
        }
      } catch (Exception ignored) {
        // Fall back seamlessly to local database
      }
    }

    // Standalone database mode (MongoDB / Memory)
    return Map.of(
        "ok", true,
        "sheet", cls.isBlank() ? today : (today + " - " + cls),
        "rows", attendanceStorage.getPublicRows(today, cls)
    );
  }

  public record Attendance(
      @NotBlank @Size(min = 2, max = 80) String name,
      @NotBlank @Pattern(regexp = "[0-9]{13}") String id,
      @Size(max = 100) @Email String email
  ) {}

  @PostMapping("/public/attendance")
  public Object submit(@Valid @RequestBody Attendance data) {
    AdminSettings s = settings.publicSettings();
    String cls = s.schedule.activeClass(ZonedDateTime.now(DHAKA));
    if (cls == null) throw new ClosedException();

    String today = LocalDate.now(DHAKA).toString();
    String timeStr = LocalTime.now(DHAKA).format(TIME_FMT);
    String recipient = data.email() == null ? "" : data.email().trim();

    // 1. Primary Save to Database (MongoDB / Local) with duplicate protection
    try {
      attendanceStorage.save(today, cls, data.name().trim(), data.id(), recipient, timeStr);
    } catch (AttendanceStorageService.DuplicateAttendanceException e) {
      ObjectNode dup = mapper.createObjectNode();
      dup.put("ok", false);
      dup.put("error", "dup");
      dup.put("message", e.getMessage());
      return dup;
    }

    // 2. If Google Sheets is configured, mirror to Google Apps Script
    JsonNode recorded = null;
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
      } catch (Exception ignored) {
        // Attendance is safely stored in local database
      }
    }

    // 3. Email receipt if configured
    String status = "disabled";
    try {
      status = notifications.receiptStatus(s, recipient, data.name().trim(), data.id(), cls, recorded);
    } catch (Exception e) {
      status = "error";
    }

    ObjectNode result = mapper.createObjectNode();
    result.put("ok", true);
    result.put("emailStatus", status);
    return result;
  }

  public record Login(@NotBlank String email, @NotBlank String password) {}

  @PostMapping("/admin/auth/login")
  public Object login(@Valid @RequestBody Login input, HttpServletRequest request) {
    String email = input.email() == null ? "" : input.email().trim().toLowerCase(Locale.ROOT);
    String password = input.password() == null ? "" : input.password().trim();
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

  @GetMapping("/admin/sheets")
  public Object sheets(Authentication principal) {
    AdminSettings s = settings.forUser(principal.getName());
    if (s.hasGoogleSheets()) {
      try {
        JsonNode sheetsNode = script.get(s, Map.of("action", "adminSheets"));
        if (sheetsNode != null && sheetsNode.path("ok").asBoolean(false) && sheetsNode.has("sheets")) {
          return sheetsNode;
        }
      } catch (Exception ignored) {
        // Fall back to local database
      }
    }
    return attendanceStorage.getAdminSheets();
  }

  @GetMapping("/admin/sheets/rows")
  public Object adminRows(Authentication principal, @RequestParam String sheet) {
    if (sheet.isBlank() || sheet.length() > 100) throw new IllegalArgumentException("Invalid sheet name");
    AdminSettings s = settings.forUser(principal.getName());
    if (s.hasGoogleSheets()) {
      try {
        JsonNode rowsNode = script.get(s, Map.of("action", "adminRows", "sheet", sheet));
        if (rowsNode != null && rowsNode.path("ok").asBoolean(false) && rowsNode.has("rows")) {
          return rowsNode;
        }
      } catch (Exception ignored) {
        // Fall back to local database
      }
    }
    return attendanceStorage.getAdminRows(sheet);
  }

  public record RowChange(String sheet, Integer rowIndex, List<String> values) {}

  @PostMapping("/admin/sheets/rows/add")
  public Object add(Authentication principal, @RequestBody RowChange data) {
    attendanceStorage.addRow(data.sheet(), data.values());
    AdminSettings s = settings.forUser(principal.getName());
    if (s.hasGoogleSheets()) {
      try { mutate(principal, "adminAdd", data); } catch (Exception ignored) {}
    }
    return Map.of("ok", true);
  }

  @PostMapping("/admin/sheets/rows/edit")
  public Object edit(Authentication principal, @RequestBody RowChange data) {
    attendanceStorage.editRow(data.sheet(), data.rowIndex() == null ? 0 : data.rowIndex(), data.values());
    AdminSettings s = settings.forUser(principal.getName());
    if (s.hasGoogleSheets()) {
      try { mutate(principal, "adminEdit", data); } catch (Exception ignored) {}
    }
    return Map.of("ok", true);
  }

  @PostMapping("/admin/sheets/rows/delete")
  public Object delete(Authentication principal, @RequestBody RowChange data) {
    attendanceStorage.deleteRow(data.sheet(), data.rowIndex() == null ? 0 : data.rowIndex());
    AdminSettings s = settings.forUser(principal.getName());
    if (s.hasGoogleSheets()) {
      try { mutate(principal, "adminDelete", data); } catch (Exception ignored) {}
    }
    return Map.of("ok", true);
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

  private JsonNode mutate(Authentication p, String action, RowChange d) {
    if (d.sheet() == null || d.sheet().isBlank() || d.sheet().length() > 100) throw new IllegalArgumentException("Invalid sheet");
    if (!action.equals("adminAdd") && (d.rowIndex() == null || d.rowIndex() < 2)) throw new IllegalArgumentException("Invalid row number");
    if (!action.equals("adminDelete") && (d.values() == null || d.values().size() > 12)) throw new IllegalArgumentException("Invalid row values");
    return script.post(settings.forUser(p.getName()), Map.of(
        "action", action,
        "sheet", d.sheet(),
        "rowIndex", d.rowIndex() == null ? 0 : d.rowIndex(),
        "values", d.values() == null ? List.of() : d.values()
    ));
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
          "message", e.getMessage() == null ? "Internal server error" : e.getMessage()
      ));
    }
  }
}
