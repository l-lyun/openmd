package com.openmd.server.auth.application;

import com.openmd.server.auth.domain.UserStatus;

public record CurrentUser(long id, String email, String nickname, boolean emailVerified, UserStatus status) {
}
