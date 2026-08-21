package com.openmd.server.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openmd.server.auth.domain.AuthErrorCode;
import com.openmd.server.auth.domain.User;
import com.openmd.server.auth.domain.UserRepository;
import com.openmd.server.auth.domain.UserStatus;
import com.openmd.server.auth.security.AccessTokenService;
import com.openmd.server.auth.security.IssuedAccessToken;
import com.openmd.server.global.entity.BaseEntity;
import com.openmd.server.global.error.BusinessException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");
	private final UserRepository users = mock(UserRepository.class);
	private final PasswordEncoder passwords = mock(PasswordEncoder.class);
	private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
	private final AccessTokenService accessTokens = mock(AccessTokenService.class);
	private AuthService service;

	@BeforeEach
	void setUp() {
		service = new AuthService(users, passwords, refreshTokens, accessTokens);
	}

	@Test
	void inactiveAccountsUseTheSameLoginFailureAsWrongPasswords() throws Exception {
		User user = pendingUser(23L);
		when(users.findByNormalizedEmail("learner@example.com")).thenReturn(Optional.of(user));
		when(passwords.matches("password1", "argon2-hash")).thenReturn(true);

		BusinessException exception = assertThrows(BusinessException.class,
			() -> service.login("learner@example.com", "password1"));

		assertEquals(AuthErrorCode.LOGIN_FAILED, exception.getErrorCode());
		verify(refreshTokens, never()).issue(23L);
	}

	@Test
	void activeAccountLoginStillIssuesAFullSession() throws Exception {
		User user = activeSignUpUser(24L, "Study7");
		when(users.findByNormalizedEmail("learner@example.com")).thenReturn(Optional.of(user));
		when(passwords.matches("password1", "argon2-hash")).thenReturn(true);
		when(refreshTokens.issue(24L)).thenReturn(new IssuedRefreshToken(
			"refresh-token", "session-id", NOW.plusSeconds(3600)
		));
		when(accessTokens.issue(24L, "session-id"))
			.thenReturn(new IssuedAccessToken("access-token", NOW.plusSeconds(300)));

		SessionTokens session = service.login(" Learner@Example.COM ", "password1");

		assertEquals("access-token", session.accessToken());
		assertEquals("refresh-token", session.refreshToken());
	}

	@Test
	void refreshCompletesUserValidationAndAccessTokenIssuanceBeforeConsumingTheRefreshToken() throws Exception {
		User user = activeUser(31L);
		RefreshTokenSession current = new RefreshTokenSession(
			31L,
			"session-id",
			Instant.parse("2026-09-18T00:00:00Z")
		);
		IssuedRefreshToken replacement = new IssuedRefreshToken(
			"session-id.new-secret",
			"session-id",
			current.expiresAt()
		);
		when(refreshTokens.inspect("current-refresh")).thenReturn(current);
		when(users.findById(31L)).thenReturn(Optional.of(user));
		when(accessTokens.issue(31L, "session-id"))
			.thenReturn(new IssuedAccessToken("access-token", NOW.plusSeconds(300)));
		when(refreshTokens.rotate("current-refresh"))
			.thenReturn(new RotatedRefreshToken(31L, replacement));

		SessionTokens result = service.refresh("current-refresh");

		assertEquals("access-token", result.accessToken());
		assertEquals("session-id.new-secret", result.refreshToken());
		InOrder order = inOrder(refreshTokens, users, accessTokens);
		order.verify(refreshTokens).inspect("current-refresh");
		order.verify(users).findById(31L);
		order.verify(accessTokens).issue(31L, "session-id");
		order.verify(refreshTokens).rotate("current-refresh");
	}

	@Test
	void refreshDoesNotConsumeTheTokenWhenUserLookupFails() {
		when(refreshTokens.inspect("current-refresh")).thenReturn(new RefreshTokenSession(
			31L,
			"session-id",
			Instant.parse("2026-09-18T00:00:00Z")
		));
		when(users.findById(31L)).thenThrow(new IllegalStateException("database unavailable"));

		assertThrows(IllegalStateException.class, () -> service.refresh("current-refresh"));

		verify(refreshTokens, never()).rotate("current-refresh");
	}

	@Test
	void refreshDoesNotConsumeTheTokenWhenAccessTokenIssuanceFails() throws Exception {
		User user = activeUser(31L);
		when(refreshTokens.inspect("current-refresh")).thenReturn(new RefreshTokenSession(
			31L,
			"session-id",
			Instant.parse("2026-09-18T00:00:00Z")
		));
		when(users.findById(31L)).thenReturn(Optional.of(user));
		when(accessTokens.issue(31L, "session-id"))
			.thenThrow(new IllegalStateException("signing unavailable"));

		assertThrows(IllegalStateException.class, () -> service.refresh("current-refresh"));

		verify(refreshTokens, never()).rotate("current-refresh");
	}

	@Test
	void currentUserStillReturnsTheNewSignUpNickname() throws Exception {
		User user = activeSignUpUser(32L, "Study7");
		when(users.findById(32L)).thenReturn(Optional.of(user));

		CurrentUser current = service.currentUser(32L);

		assertEquals(32L, current.id());
		assertEquals("learner@example.com", current.email());
		assertEquals("Study7", current.nickname());
		assertEquals(UserStatus.ACTIVE, current.status());
	}

	@Test
	void logoutStillRevokesThePresentedRefreshCredential() {
		service.logout("current-refresh");

		verify(refreshTokens).revoke("current-refresh");
	}

	private User pendingUser(long id) throws Exception {
		User user = User.pending("learner@example.com", "learner@example.com", "argon2-hash");
		setId(user, id);
		return user;
	}

	private User activeUser(long id) throws Exception {
		User user = pendingUser(id);
		user.activate(NOW);
		return user;
	}

	private User activeSignUpUser(long id, String nickname) throws Exception {
		User user = User.active(
			"learner@example.com",
			"learner@example.com",
			"argon2-hash",
			nickname,
			NOW.minusSeconds(30),
			"TEMP-2026-08-20",
			"TEMP-2026-08-20",
			NOW
		);
		setId(user, id);
		return user;
	}

	private static void setId(User user, long id) throws Exception {
		Field field = BaseEntity.class.getDeclaredField("id");
		field.setAccessible(true);
		field.set(user, id);
	}
}
