package com.openmd.server.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openmd.server.auth.domain.AuthErrorCode;
import com.openmd.server.auth.domain.User;
import com.openmd.server.auth.domain.UserRepository;
import com.openmd.server.auth.security.AccessTokenService;
import com.openmd.server.auth.security.IssuedAccessToken;
import com.openmd.server.global.entity.BaseEntity;
import com.openmd.server.global.error.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class TwoStepSignUpServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
	private final UserRepository users = mock(UserRepository.class);
	private final PasswordEncoder passwords = mock(PasswordEncoder.class);
	private final VerificationCodeGenerator codes = mock(VerificationCodeGenerator.class);
	private final VerificationCodeDigest digests = new VerificationCodeDigest(
		"0123456789abcdef0123456789abcdef".getBytes()
	);
	private final EmailVerificationStore verifications = mock(EmailVerificationStore.class);
	private final SignUpCredentialStore credentials = mock(SignUpCredentialStore.class);
	private final VerificationEmailSender emails = mock(VerificationEmailSender.class);
	private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
	private final AccessTokenService accessTokens = mock(AccessTokenService.class);
	private final TransactionOperations transactions = mock(TransactionOperations.class);
	private TwoStepSignUpService service;

	@BeforeEach
	void setUp() {
		org.mockito.Mockito.doAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			return callback.doInTransaction(mock(TransactionStatus.class));
		}).when(transactions).execute(any());
		service = new TwoStepSignUpService(users, passwords, codes, digests, verifications, credentials,
			emails, refreshTokens, accessTokens, Clock.fixed(NOW, ZoneOffset.UTC), transactions);
	}

	@Test
	void requestingEmailVerificationDoesNotCreateAUserAndUsesOnlyAnEmailDigestKey() {
		when(users.findByNormalizedEmail("learner@example.com")).thenReturn(Optional.empty());
		when(codes.generate()).thenReturn("A7K9M2");
		String emailKey = digests.emailKey("learner@example.com");
		when(verifications.issue(eq(emailKey), any(), eq(NOW), any(), any(), eq(true)))
			.thenReturn(EmailVerificationStore.IssueResult.success());

		service.requestEmailVerification(" Learner@Example.COM ");

		verify(users, never()).saveAndFlush(any());
		verifyNoInteractions(passwords);
		verify(verifications).issue(
			eq(emailKey), eq(digests.create(emailKey, "A7K9M2")), eq(NOW), any(), any(), eq(true)
		);
		verify(emails).sendVerificationCode("Learner@Example.COM", "A7K9M2");
	}

	@Test
	void existingEmailKeepsTheSameAcceptedSurfaceWithoutSendingAnotherCode() {
		when(users.findByNormalizedEmail("learner@example.com")).thenReturn(Optional.of(mock(User.class)));

		service.requestEmailVerification("learner@example.com");

		verify(users, never()).saveAndFlush(any());
		verifyNoInteractions(verifications, emails);
	}

	@Test
	void confirmingCodeIssuesUuidV4CredentialWithoutCreatingAUser() {
		String emailKey = digests.emailKey("learner@example.com");
		when(verifications.verify(emailKey, digests.create(emailKey, "A7K9M2")))
			.thenReturn(EmailVerificationStore.VerificationResult.MATCHED);

		IssuedSignUpToken issued = service.confirmEmail("Learner@Example.COM", " a7k9m2 ");

		UUID uuid = UUID.fromString(issued.token());
		assertEquals(4, uuid.version());
		ArgumentCaptor<SignUpCredential> credential = ArgumentCaptor.forClass(SignUpCredential.class);
		verify(credentials).save(eq(SignUpTokenDigest.create(issued.token())), credential.capture(), any());
		assertEquals("learner@example.com", credential.getValue().normalizedEmail());
		assertEquals("Learner@Example.COM", credential.getValue().displayEmail());
		assertEquals(NOW, credential.getValue().verifiedAt());
		verify(verifications).consume(emailKey);
		verifyNoInteractions(users);
	}

	@Test
	void confirmationDistinguishesMismatchedAndExpiredCodesWithoutIssuingCredentials() {
		String mismatchedEmailKey = digests.emailKey("mismatch@example.com");
		when(verifications.verify(
			mismatchedEmailKey,
			digests.create(mismatchedEmailKey, "A7K9M2")
		)).thenReturn(EmailVerificationStore.VerificationResult.MISMATCHED);
		String expiredEmailKey = digests.emailKey("expired@example.com");
		when(verifications.verify(
			expiredEmailKey,
			digests.create(expiredEmailKey, "A7K9M2")
		)).thenReturn(EmailVerificationStore.VerificationResult.EXPIRED);

		BusinessException mismatched = assertThrows(BusinessException.class,
			() -> service.confirmEmail("mismatch@example.com", "A7K9M2"));
		BusinessException expired = assertThrows(BusinessException.class,
			() -> service.confirmEmail("expired@example.com", "A7K9M2"));

		assertEquals(AuthErrorCode.INVALID_VERIFICATION_CODE, mismatched.getErrorCode());
		assertEquals(AuthErrorCode.EXPIRED_VERIFICATION_CODE, expired.getErrorCode());
		verifyNoInteractions(credentials, users);
	}

	@Test
	void nicknameAvailabilityUsesNfcAndCaseInsensitiveRepositoryComparison() {
		when(users.existsByNicknameIgnoreCase("가7")).thenReturn(false);

		NicknameAvailability result = service.nicknameAvailability("가7");

		assertTrue(result.available());
		assertEquals("가7", result.checkedNickname());
	}

	@Test
	void finalSignUpCommitsActiveUserBeforeConsumingCredentialAndIssuingSession() throws Exception {
		AtomicBoolean transactionActive = new AtomicBoolean();
		org.mockito.Mockito.doAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			transactionActive.set(true);
			try {
				return callback.doInTransaction(mock(TransactionStatus.class));
			} finally {
				transactionActive.set(false);
			}
		}).when(transactions).execute(any());
		org.mockito.Mockito.doAnswer(invocation -> {
			assertFalse(transactionActive.get(), "credential must be consumed after DB transaction completion");
			return null;
		}).when(credentials).consume(any());
		String token = UUID.randomUUID().toString();
		String tokenDigest = SignUpTokenDigest.create(token);
		when(credentials.find(tokenDigest)).thenReturn(Optional.of(new SignUpCredential(
			"Learner@Example.COM", "learner@example.com", NOW.minusSeconds(30)
		)));
		when(users.existsByNicknameIgnoreCase("Study7")).thenReturn(false);
		when(passwords.encode("password1")).thenReturn("argon2-hash");
		when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			setId(user, 71L);
			return user;
		});
		when(refreshTokens.issue(any(Long.class))).thenReturn(new IssuedRefreshToken(
			"refresh-token", "session-id", NOW.plusSeconds(3600)
		));
		when(accessTokens.issue(any(Long.class), eq("session-id")))
			.thenReturn(new IssuedAccessToken("access-token", NOW.plusSeconds(300)));

		SessionTokens session = service.completeSignUp(command(token, "Study7"));

		assertEquals("access-token", session.accessToken());
		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(users).saveAndFlush(saved.capture());
		assertEquals("Study7", saved.getValue().getNickname());
		assertEquals("TEMP-2026-08-20", saved.getValue().getServiceTermsVersion());
		assertEquals(NOW, saved.getValue().getServiceTermsAgreedAt());
		assertEquals("TEMP-2026-08-20", saved.getValue().getPrivacyTermsVersion());
		assertEquals(NOW, saved.getValue().getPrivacyTermsAgreedAt());
		InOrder order = inOrder(transactions, credentials, refreshTokens, accessTokens);
		order.verify(transactions).execute(any());
		order.verify(credentials).consume(tokenDigest);
		order.verify(refreshTokens).issue(any(Long.class));
		order.verify(accessTokens).issue(any(Long.class), eq("session-id"));
	}

	@Test
	void tokenDeleteFailureReliesOnTtlAndStillIssuesTheCommittedUsersSession() throws Exception {
		String token = UUID.randomUUID().toString();
		String digest = SignUpTokenDigest.create(token);
		when(credentials.find(digest)).thenReturn(Optional.of(new SignUpCredential(
			"learner@example.com", "learner@example.com", NOW
		)));
		when(users.existsByNicknameIgnoreCase("Study8")).thenReturn(false);
		when(passwords.encode("password1")).thenReturn("argon2-hash");
		when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			setId(user, 72L);
			return user;
		});
		org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
			.when(credentials).consume(digest);
		when(refreshTokens.issue(72L)).thenReturn(new IssuedRefreshToken(
			"refresh-token", "session-id", NOW.plusSeconds(3600)
		));
		when(accessTokens.issue(72L, "session-id"))
			.thenReturn(new IssuedAccessToken("access-token", NOW.plusSeconds(300)));

		SessionTokens session = service.completeSignUp(command(token, "Study8"));

		assertEquals("access-token", session.accessToken());
		verify(refreshTokens).issue(72L);
	}

	@Test
	void failedDatabaseInsertPreservesTheSignUpCredential() {
		String token = UUID.randomUUID().toString();
		String digest = SignUpTokenDigest.create(token);
		when(credentials.find(digest)).thenReturn(Optional.of(new SignUpCredential(
			"learner@example.com", "learner@example.com", NOW
		)));
		when(users.existsByNicknameIgnoreCase("Taken7")).thenReturn(false);
		when(passwords.encode("password1")).thenReturn("argon2-hash");
		when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("race"));

		assertThrows(DataIntegrityViolationException.class,
			() -> service.completeSignUp(command(token, "Taken7")));

		verify(credentials, never()).consume(digest);
		verifyNoInteractions(refreshTokens, accessTokens);
	}

	@Test
	void concurrentNicknameConstraintUsesAuth010AndPreservesCredential() {
		String token = UUID.randomUUID().toString();
		String digest = SignUpTokenDigest.create(token);
		when(credentials.find(digest)).thenReturn(Optional.of(new SignUpCredential(
			"learner@example.com", "learner@example.com", NOW
		)));
		when(users.existsByNicknameIgnoreCase("Race7")).thenReturn(false);
		when(passwords.encode("password1")).thenReturn("argon2-hash");
		when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException(
			"Duplicate entry for key 'uk_users_nickname'"
		));

		BusinessException exception = assertThrows(BusinessException.class,
			() -> service.completeSignUp(command(token, "Race7")));

		assertEquals(AuthErrorCode.NICKNAME_CONFLICT, exception.getErrorCode());
		verify(credentials, never()).consume(digest);
	}

	@Test
	void rejectsMissingRequiredAgreementBeforeCreatingAUser() {
		String token = UUID.randomUUID().toString();
		when(credentials.find(SignUpTokenDigest.create(token))).thenReturn(Optional.of(new SignUpCredential(
			"learner@example.com", "learner@example.com", NOW
		)));
		SignUpCommand missingPrivacy = new SignUpCommand(token, "password1", "Study7", List.of(
			new TermsAgreement("SERVICE_TERMS", "TEMP-2026-08-20")
		));

		BusinessException exception = assertThrows(BusinessException.class,
			() -> service.completeSignUp(missingPrivacy));

		assertEquals("COMMON_001", exception.getErrorCode().code());
		verify(users, never()).saveAndFlush(any());
		verify(credentials, never()).consume(any());
	}

	@Test
	void rejectsDuplicateOrUnsupportedAgreementVersionsWithoutCreatingAUser() {
		String token = UUID.randomUUID().toString();
		String digest = SignUpTokenDigest.create(token);
		when(credentials.find(digest)).thenReturn(Optional.of(new SignUpCredential(
			"learner@example.com", "learner@example.com", NOW
		)));
		SignUpCommand duplicated = new SignUpCommand(token, "password1", "Study7", List.of(
			new TermsAgreement("SERVICE_TERMS", "TEMP-2026-08-20"),
			new TermsAgreement("SERVICE_TERMS", "TEMP-2026-08-20"),
			new TermsAgreement("PRIVACY_COLLECTION", "TEMP-2026-08-20")
		));
		SignUpCommand unsupportedVersion = new SignUpCommand(token, "password1", "Study7", List.of(
			new TermsAgreement("SERVICE_TERMS", "OLD"),
			new TermsAgreement("PRIVACY_COLLECTION", "TEMP-2026-08-20")
		));

		BusinessException duplicateError = assertThrows(BusinessException.class,
			() -> service.completeSignUp(duplicated));
		BusinessException versionError = assertThrows(BusinessException.class,
			() -> service.completeSignUp(unsupportedVersion));

		assertEquals("COMMON_001", duplicateError.getErrorCode().code());
		assertEquals("COMMON_001", versionError.getErrorCode().code());
		verify(users, never()).saveAndFlush(any());
		verify(credentials, never()).consume(any());
	}

	@Test
	void missingExpiredOrUsedCredentialUsesAuth005WithoutCreatingAUser() {
		String missingToken = UUID.randomUUID().toString();
		String alreadyUsedToken = UUID.randomUUID().toString();
		when(credentials.find(SignUpTokenDigest.create(missingToken))).thenReturn(Optional.empty());
		when(credentials.find(SignUpTokenDigest.create(alreadyUsedToken))).thenReturn(Optional.empty());

		BusinessException missing = assertThrows(BusinessException.class,
			() -> service.completeSignUp(command(missingToken, "Study7")));
		BusinessException used = assertThrows(BusinessException.class,
			() -> service.completeSignUp(command(alreadyUsedToken, "Study8")));

		assertEquals(AuthErrorCode.INVALID_CREDENTIAL, missing.getErrorCode());
		assertEquals(AuthErrorCode.INVALID_CREDENTIAL, used.getErrorCode());
		verifyNoInteractions(passwords, refreshTokens, accessTokens);
		verify(users, never()).saveAndFlush(any());
	}

	@Test
	void concurrentEmailConstraintAllowsOnlyTheDatabaseWinnerToIssueASession() {
		String token = UUID.randomUUID().toString();
		String digest = SignUpTokenDigest.create(token);
		when(credentials.find(digest)).thenReturn(Optional.of(new SignUpCredential(
			"learner@example.com", "learner@example.com", NOW
		)));
		when(users.existsByNicknameIgnoreCase("Study7")).thenReturn(false);
		when(passwords.encode("password1")).thenReturn("argon2-hash");
		when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException(
			"Duplicate entry for key 'uk_users_normalized_email'"
		));

		BusinessException loser = assertThrows(BusinessException.class,
			() -> service.completeSignUp(command(token, "Study7")));

		assertEquals(AuthErrorCode.INVALID_CREDENTIAL, loser.getErrorCode());
		verify(credentials, never()).consume(digest);
		verifyNoInteractions(refreshTokens, accessTokens);
	}

	@Test
	void duplicateNicknameUsesTheDocumentedConflictCodeAndPreservesCredential() {
		String token = UUID.randomUUID().toString();
		String digest = SignUpTokenDigest.create(token);
		when(credentials.find(digest)).thenReturn(Optional.of(new SignUpCredential(
			"learner@example.com", "learner@example.com", NOW
		)));
		when(users.existsByNicknameIgnoreCase("Study7")).thenReturn(true);

		BusinessException exception = assertThrows(BusinessException.class,
			() -> service.completeSignUp(command(token, "Study7")));

		assertEquals(AuthErrorCode.NICKNAME_CONFLICT, exception.getErrorCode());
		verify(credentials, never()).consume(digest);
	}

	private SignUpCommand command(String token, String nickname) {
		return new SignUpCommand(token, "password1", nickname, List.of(
			new TermsAgreement("SERVICE_TERMS", "TEMP-2026-08-20"),
			new TermsAgreement("PRIVACY_COLLECTION", "TEMP-2026-08-20")
		));
	}

	private static void setId(User user, long id) throws Exception {
		Field field = BaseEntity.class.getDeclaredField("id");
		field.setAccessible(true);
		field.set(user, id);
	}
}
