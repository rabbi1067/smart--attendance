package com.smartattendance.config;

import com.mongodb.MongoSecurityException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Explains authentication errors hidden beneath repository bean creation failures. */
public class MongoAuthenticationFailureAnalyzer extends AbstractFailureAnalyzer<MongoSecurityException> {
  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, MongoSecurityException cause) {
    return new FailureAnalysis(
        "MongoDB authentication failed. Atlas rejected the credentials configured in MONGODB_URI; "
            + "the repository and mongoTemplate errors are consequences of this failure.",
        "In MongoDB Atlas > Security > Database Access, verify the database user for this cluster "
            + "and reset its password if necessary (this is not the Atlas website login). "
            + "In Render > Environment, replace MONGODB_URI with the matching connection string. "
            + "Percent-encode the username and password exactly once, keep /smart_attendance "
            + "as the database and use authSource=admin for Atlas. Remove surrounding quotes, "
            + "angle-bracket placeholders and any stale authMechanism override. "
            + "Save and redeploy, then verify /actuator/health reports UP. "
            + "A Git push alone cannot update Render credentials. See SETUP_BN.md section 1.1.",
        cause);
  }
}
