package com.smartattendance.service;

import com.smartattendance.model.AttendanceEntry;
import com.smartattendance.repo.AttendanceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** MongoDB is the only authoritative source. A failed write is never reported as success. */
@Service
public class AttendanceStorageService {
  private final AttendanceRepository repo;
  public AttendanceStorageService(AttendanceRepository repo) { this.repo = repo; }

  public static class DuplicateAttendanceException extends RuntimeException {
    public DuplicateAttendanceException(String msg) { super(msg); }
  }

  private static String tab(String date, String cls) {
    return cls == null || cls.isBlank() ? date : date + " - " + cls;
  }
  private static String str(String s) { return s == null ? "" : s.trim(); }
  private static String requiredDate(String value) {
    String clean = str(value);
    try { LocalDate.parse(clean); } catch (Exception e) { throw new IllegalArgumentException("Date must be in YYYY-MM-DD format"); }
    return clean;
  }
  private static void requireStudent(String name, String id, String email) {
    if (str(name).length() < 2 || str(name).length() > 80) throw new IllegalArgumentException("Student name must contain 2–80 characters");
    if (!str(id).matches("[0-9]{13}")) throw new IllegalArgumentException("Student ID must be exactly 13 digits");
    if (!str(email).isEmpty() && (str(email).length() > 100 || !str(email).matches("(?i)^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")))
      throw new IllegalArgumentException("Invalid student email address");
  }
  private static void requireAttendance(String attendance, String time) {
    String status = str(attendance), clock = str(time);
    if (status.length() > 30 || status.contains("\r") || status.contains("\n"))
      throw new IllegalArgumentException("Invalid attendance status");
    if (clock.length() > 30 || clock.contains("\r") || clock.contains("\n"))
      throw new IllegalArgumentException("Invalid attendance time");
  }
  private boolean duplicate(String date, String cls, String studentId, String exemptId) {
    // This check provides a friendly error; the compound unique MongoDB index protects concurrent requests.
    return repo.findByDateAndClassNameAndStudentId(date, cls, studentId)
        .stream().anyMatch(e -> !Objects.equals(e.id, exemptId));
  }
  private AttendanceEntry persisted(AttendanceEntry entry) {
    try { return repo.save(entry); }
    catch (DuplicateKeyException e) { throw new DuplicateAttendanceException("This student already has attendance for this class and date."); }
  }

  public AttendanceEntry save(String date, String className, String name, String studentId, String email, String time) {
    date = requiredDate(date); className = str(className); studentId = str(studentId);
    requireStudent(name, studentId, email);
    if (duplicate(date, className, studentId, null)) throw new DuplicateAttendanceException("This ID has already been marked for this class.");
    int sl = (int)repo.countByTabName(tab(date, className)) + 1;
    AttendanceEntry entry = new AttendanceEntry(UUID.randomUUID().toString(), date, className, tab(date, className), sl,
        str(name), studentId, str(email), "1", str(time));
    return persisted(entry);
  }

  public List<Map<String,Object>> getPublicRows(String date, String className) {
    String cleanDate = requiredDate(date), cls = str(className);
    List<Map<String,Object>> rows = new ArrayList<>();
    // Stored SL values can repeat after a deletion or concurrent saves; order by immutable creation time.
    for (AttendanceEntry e : repo.findByDateAndClassNameOrderBySlAsc(cleanDate, cls).stream()
        .sorted(chronological()).toList()) {
      String id = str(e.studentId);
      String masked = id.length() >= 8 ? id.substring(0,4) + "•••••" + id.substring(id.length()-4) : "••••";
      rows.add(Map.of("name", str(e.name), "idm", masked, "time", str(e.time)));
    }
    return rows;
  }

  public long mongoCount() { return repo.count(); }

  private List<AttendanceEntry> all() { return repo.findAll(); }
  public Map<String,Object> getAdminSheets() {
    List<AttendanceEntry> entries = all();
    Map<String,Long> counts = new TreeMap<>(Comparator.reverseOrder());
    for (AttendanceEntry e : entries) counts.merge(str(e.tabName).isBlank() ? tab(str(e.date), str(e.className)) : e.tabName, 1L, Long::sum);
    List<Map<String,Object>> sheets = new ArrayList<>();
    sheets.add(Map.of("name", "All Records", "count", entries.size()));
    counts.forEach((name, count) -> sheets.add(Map.of("name", name, "count", count)));
    return Map.of("ok",true,"sheets",sheets);
  }
  private static Comparator<AttendanceEntry> chronological() {
    return Comparator.comparing((AttendanceEntry e) -> e.createdAt == null ? Instant.EPOCH : e.createdAt)
        .thenComparing(e -> str(e.id));
  }
  private static Comparator<AttendanceEntry> latest() { return chronological().reversed(); }
  private List<AttendanceEntry> forSheet(String sheet) {
    String name = str(sheet);
    List<AttendanceEntry> entries = all();
    if (name.isEmpty() || name.equalsIgnoreCase("All Records")) return entries.stream().sorted(latest()).toList();
    return entries.stream().filter(e -> name.equalsIgnoreCase(str(e.tabName))).sorted(chronological()).toList();
  }
  public Map<String,Object> getAdminRows(String sheetName) {
    List<AttendanceEntry> entries = forSheet(sheetName);
    List<Map<String,Object>> rows = new ArrayList<>();
    for (int i=0;i<entries.size();i++) {
      AttendanceEntry e = entries.get(i);
      rows.add(Map.of("rowIndex",i+2,"recordId",e.id,"values",List.of(
          String.valueOf(i+1), str(e.date), str(e.name), str(e.studentId),str(e.email),str(e.attendance),str(e.time))));
    }
    return Map.of("ok",true,"sheet",str(sheetName).isEmpty()?"All Records":sheetName,
        "headers",List.of("SL","Date","Name","ID","Email","Attendance","Local Time"),"rows",rows);
  }

  private AttendanceEntry locate(String sheet, String recordId) {
    // A table row number is mutable and may belong to a different student after a concurrent edit.
    // Require the database document ID for every edit/delete; never fall back to rowIndex.
    if (recordId == null || recordId.isBlank())
      throw new IllegalArgumentException("Missing record ID. Refresh records and retry.");
    AttendanceEntry entry = repo.findById(recordId)
        .orElseThrow(() -> new NoSuchElementException("Record no longer exists. Refresh and retry."));
    if (!str(sheet).equalsIgnoreCase("All Records") && !str(sheet).isBlank()
        && !str(sheet).equalsIgnoreCase(str(entry.tabName)))
      throw new IllegalArgumentException("Record moved to another tab. Refresh the table.");
    return entry;
  }

  public AttendanceEntry addRow(String sheet, List<String> values) {
    if (values == null || values.size() < 7) throw new IllegalArgumentException("All attendance fields are required");
    String date = requiredDate(values.get(1)), name = str(values.get(2)), id = str(values.get(3)), email = str(values.get(4));
    requireStudent(name,id,email);
    requireAttendance(values.get(5), values.get(6));
    String className = "";
    String requestedTab = str(sheet);
    if (!requestedTab.isBlank() && !requestedTab.equalsIgnoreCase("All Records")) {
      if (requestedTab.equals(date)) className = "";
      else if (requestedTab.startsWith(date + " - ")) className = requestedTab.substring((date + " - ").length()).trim();
      else throw new IllegalArgumentException("Date does not match selected tab. Use All Records or choose a matching date.");
    }
    if (duplicate(date,className,id,null)) throw new DuplicateAttendanceException("Student ID already exists in this date/class.");
    AttendanceEntry entry = new AttendanceEntry(UUID.randomUUID().toString(),date,className,tab(date,className),
        (int)repo.countByTabName(tab(date,className))+1,name,id,email,str(values.get(5)),str(values.get(6)));
    return persisted(entry);
  }
  public AttendanceEntry editRow(String sheet, String recordId, Integer rowIndex, List<String> values) {
    if (values == null || values.size() < 7) throw new IllegalArgumentException("All attendance fields are required");
    AttendanceEntry e = locate(sheet,recordId);
    String date=requiredDate(values.get(1)), name=str(values.get(2)), id=str(values.get(3)), email=str(values.get(4));
    requireStudent(name,id,email);
    requireAttendance(values.get(5),values.get(6));
    if (duplicate(date,str(e.className),id,e.id)) throw new DuplicateAttendanceException("Student ID already exists in this date/class.");
    e.date=date; e.tabName=tab(date,e.className); e.name=name; e.studentId=id;
    e.email=email; e.attendance=str(values.get(5)); e.time=str(values.get(6));
    return persisted(e);
  }
  public void deleteRow(String sheet, String recordId, Integer rowIndex) {
    AttendanceEntry e = locate(sheet,recordId);
    repo.deleteById(e.id);
  }

  public String exportCsv(String type, String filter) {
    String t = str(type).toLowerCase(Locale.ROOT), f = str(filter);
    List<AttendanceEntry> entries = all().stream().filter(e -> switch(t) {
      case "date" -> f.equalsIgnoreCase(str(e.date));
      case "class" -> f.equalsIgnoreCase(displayClass(e));
      case "sheet" -> f.isBlank() || "All Records".equalsIgnoreCase(f) || f.equalsIgnoreCase(str(e.tabName));
      case "combined" -> {
        // Composite "date|class" filter — class names never contain '|' itself.
        // Blank classNames display as "General" in the CSV, so filter with the same label.
        int sep = f.indexOf('|');
        yield sep > 0 && f.equalsIgnoreCase(str(e.date) + "|" + displayClass(e));
      }
      default -> true;
    }).sorted(latest()).collect(Collectors.toList());
    StringBuilder csv = new StringBuilder("\uFEFFSL,Date,Class,Name,Student ID,Email,Attendance,Time\r\n");
    for (int i=0;i<entries.size();i++) {
      AttendanceEntry e = entries.get(i);
      // Date, Student ID, and Time are wrapped as ="…" text-formulas so Excel shows the
      // full value instead of ### (auto date/number conversion) or 2.02E+12 (long IDs).
      csv.append(csvCell(String.valueOf(i+1))).append(',').append(csvTextCell(e.date)).append(',')
          .append(csvCell(str(e.className).isBlank()?"General":e.className)).append(',')
          .append(csvCell(e.name)).append(',').append(csvTextCell(e.studentId)).append(',')
          .append(csvCell(e.email)).append(',').append(csvCell(e.attendance)).append(',')
          .append(csvTextCell(e.time)).append("\r\n");
    }
    return csv.toString();
  }
  private static String displayClass(AttendanceEntry e) {
    String c = str(e.className);
    return c.isBlank() ? "General" : c;
  }
  // Excel treats ="…" cells as text results — prevents date shifting and scientific notation.
  private static String csvTextCell(String value) {
    String s = str(value).replace("\"","").replace(","," ").replaceAll("[\\r\\n\\t]"," ");
    return "=\"" + s + "\"";
  }
  private static String csvCell(String value) {
    String s = str(value);
    if (s.matches("(?s)^[=+@\\-\\t\\r].*")) s = "'" + s;
    return "\"" + s.replace("\"","\"\"") + "\"";
  }
}
