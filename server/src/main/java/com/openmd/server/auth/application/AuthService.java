package com.openmd.server.auth.application;

import com.openmd.server.auth.domain.AuthErrorCode;
import com.openmd.server.auth.domain.User;
import com.openmd.server.auth.domain.UserRepository;
import com.openmd.server.auth.domain.UserStatus;
import com.openmd.server.auth.security.AccessTokenService;
import com.openmd.server.auth.security.IssuedAccessToken;
import com.openmd.server.global.error.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokenService;
	private final AccessTokenService accessTokenService;

	public AuthService(
		UserRepository userRepository,
		PasswordEncoder passwordEncoder,
		RefreshTokenService refreshTokenService,
		AccessTokenService accessTokenService
	) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.refreshTokenService = refreshTokenService;
		this.accessTokenService = accessTokenService;
	}

	public SessionTokens login(String email, String password) {
		User user = userRepository.findByNormalizedEmail(EmailNormalizer.normalize(email))
			.orElseThrow(this::loginFailed);
		if (!passwordEncoder.matches(password, user.getPasswordHash())
			|| user.getStatus() != UserStatus.ACTIVE
			|| user.getEmailVerifiedAt() == null) {
			throw loginFailed();
		}
		IssuedRefreshToken refresh = refreshTokenService.issue(user.getId());
		IssuedAccessToken access = accessTokenService.issue(user.getId(), refresh.sessionId());
		return sessionTokens(access, refresh);
	}

	public SessionTokens refresh(String refreshToken) {
		RefreshTokenSession current = refreshTokenService.inspect(refreshToken);
		User user = userRepository.findById(current.userId()).orElseThrow(this::invalidCredential);
		if (user.getStatus() != UserStatus.ACTIVE || user.getEmailVerifiedAt() == null) {
			refreshTokenService.revoke(refreshToken);
			throw invalidCredential();
		}
		IssuedAccessToken access = accessTokenService.issue(user.getId(), current.sessionId());
		RotatedRefreshToken rotated = refreshTokenService.rotate(refreshToken);
		return sessionTokens(access, rotated.refreshToken());
	}

	public void logout(String refreshToken) {
		refreshTokenService.revoke(refreshToken);
	}

	@Transactional(readOnly = true)
	public CurrentUser currentUser(long userId) {
		User user = userRepository.findById(userId).orElseThrow(this::invalidCredential);
		if (user.getStatus() != UserStatus.ACTIVE || user.getEmailVerifiedAt() == null) {
			throw invalidCredential();
		}
		return new CurrentUser(user.getId(), user.getEmail(), user.getNickname(), true, user.getStatus());
	}

	private SessionTokens sessionTokens(IssuedAccessToken access, IssuedRefreshToken refresh) {
		return new SessionTokens(access.token(), access.expiresAt(), refresh.token(), refresh.expiresAt());
	}

	private BusinessException loginFailed() {
		return new BusinessException(AuthErrorCode.LOGIN_FAILED);
	}

	private BusinessException invalidCredential() {
		return new BusinessException(AuthErrorCode.INVALID_CREDENTIAL);
	}
}
