package com.smartattendance.repo;
import com.smartattendance.model.AdminSettings;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface SettingsRepository extends MongoRepository<AdminSettings,String> {}
