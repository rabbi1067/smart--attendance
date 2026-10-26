package com.smartattendance.repo;

import com.smartattendance.model.AttendanceEntry;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface AttendanceRepository extends MongoRepository<AttendanceEntry, String> {
  List<AttendanceEntry> findByDateOrderBySlAsc(String date);
  List<AttendanceEntry> findByDateAndClassNameOrderBySlAsc(String date, String className);
  List<AttendanceEntry> findByTabNameOrderBySlAsc(String tabName);
  List<AttendanceEntry> findAllByOrderByCreatedAtDesc();
  List<AttendanceEntry> findByClassNameOrderByCreatedAtDesc(String className);
  List<AttendanceEntry> findByDateOrderByCreatedAtDesc(String date);
  boolean existsByDateAndClassNameAndStudentId(String date, String className, String studentId);
  boolean existsByDateAndStudentId(String date, String studentId);
  List<AttendanceEntry> findByDateAndClassNameAndStudentId(String date, String className, String studentId);
  long countByTabName(String tabName);
}
