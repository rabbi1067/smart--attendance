package com.smartattendance.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.Instant;

@Document(collection = "attendance_records")
public class AttendanceEntry {
  @Id
  public String id;

  @Indexed
  public String date = "";

  @Indexed
  public String className = "";

  @Indexed
  public String tabName = "";

  public int sl = 1;
  public String name = "";

  @Indexed
  public String studentId = "";

  public String email = "";
  public String attendance = "1";
  public String time = "";
  public Instant createdAt = Instant.now();

  public AttendanceEntry() {}

  public AttendanceEntry(String id, String date, String className, String tabName, int sl,
                         String name, String studentId, String email, String attendance, String time) {
    this.id = id;
    this.date = date == null ? "" : date.trim();
    this.className = className == null ? "" : className.trim();
    this.tabName = tabName == null ? "" : tabName.trim();
    this.sl = sl;
    this.name = name == null ? "" : name.trim();
    this.studentId = studentId == null ? "" : studentId.trim();
    this.email = email == null ? "" : email.trim();
    this.attendance = (attendance == null || attendance.isBlank()) ? "1" : attendance.trim();
    this.time = time == null ? "" : time.trim();
    this.createdAt = Instant.now();
  }
}
