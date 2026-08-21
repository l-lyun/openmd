package com.openmd.server.auth.api;

public record EmailVerifiedResponse(boolean emailVerified, String signUpToken, String nextAction) {
}
