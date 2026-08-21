package com.openmd.server.auth.config;

import com.openmd.server.auth.application.*;
import com.openmd.server.auth.api.BrowserRefreshCookie;
import com.openmd.server.auth.domain.UserRepository;
import com.openmd.server.auth.infrastructure.*;
import com.openmd.server.auth.security.AccessTokenService;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "openmd.auth.enabled", havingValue = "true", matchIfMissing = true)
public class AuthConfiguration {

	@Bean Clock clock() { return Clock.systemUTC(); }

	@Bean
	BrowserRefreshCookie browserRefreshCookie(
		Clock clock,
		@Value("${openmd.auth.browser.cookie.name:__Host-openmd_refresh}") String name,
		@Value("${openmd.auth.browser.cookie.secure:true}") boolean secure,
		@Value("${openmd.auth.browser.cookie.same-site:Lax}") String sameSite,
		@Value("${openmd.auth.browser.cookie.path:/}") String path
	) {
		return new BrowserRefreshCookie(name, secure, sameSite, path, clock);
	}
	@Bean PasswordEncoder passwordEncoder() { return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }
	@Bean VerificationCodeGenerator verificationCodeGenerator() { return new VerificationCodeGenerator(new SecureRandom()); }

	@Bean
	VerificationCodeDigest verificationCodeDigest(@Value("${openmd.auth.email-code-hmac-secret}") String secret) {
		try {
			return new VerificationCodeDigest(Base64.getDecoder().decode(secret));
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("Email code HMAC secret must be valid Base64", exception);
		}
	}

	@Bean
	AccessTokenService accessTokenService(@Value("${openmd.auth.access-token-secret}") String secret, Clock clock) {
		return AccessTokenService.create(secret, clock);
	}

	@Bean EmailVerificationStore emailVerificationStore(StringRedisTemplate redis) { return new RedisEmailVerificationStore(redis); }
	@Bean SignUpCredentialStore signUpCredentialStore(StringRedisTemplate redis) { return new RedisSignUpCredentialStore(redis); }
	@Bean RefreshSessionStore refreshSessionStore(StringRedisTemplate redis) { return new RedisRefreshSessionStore(redis); }

	@Bean
	RefreshTokenService refreshTokenService(
		RefreshSessionStore store,
		Clock clock,
		@Value("${openmd.auth.refresh-token-lifetime:30d}") Duration lifetime
	) {
		if (lifetime.isZero() || lifetime.isNegative()) {
			throw new IllegalArgumentException("Refresh token lifetime must be positive");
		}
		return new RefreshTokenService(store, clock, lifetime);
	}

	@Bean
	VerificationEmailSender verificationEmailSender(
		JavaMailSender mailSender,
		@Value("${openmd.mail.from}") String from,
		@Value("${spring.mail.host}") String host
	) {
		if (from == null || from.isBlank()) {
			throw new IllegalArgumentException("OpenMD mail sender address must be configured");
		}
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("SMTP host must be configured");
		}
		return new SpringMailVerificationEmailSender(mailSender, from);
	}

	@Bean
	AuthService authService(
		UserRepository users,
		PasswordEncoder passwordEncoder,
		RefreshTokenService refreshTokens,
		AccessTokenService accessTokens
	) {
		return new AuthService(users, passwordEncoder, refreshTokens, accessTokens);
	}

	@Bean
	TwoStepSignUpService twoStepSignUpService(
		UserRepository users,
		PasswordEncoder passwordEncoder,
		VerificationCodeGenerator generator,
		VerificationCodeDigest digest,
		EmailVerificationStore verifications,
		SignUpCredentialStore credentials,
		VerificationEmailSender emailSender,
		RefreshTokenService refreshTokens,
		AccessTokenService accessTokens,
		Clock clock,
		PlatformTransactionManager transactionManager
	) {
		return new TwoStepSignUpService(users, passwordEncoder, generator, digest, verifications, credentials,
			emailSender, refreshTokens, accessTokens, clock, new TransactionTemplate(transactionManager));
	}
}
