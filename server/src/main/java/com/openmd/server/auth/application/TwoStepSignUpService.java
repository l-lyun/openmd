package com.openmd.server.auth.application;

import com.openmd.server.auth.domain.AuthErrorCode;
import com.openmd.server.auth.domain.PasswordPolicy;
import com.openmd.server.auth.domain.User;
import com.openmd.server.auth.domain.UserRepository;
import com.openmd.server.auth.security.AccessTokenService;
import com.openmd.server.auth.security.IssuedAccessToken;
import com.openmd.server.global.error.BusinessException;
import com.openmd.server.global.error.CommonErrorCode;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionOperations;

public final class TwoStepSignUpService {

	private static final Duration VERIFICATION_TTL = Duration.ofMinutes(10);
	private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
	private static final Duration SIGN_UP_TOKEN_TTL = Duration.ofMinutes(15);
	private static final String CODE_REGEX = "[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}";
	private static final String NICKNAME_REGEX = "[가-힣A-Za-z0-9]{2,10}";
	private static final String TERMS_VERSION = "TEMP-2026-08-20";
	private static final Map<String, String> REQUIRED_AGREEMENTS = Map.of(
		"SERVICE_TERMS", TERMS_VERSION,
		"PRIVACY_COLLECTION", TERMS_VERSION
	);

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final VerificationCodeGenerator codeGenerator;
	private final VerificationCodeDigest codeDigest;
	private final EmailVerificationStore verificationStore;
	private final SignUpCredentialStore credentialStore;
	private final VerificationEmailSender emailSender;
	private final RefreshTokenService refreshTokenService;
	private final AccessTokenService accessTokenService;
	private final Clock clock;
	private final TransactionOperations transactions;

	public TwoStepSignUpService(
		UserRepository userRepository,
		PasswordEncoder passwordEncoder,
		VerificationCodeGenerator codeGenerator,
		VerificationCodeDigest codeDigest,
		EmailVerificationStore verificationStore,
		SignUpCredentialStore credentialStore,
		VerificationEmailSender emailSender,
		RefreshTokenService refreshTokenService,
		AccessTokenService accessTokenService,
		Clock clock,
		TransactionOperations transactions
	) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.codeGenerator = codeGenerator;
		this.codeDigest = codeDigest;
		this.verificationStore = verificationStore;
		this.credentialStore = credentialStore;
		this.emailSender = emailSender;
		this.refreshTokenService = refreshTokenService;
		this.accessTokenService = accessTokenService;
		this.clock = clock;
		this.transactions = transactions;
	}

	public void requestEmailVerification(String email) {
		String displayEmail = email.trim();
		String normalizedEmail = EmailNormalizer.normalize(email);
		if (userRepository.findByNormalizedEmail(normalizedEmail).isPresent()) {
			return;
		}
		String emailKey = codeDigest.emailKey(normalizedEmail);
		String code = codeGenerator.generate();
		String digest = codeDigest.create(emailKey, code);
		EmailVerificationStore.IssueResult result = verificationStore.issue(
			emailKey,
			digest,
			clock.instant(),
			VERIFICATION_TTL,
			RESEND_COOLDOWN,
			true
		);
		if (!result.issued()) {
			return;
		}
		try {
			emailSender.sendVerificationCode(displayEmail, code);
		} catch (BusinessException exception) {
			if (exception.getErrorCode() == AuthErrorCode.EMAIL_DELIVERY_FAILED) {
				try {
					verificationStore.cancelIssue(emailKey, digest);
				} catch (RuntimeException compensationFailure) {
					exception.addSuppressed(compensationFailure);
				}
			}
			throw exception;
		}
	}

	public IssuedSignUpToken confirmEmail(String email, String submittedCode) {
		String code = submittedCode == null ? "" : submittedCode.trim().toUpperCase(Locale.ROOT);
		if (!code.matches(CODE_REGEX)) {
			throw new BusinessException(AuthErrorCode.INVALID_VERIFICATION_CODE);
		}
		String displayEmail = email.trim();
		String normalizedEmail = EmailNormalizer.normalize(email);
		String emailKey = codeDigest.emailKey(normalizedEmail);
		EmailVerificationStore.VerificationResult result = verificationStore.verify(
			emailKey,
			codeDigest.create(emailKey, code)
		);
		if (result == EmailVerificationStore.VerificationResult.MISMATCHED) {
			throw new BusinessException(AuthErrorCode.INVALID_VERIFICATION_CODE);
		}
		if (result == EmailVerificationStore.VerificationResult.EXPIRED) {
			throw new BusinessException(AuthErrorCode.EXPIRED_VERIFICATION_CODE);
		}

		String token = UUID.randomUUID().toString();
		credentialStore.save(
			SignUpTokenDigest.create(token),
			new SignUpCredential(displayEmail, normalizedEmail, clock.instant()),
			SIGN_UP_TOKEN_TTL
		);
		verificationStore.consume(emailKey);
		return new IssuedSignUpToken(token);
	}

	public NicknameAvailability nicknameAvailability(String input) {
		String nickname = normalizeNickname(input);
		return new NicknameAvailability(!userRepository.existsByNicknameIgnoreCase(nickname), nickname);
	}

	public SessionTokens completeSignUp(SignUpCommand command) {
		String tokenDigest = signUpTokenDigest(command.signUpToken());
		SignUpCredential credential = credentialStore.find(tokenDigest)
			.orElseThrow(this::invalidSignUpCredential);
		String nickname = normalizeNickname(command.nickname());
		validatePassword(command.password());
		validateAgreements(command.agreements());
		if (userRepository.existsByNicknameIgnoreCase(nickname)) {
			throw new BusinessException(AuthErrorCode.NICKNAME_CONFLICT);
		}

		User user;
		String passwordHash = passwordEncoder.encode(command.password());
		try {
			user = transactions.execute(status -> userRepository.saveAndFlush(User.active(
				credential.displayEmail(),
				credential.normalizedEmail(),
				passwordHash,
				nickname,
				credential.verifiedAt(),
				TERMS_VERSION,
				TERMS_VERSION,
				clock.instant()
			)));
		} catch (DataIntegrityViolationException exception) {
			if (hasConstraint(exception, "uk_users_nickname")
				|| userRepository.existsByNicknameIgnoreCase(nickname)) {
				throw new BusinessException(AuthErrorCode.NICKNAME_CONFLICT);
			}
			if (hasConstraint(exception, "uk_users_normalized_email")) {
				throw invalidSignUpCredential();
			}
			throw exception;
		}
		if (user == null) {
			throw new IllegalStateException("Sign-up transaction returned no user");
		}

		try {
			credentialStore.consume(tokenDigest);
		} catch (RuntimeException ignored) {
			// The ACTIVE user and DB unique constraints remain authoritative; Redis has a 15-minute TTL.
		}
		IssuedRefreshToken refresh = refreshTokenService.issue(user.getId());
		IssuedAccessToken access = accessTokenService.issue(user.getId(), refresh.sessionId());
		return new SessionTokens(access.token(), access.expiresAt(), refresh.token(), refresh.expiresAt());
	}

	private String normalizeNickname(String input) {
		String nickname = input == null ? "" : Normalizer.normalize(input, Normalizer.Form.NFC);
		if (!nickname.matches(NICKNAME_REGEX)) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT);
		}
		return nickname;
	}

	private void validatePassword(String password) {
		if (!PasswordPolicy.isValid(password)) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT);
		}
	}

	private void validateAgreements(List<TermsAgreement> agreements) {
		if (agreements == null) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT);
		}
		Map<String, String> submitted;
		try {
			submitted = agreements.stream().collect(Collectors.toUnmodifiableMap(
				TermsAgreement::termsId,
				TermsAgreement::version,
				(first, second) -> { throw new IllegalArgumentException("duplicate agreement"); }
			));
		} catch (RuntimeException exception) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT);
		}
		if (!REQUIRED_AGREEMENTS.equals(submitted)) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT);
		}
	}

	private String signUpTokenDigest(String token) {
		try {
			if (token == null) {
				throw invalidSignUpCredential();
			}
			UUID uuid = UUID.fromString(token);
			if (uuid.version() != 4) {
				throw invalidSignUpCredential();
			}
			return SignUpTokenDigest.create(uuid.toString());
		} catch (IllegalArgumentException exception) {
			throw invalidSignUpCredential();
		}
	}

	private BusinessException invalidSignUpCredential() {
		return new BusinessException(AuthErrorCode.INVALID_CREDENTIAL);
	}

	private boolean hasConstraint(Throwable exception, String constraintName) {
		for (Throwable current = exception; current != null; current = current.getCause()) {
			String message = current.getMessage();
			if (message != null && message.contains(constraintName)) {
				return true;
			}
		}
		return false;
	}
}
