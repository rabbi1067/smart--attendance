package com.smartattendance.service;

import com.smartattendance.model.AttendanceEntry;
import com.smartattendance.repo.AttendanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
public class AttendanceStorageService {
  private static final Logger log = LoggerFactory.getLogger(AttendanceStorageService.class);
  private final AttendanceRepository repo;
  private final CopyOnWriteArrayList<AttendanceEntry> memoryRecords = new CopyOnWriteArrayList<>();
  private final Set<String> submittedTodayKeys = ConcurrentHashMap.newKeySet();

  public AttendanceStorageService(AttendanceRepository repo) {
    this.repo = repo;
    // Initial warmup: attempt to load from MongoDB into memory
    try {
      List<AttendanceEntry> existing = repo.findAll();
      memoryRecords.addAll(existing);
      for (AttendanceEntry e : existing) {
        String key = makeKey(e.date, e.className, e.studentId);
        submittedTodayKeys.add(key);
      }
      log.info("MongoDB warmup successful: loaded {} attendance entries.", existing.size());
    } catch (Exception e) {
      log.error("MongoDB warmup connection failed: {}. Operating in resilient memory mode.", e.getMessage());
    }
  }

  private String makeKey(String date, String className, String studentId) {
    String cleanDate = date == null ? "" : date.trim();
    String cleanCls = className == null ? "" : className.trim();
    String cleanId = studentId == null ? "" : studentId.trim();
    return cleanDate + ":" + (cleanCls.isEmpty() ? "default" : cleanCls) + ":" + cleanId;
  }

  public static class DuplicateAttendanceException extends RuntimeException {
    public DuplicateAttendanceException(String message) { super(message); }
  }

  public synchronized AttendanceEntry save(String date, String className, String name, String studentId, String email, String time) {
    String cleanDate = date == null ? "" : date.trim();
    String cleanCls = className == null ? "" : className.trim();
    String cleanId = studentId == null ? "" : studentId.trim();
    String key = makeKey(cleanDate, cleanCls, cleanId);

    // Duplicate check in memory
    if (submittedTodayKeys.contains(key)) {
      throw new DuplicateAttendanceException("This ID has already been marked for this class.");
    }

    // Duplicate check in MongoDB
    try {
      boolean exists = cleanCls.isEmpty()
          ? repo.existsByDateAndStudentId(cleanDate, cleanId)
          : repo.existsByDateAndClassNameAndStudentId(cleanDate, cleanCls, cleanId);
      if (exists) {
        submittedTodayKeys.add(key);
        throw new DuplicateAttendanceException("This ID has already been marked for this class.");
      }
    } catch (DuplicateAttendanceException e) {
      throw e;
    } catch (Exception ignored) {}

    String tabName = cleanCls.isEmpty() ? cleanDate : (cleanDate + " - " + cleanCls);
    long existingCount = memoryRecords.stream().filter(r -> tabName.equals(r.tabName)).count();
    int nextSl = (int) existingCount + 1;

    String entryId = UUID.randomUUID().toString();
    AttendanceEntry entry = new AttendanceEntry(
        entryId, cleanDate, cleanCls, tabName, nextSl,
        name == null ? "" : name.trim(),
        cleanId,
        email == null ? "" : email.trim(),
        "1",
        time == null ? "" : time.trim()
    );

    // Persist to MongoDB
    try {
      entry = repo.save(entry);
      log.info("Persisted attendance record to MongoDB successfully: studentId={}, name={}", cleanId, entry.name);
    } catch (Exception e) {
      log.error("Failed to persist attendance record to MongoDB: {}. Check credentials and Network Access.", e.getMessage());
    }

    memoryRecords.add(entry);
    submittedTodayKeys.add(key);
    return entry;
  }

  public List<Map<String, Object>> getPublicRows(String date, String className) {
    String cleanDate = date == null ? "" : date.trim();
    String cleanCls = className == null ? "" : className.trim();
    String tabName = cleanCls.isEmpty() ? cleanDate : (cleanDate + " - " + cleanCls);

    List<AttendanceEntry> list;
    try {
      list = repo.findByTabNameOrderBySlAsc(tabName);
      if (list.isEmpty()) {
        list = memoryRecords.stream()
            .filter(r -> tabName.equalsIgnoreCase(r.tabName) || (cleanDate.equals(r.date) && cleanCls.equalsIgnoreCase(r.className)))
            .sorted(Comparator.comparingInt(a -> a.sl))
            .collect(Collectors.toList());
      }
    } catch (Exception e) {
      list = memoryRecords.stream()
          .filter(r -> tabName.equalsIgnoreCase(r.tabName) || (cleanDate.equals(r.date) && cleanCls.equalsIgnoreCase(r.className)))
          .sorted(Comparator.comparingInt(a -> a.sl))
          .collect(Collectors.toList());
    }

    List<Map<String, Object>> result = new ArrayList<>();
    for (AttendanceEntry e : list) {
      String id = e.studentId == null ? "" : e.studentId;
      String idm = id.length() >= 8 ? id.substring(0, 4) + "•••••" + id.substring(id.length() - 4) : id;
      result.add(Map.of(
          "name", e.name == null ? "" : e.name,
          "id", id,
          "idm", idm,
          "time", e.time == null ? "" : e.time
      ));
    }
    return result;
  }

  public long mongoCount() {
    return repo.count();
  }

  public int getMemoryCount() {
    return memoryRecords.size();
  }

  public Map<String, Object> getAdminSheets() {
    List<AttendanceEntry> all;
    try {
      all = repo.findAll();
      if (all.isEmpty()) all = new ArrayList<>(memoryRecords);
    } catch (Exception e) {
      all = new ArrayList<>(memoryRecords);
    }

    // Map tabName -> count
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (AttendanceEntry e : all) {
      String tab = e.tabName == null || e.tabName.isBlank() ? (e.date == null || e.date.isBlank() ? "General" : e.date) : e.tabName;
      counts.put(tab, counts.getOrDefault(tab, 0) + 1);
    }

    List<Map<String, Object>> sheets = new ArrayList<>();
    // Add "All Records" tab first or at the end
    sheets.add(Map.of("name", "All Records", "count", all.size()));
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      sheets.add(Map.of("name", entry.getKey(), "count", entry.getValue()));
    }

    return Map.of("ok", true, "sheets", sheets);
  }

  public Map<String, Object> getAdminRows(String sheetName) {
    String cleanSheet = sheetName == null ? "" : sheetName.trim();
    List<AttendanceEntry> all;
    try {
      all = repo.findAll();
      if (all.isEmpty()) all = new ArrayList<>(memoryRecords);
    } catch (Exception e) {
      all = new ArrayList<>(memoryRecords);
    }

    List<AttendanceEntry> filtered;
    if ("All Records".equalsIgnoreCase(cleanSheet) || cleanSheet.isEmpty()) {
      filtered = all.stream()
          .sorted((a, b) -> b.createdAt.compareTo(a.createdAt))
          .collect(Collectors.toList());
    } else {
      filtered = all.stream()
          .filter(e -> cleanSheet.equalsIgnoreCase(e.tabName) || cleanSheet.equalsIgnoreCase(e.date) || cleanSheet.equalsIgnoreCase(e.className))
          .sorted(Comparator.comparingInt(a -> a.sl))
          .collect(Collectors.toList());
    }

    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < filtered.size(); i++) {
      AttendanceEntry e = filtered.get(i);
      rows.add(Map.of(
          "rowIndex", i + 2,
          "values", List.of(
              String.valueOf(e.sl > 0 ? e.sl : (i + 1)),
              e.date == null ? "" : e.date,
              e.name == null ? "" : e.name,
              e.studentId == null ? "" : e.studentId,
              e.email == null ? "" : e.email,
              e.attendance == null || e.attendance.isBlank() ? "1" : e.attendance,
              e.time == null ? "" : e.time
          )
      ));
    }

    return Map.of(
        "ok", true,
        "sheet", cleanSheet.isEmpty() ? "All Records" : cleanSheet,
        "headers", List.of("SL", "Date", "Name", "ID", "Email", "Attendance", "Local Time"),
        "rows", rows
    );
  }

  public synchronized void addRow(String sheetName, List<String> values) {
    if (values == null || values.size() < 4) return;
    String date = values.size() > 1 ? values.get(1) : "";
    String name = values.size() > 2 ? values.get(2) : "";
    String id = values.size() > 3 ? values.get(3) : "";
    String email = values.size() > 4 ? values.get(4) : "";
    String att = values.size() > 5 ? values.get(5) : "1";
    String time = values.size() > 6 ? values.get(6) : "";

    String tabName = sheetName == null || sheetName.isBlank() || "All Records".equalsIgnoreCase(sheetName) ? date : sheetName;
    String cls = tabName.contains(" - ") ? tabName.substring(tabName.indexOf(" - ") + 3).trim() : "";

    AttendanceEntry entry = new AttendanceEntry(
        UUID.randomUUID().toString(), date, cls, tabName,
        memoryRecords.size() + 1, name, id, email, att, time
    );
    try { repo.save(entry); } catch (Exception ignored) {}
    memoryRecords.add(entry);
  }

  public synchronized void editRow(String sheetName, int rowIndex, List<String> values) {
    if (values == null || values.size() < 4) return;
    int targetIdx = rowIndex - 2;
    Map<String, Object> adminRows = getAdminRows(sheetName);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) adminRows.get("rows");
    if (targetIdx < 0 || targetIdx >= rows.size()) return;

    @SuppressWarnings("unchecked")
    List<String> oldVals = (List<String>) rows.get(targetIdx).get("values");
    String oldId = oldVals.size() > 3 ? oldVals.get(3) : "";

    // Find in memoryRecords
    for (AttendanceEntry e : memoryRecords) {
      if (oldId.equals(e.studentId)) {
        if (values.size() > 1) e.date = values.get(1);
        if (values.size() > 2) e.name = values.get(2);
        if (values.size() > 3) e.studentId = values.get(3);
        if (values.size() > 4) e.email = values.get(4);
        if (values.size() > 5) e.attendance = values.get(5);
        if (values.size() > 6) e.time = values.get(6);
        try { repo.save(e); } catch (Exception ignored) {}
        break;
      }
    }
  }

  public synchronized void deleteRow(String sheetName, int rowIndex) {
    int targetIdx = rowIndex - 2;
    Map<String, Object> adminRows = getAdminRows(sheetName);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) adminRows.get("rows");
    if (targetIdx < 0 || targetIdx >= rows.size()) return;

    @SuppressWarnings("unchecked")
    List<String> oldVals = (List<String>) rows.get(targetIdx).get("values");
    String targetId = oldVals.size() > 3 ? oldVals.get(3) : "";

    AttendanceEntry toRemove = null;
    for (AttendanceEntry e : memoryRecords) {
      if (targetId.equals(e.studentId)) {
        toRemove = e;
        break;
      }
    }
    if (toRemove != null) {
      memoryRecords.remove(toRemove);
      try { repo.delete(toRemove); } catch (Exception ignored) {}
    }
  }

  /**
   * Generates standard RFC 4180 CSV with UTF-8 BOM (\uFEFF)
   * for perfect rendering in Microsoft Excel and text editors.
   */
  public String exportCsv(String type, String filter) {
    List<AttendanceEntry> all;
    try {
      all = repo.findAll();
      if (all.isEmpty()) all = new ArrayList<>(memoryRecords);
    } catch (Exception e) {
      all = new ArrayList<>(memoryRecords);
    }

    String cleanType = type == null ? "all" : type.trim().toLowerCase();
    String cleanFilter = filter == null ? "" : filter.trim();

    List<AttendanceEntry> filtered;
    if ("date".equals(cleanType) && !cleanFilter.isEmpty()) {
      filtered = all.stream().filter(e -> cleanFilter.equalsIgnoreCase(e.date)).collect(Collectors.toList());
    } else if ("class".equals(cleanType) && !cleanFilter.isEmpty()) {
      filtered = all.stream().filter(e -> cleanFilter.equalsIgnoreCase(e.className)).collect(Collectors.toList());
    } else if ("sheet".equals(cleanType) && !cleanFilter.isEmpty() && !"All Records".equalsIgnoreCase(cleanFilter)) {
      filtered = all.stream().filter(e -> cleanFilter.equalsIgnoreCase(e.tabName) || cleanFilter.equalsIgnoreCase(e.date)).collect(Collectors.toList());
    } else {
      // All records
      filtered = all.stream().sorted((a, b) -> b.createdAt.compareTo(a.createdAt)).collect(Collectors.toList());
    }

    StringBuilder sb = new StringBuilder();
    // UTF-8 BOM so MS Excel renders Bangla and international characters properly
    sb.append('\uFEFF');
    sb.append("SL,Date,Class,Name,Student ID,Email,Attendance,Time\r\n");

    for (int i = 0; i < filtered.size(); i++) {
      AttendanceEntry e = filtered.get(i);
      sb.append(escapeCsv(String.valueOf(i + 1))).append(",");
      sb.append(escapeCsv(e.date)).append(",");
      sb.append(escapeCsv(e.className.isEmpty() ? "General" : e.className)).append(",");
      sb.append(escapeCsv(e.name)).append(",");
      // Force text format for 13-digit ID in Excel using ="1234567890123" or standard text
      sb.append("\"=\"\"").append(e.studentId.replace("\"", "\"\"")).append("\"\"\",");
      sb.append(escapeCsv(e.email)).append(",");
      sb.append(escapeCsv(e.attendance.isBlank() ? "1" : e.attendance)).append(",");
      sb.append(escapeCsv(e.time)).append("\r\n");
    }

    return sb.toString();
  }

  private String escapeCsv(String val) {
    if (val == null) return "\"\"";
    String s = val.trim();
    if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
      return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return "\"" + s + "\"";
  }
}
