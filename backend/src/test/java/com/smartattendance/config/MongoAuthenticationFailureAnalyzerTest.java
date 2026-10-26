package com.smartattendance.config;

import com.mongodb.MongoCredential;
import com.mongodb.MongoSecurityException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.io.support.SpringFactoriesLoader;

import static org.junit.jupiter.api.Assertions.*;

class MongoAuthenticationFailureAnalyzerTest {
  @Test void explainsAuthenticationFailureInsideRepositoryBeanErrorsWithoutExposingCredentials() {
    var credential = MongoCredential.createCredential(
        "private-db-user", "admin", "private-db-password".toCharArray());
    var failure = new BeanCreationException("settingsRepository",
        new BeanCreationException("mongoTemplate",
            new MongoSecurityException(credential, "private server error")));

    var analysis = new MongoAuthenticationFailureAnalyzer().analyze(failure);

    assertNotNull(analysis);
    assertTrue(analysis.getDescription().contains("MongoDB authentication failed"));
    assertTrue(analysis.getAction().contains("Render > Environment"));
    assertTrue(analysis.getAction().contains("Percent-encode"));
    assertTrue(analysis.getAction().contains("redeploy"));
    String report = analysis.getDescription() + analysis.getAction();
    assertFalse(report.contains("private-db-user"));
    assertFalse(report.contains("private-db-password"));
    assertFalse(report.contains("private server error"));
  }

  @Test void doesNotMisdiagnoseUnrelatedStartupFailures() {
    assertNull(new MongoAuthenticationFailureAnalyzer().analyze(
        new BeanCreationException("mongoTemplate", "Index creation failed")));
  }

  @Test void springBootCanDiscoverTheAnalyzer() {
    assertTrue(SpringFactoriesLoader.loadFactoryNames(FailureAnalyzer.class, getClass().getClassLoader())
        .contains(MongoAuthenticationFailureAnalyzer.class.getName()));
  }
}
