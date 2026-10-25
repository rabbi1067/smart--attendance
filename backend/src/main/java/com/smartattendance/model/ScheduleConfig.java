package com.smartattendance.model;

import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

public record ScheduleConfig(boolean ENABLED, String START_TIME, String END_TIME, boolean USE_CLASSES, List<ClassTime> CLASSES) {
  private static final DateTimeFormatter FORMAT = new DateTimeFormatterBuilder()
      .parseCaseInsensitive().appendPattern("h:mm a").toFormatter(Locale.US);
  public record ClassTime(String name, String start, String end) {}
  public static ScheduleConfig defaults() {
    return new ScheduleConfig(false, "10:00 PM", "11:00 PM", false, List.of(
        new ClassTime("Bangla Class", "04:30 PM", "05:30 PM"),
        new ClassTime("English Class", "07:00 PM", "08:00 PM"),
        new ClassTime("Math Class", "10:00 PM", "11:00 PM")));
  }
  public static LocalTime parse(String t) {
    try { return LocalTime.parse(t, FORMAT); }
    catch (Exception e) { throw new IllegalArgumentException("Times must use h:mm AM/PM, for example 10:00 PM"); }
  }
  public void validate() {
    parse(START_TIME); parse(END_TIME);
    if (!parse(START_TIME).isBefore(parse(END_TIME))) throw new IllegalArgumentException("End time must be later on the same day");
    if (CLASSES == null || CLASSES.size() > 25) throw new IllegalArgumentException("Maximum 25 classes");
    Set<String> names = new HashSet<>();
    for (ClassTime c : CLASSES) {
      if (c == null || c.name() == null || !Pattern.matches("[\\p{L}\\p{N} ._()&-]{1,50}", c.name().trim()))
        throw new IllegalArgumentException("Invalid class name");
      if (!names.add(c.name().trim().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate class names");
      if (!parse(c.start()).isBefore(parse(c.end()))) throw new IllegalArgumentException("Class end must be later on the same day");
    }
    if (ENABLED && USE_CLASSES && CLASSES.isEmpty()) throw new IllegalArgumentException("Please add at least one class");
    if (USE_CLASSES) {
      for (int i = 0; i < CLASSES.size(); i++) for (int j = i + 1; j < CLASSES.size(); j++) {
        ClassTime a = CLASSES.get(i), b = CLASSES.get(j);
        if (parse(a.start()).isBefore(parse(b.end())) && parse(b.start()).isBefore(parse(a.end())))
          throw new IllegalArgumentException("Class times must not overlap: " + a.name() + " / " + b.name());
      }
    }
  }
  // Times can cross midnight. The matching label is selected exclusively by the server.
  private static boolean inWindow(LocalTime now, LocalTime start, LocalTime end) {
    if (start.isBefore(end)) return !now.isBefore(start) && now.isBefore(end);
    return !now.isBefore(start) || now.isBefore(end);
  }
  public String activeClass(ZonedDateTime now) {
    if (!ENABLED) return "";
    var t = now.toLocalTime();
    if (!USE_CLASSES) return inWindow(t, parse(START_TIME), parse(END_TIME)) ? "" : null;
    for (ClassTime c : CLASSES) if (inWindow(t, parse(c.start()), parse(c.end()))) return c.name();
    return null;
  }
}
