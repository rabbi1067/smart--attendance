package com.smartattendance.service;

import com.smartattendance.model.AttendanceEntry;
import com.smartattendance.repo.AttendanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AttendanceStorageServiceTest {
  private static final String STUDENT_ID = "2023200000686";
  @Test void successfulWriteReturnsPersistedDocument() {
    AttendanceRepository repo=mock(AttendanceRepository.class);
    when(repo.findByDateAndClassNameAndStudentId(anyString(),anyString(),anyString())).thenReturn(List.of());
    when(repo.save(any(AttendanceEntry.class))).thenAnswer(call -> call.getArgument(0));
    AttendanceStorageService service=new AttendanceStorageService(repo);
    AttendanceEntry saved=service.save("2026-10-26","","Student Name",STUDENT_ID,"student@example.com","09:00 AM");
    assertNotNull(saved.id);
    assertEquals("2026-10-26",saved.tabName);
    verify(repo).save(any(AttendanceEntry.class));
  }
  @Test void mongodbOutageCannotBeReportedAsSuccessfulAttendance() {
    AttendanceRepository repo=mock(AttendanceRepository.class);
    when(repo.findByDateAndClassNameAndStudentId(anyString(),anyString(),anyString())).thenReturn(List.of());
    when(repo.save(any(AttendanceEntry.class))).thenThrow(new DataAccessResourceFailureException("Database unavailable"));
    assertThrows(DataAccessResourceFailureException.class,()->new AttendanceStorageService(repo)
        .save("2026-10-26","","Student Name",STUDENT_ID,"","09:00 AM"));
  }
  @Test void publicRowsMaskStudentIdsWithoutSendingOriginalToBrowser() {
    AttendanceRepository repo=mock(AttendanceRepository.class);
    when(repo.findByDateAndClassNameOrderBySlAsc("2026-10-26", ""))
      .thenReturn(List.of(entry("record-a", "2026-10-26", STUDENT_ID)));
    var rows=new AttendanceStorageService(repo).getPublicRows("2026-10-26", "");
    assertEquals(1,rows.size());
    assertFalse(rows.get(0).containsKey("id"));
    assertFalse(rows.get(0).toString().contains(STUDENT_ID));
  }
  @Test void duplicateCannotCreateSecondMongoDocument() {
    AttendanceRepository repo=mock(AttendanceRepository.class);
    AttendanceEntry existing=entry("id-a","2026-10-26",STUDENT_ID);
    when(repo.findByDateAndClassNameAndStudentId("2026-10-26","",STUDENT_ID)).thenReturn(List.of(existing));
    assertThrows(AttendanceStorageService.DuplicateAttendanceException.class,()->new AttendanceStorageService(repo)
        .save("2026-10-26","","Another Name",STUDENT_ID,"","09:01 AM"));
    verify(repo,never()).save(any());
  }
  @Test void editTargetsStableRecordIdRatherThanMatchingAnotherStudentsId() {
    AttendanceRepository repo=mock(AttendanceRepository.class);
    AttendanceEntry a=entry("record-a","2026-10-25",STUDENT_ID);
    AttendanceEntry b=entry("record-b","2026-10-26",STUDENT_ID);
    when(repo.findById("record-b")).thenReturn(Optional.of(b));
    when(repo.findByDateAndClassNameAndStudentId(anyString(),anyString(),anyString())).thenReturn(List.of());
    when(repo.save(any())).thenAnswer(call -> call.getArgument(0));
    var service=new AttendanceStorageService(repo);
    service.editRow("All Records","record-b",2,List.of("","2026-10-26","New Name",STUDENT_ID,"","1","10:30 AM"));
    assertEquals("Student Name",a.name);
    assertEquals("New Name",b.name);
    verify(repo).save(b);
    verify(repo,never()).save(a);
  }
  @Test void deleteTargetsMongoDocumentId() {
    AttendanceRepository repo=mock(AttendanceRepository.class);
    when(repo.findById("record-b")).thenReturn(Optional.of(entry("record-b","2026-10-26",STUDENT_ID)));
    new AttendanceStorageService(repo).deleteRow("All Records","record-b",2);
    verify(repo).deleteById("record-b");
  }
  @Test void editReportsMissingDocumentInsteadOfSilentlyDoingNothing() {
    AttendanceRepository repo=mock(AttendanceRepository.class);
    when(repo.findById(anyString())).thenReturn(Optional.empty());
    assertThrows(NoSuchElementException.class,()->new AttendanceStorageService(repo).deleteRow("All Records","gone",2));
  }
  @Test void legacyRowIndexCannotModifyWrongRecord() {
    AttendanceRepository repo=mock(AttendanceRepository.class);
    var service=new AttendanceStorageService(repo);
    assertThrows(IllegalArgumentException.class,()->service.editRow("All Records",null,2,
        List.of("","2026-10-26","Test Student",STUDENT_ID,"","1","10:00 AM")));
    assertThrows(IllegalArgumentException.class,()->service.deleteRow("All Records",null,2));
    verify(repo,never()).findAll();
    verify(repo,never()).save(any());
    verify(repo,never()).deleteById(anyString());
  }
  @Test void addOnWrongSelectedDateTabCannotSilentlyUseAnotherTab() {
    AttendanceRepository repo=mock(AttendanceRepository.class);
    var service=new AttendanceStorageService(repo);
    assertThrows(IllegalArgumentException.class,()->service.addRow("2026-10-25",List.of("","2026-10-26","Test Student",STUDENT_ID,"","1","10:00 AM")));
    verify(repo,never()).save(any());
  }
  @Test void addFromAllRecordsUsesEnteredDate() {
    AttendanceRepository repo=mock(AttendanceRepository.class);
    when(repo.findByDateAndClassNameAndStudentId(anyString(),anyString(),anyString())).thenReturn(List.of());
    when(repo.save(any(AttendanceEntry.class))).thenAnswer(call->call.getArgument(0));
    var entry=new AttendanceStorageService(repo).addRow("All Records",
        List.of("","2026-10-26","Test Student",STUDENT_ID,"","1","10:00 AM"));
    assertEquals("2026-10-26",entry.tabName);
    assertEquals("",entry.className);
  }
  @Test void displayOrderUsesCreationTimeNotReusedSequenceNumber() {
    AttendanceRepository repo=mock(AttendanceRepository.class);
    AttendanceEntry a=entry("record-a","2026-10-26","2023200000686");
    AttendanceEntry b=entry("record-b","2026-10-26","2023200000687");
    a.sl=3;b.sl=3; // A deleted middle record can make SL values repeat.
    a.createdAt=java.time.Instant.parse("2026-10-26T01:00:00Z");
    b.createdAt=java.time.Instant.parse("2026-10-26T02:00:00Z");
    when(repo.findByDateAndClassNameOrderBySlAsc("2026-10-26", ""))
        .thenReturn(List.of(b,a));
    when(repo.findAll()).thenReturn(List.of(b,a));
    var service=new AttendanceStorageService(repo);
    var publicRows=service.getPublicRows("2026-10-26", "");
    assertEquals("Student Name", publicRows.get(0).get("name"));
    var result=service.getAdminRows("2026-10-26");
    @SuppressWarnings("unchecked")
    var ordered=(List<Map<String,Object>>)result.get("rows");
    assertEquals("record-a",ordered.get(0).get("recordId"));
    assertEquals("record-b",ordered.get(1).get("recordId"));
  }
  private static AttendanceEntry entry(String record,String date,String id) {
    return new AttendanceEntry(record,date,"",date,1,"Student Name",id,"","1","09:00 AM");
  }
  @Test void combinedDateAndClassFilterMatchesGeneralForBlankClassNames() {
    AttendanceRepository repo=mock(AttendanceRepository.class);
    AttendanceEntry general=entry("record-a","2026-10-26",STUDENT_ID);      // blank class displays as "General"
    AttendanceEntry realClass=new AttendanceEntry("record-b","2026-10-26","CSE","2026-10-26 - CSE",1,"Other Name",
        "2023200000687","","1","10:00 AM");
    when(repo.findAll()).thenReturn(List.of(general,realClass));
    var service=new AttendanceStorageService(repo);
    assertTrue(service.exportCsv("combined","2026-10-26|General").contains("Student Name"));
    assertFalse(service.exportCsv("combined","2026-10-26|General").contains("2023200000687"));
    assertTrue(service.exportCsv("combined","2026-10-26|CSE").contains("Other Name"));
    assertFalse(service.exportCsv("combined","2026-10-26|CSE").contains("Student Name"));
  }
  @Test void exportedDateStudentIdAndTimeRenderAsExcelTextNotHashMarks() {
    AttendanceRepository repo=mock(AttendanceRepository.class);
    when(repo.findAll()).thenReturn(List.of(entry("record-a","2026-10-26",STUDENT_ID)));
    var service=new AttendanceStorageService(repo);
    String csv=service.exportCsv("all","");
    assertTrue(csv.contains("=\"2026-10-26\""), "Date must be an Excel text-formula so it never shows ###");
    assertTrue(csv.contains("=\""+STUDENT_ID+"\""), "Student ID must be an Excel text-formula so it never shows 2.02E+12");
    assertTrue(csv.contains("=\"09:00 AM\""), "Time must be an Excel text-formula so it never shows ###");
  }
}
