package com.openmd.server.auth.application;

public record NicknameAvailability(boolean available, String checkedNickname) {
}
