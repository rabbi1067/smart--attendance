package com.smartattendance.model;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScheduleConfigTest {
  private ZonedDateTime dhaka(String local) {return ZonedDateTime.of(LocalDateTime.parse(local),ZoneId.of("Asia/Dhaka"));}
  @Test void defaultAlwaysOpen() {assertEquals("",ScheduleConfig.defaults().activeClass(dhaka("2026-10-24T02:00:00")));}
  @Test void singleWindowIsEnforced() {
    var s = new ScheduleConfig(true,"10:00 PM","11:00 PM",false,List.of());
    s.validate();
    assertNull(s.activeClass(dhaka("2026-10-24T21:59:59")));
    assertEquals("",s.activeClass(dhaka("2026-10-24T22:01:00")));
    assertNull(s.activeClass(dhaka("2026-10-24T23:00:00")));
  }
  @Test void classNameAndBoundaries() {
    var s = new ScheduleConfig(true,"10:00 PM","11:00 PM",true,List.of(new ScheduleConfig.ClassTime("Math Class","07:00 PM","08:00 PM")));
    s.validate();
    assertEquals("Math Class",s.activeClass(dhaka("2026-10-24T19:15:00")));
    assertNull(s.activeClass(dhaka("2026-10-24T20:00:00")));
  }
  @Test void overlappingClassTimesAreRejected() {
    var s = new ScheduleConfig(true,"10:00 PM","11:00 PM",true,List.of(
      new ScheduleConfig.ClassTime("Math","07:00 PM","08:00 PM"),
      new ScheduleConfig.ClassTime("English","07:30 PM","09:00 PM")));
    assertThrows(IllegalArgumentException.class,s::validate);
  }
  @Test void crossingMidnightIsRejectedToMatchClientTimer() {
    var s = new ScheduleConfig(true,"11:30 PM","01:00 AM",false,List.of());
    assertThrows(IllegalArgumentException.class,s::validate);
  }
}
