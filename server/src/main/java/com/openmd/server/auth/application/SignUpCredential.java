package com.openmd.server.auth.application;

import java.time.Instant;

public record SignUpCredential(String displayEmail, String normalizedEmail, Instant verifiedAt) {
}
