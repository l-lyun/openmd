package com.openmd.server.auth.domain;

import com.openmd.server.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
	name = "users",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_users_normalized_email", columnNames = "normalized_email"),
		@UniqueConstraint(name = "uk_users_nickname", columnNames = "nickname")
	}
)
public class User extends BaseEntity {

	@Column(nullable = false, length = 320)
	private String email;

	@Column(name = "normalized_email", nullable = false, length = 320)
	private String normalizedEmail;

	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	@Column(length = 10)
	private String nickname;

	@Column(name = "email_verified_at")
	private Instant emailVerifiedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private UserStatus status;

	@Column(name = "activated_at")
	private Instant activatedAt;

	@Column(name = "suspended_at")
	private Instant suspendedAt;

	@Column(name = "withdrawn_at")
	private Instant withdrawnAt;

	@Column(name = "service_terms_version", length = 64)
	private String serviceTermsVersion;

	@Column(name = "service_terms_agreed_at")
	private Instant serviceTermsAgreedAt;

	@Column(name = "privacy_terms_version", length = 64)
	private String privacyTermsVersion;

	@Column(name = "privacy_terms_agreed_at")
	private Instant privacyTermsAgreedAt;

	protected User() {
	}

	private User(String email, String normalizedEmail, String passwordHash) {
		this.email = email;
		this.normalizedEmail = normalizedEmail;
		this.passwordHash = passwordHash;
		this.status = UserStatus.PENDING_ACTIVATION;
	}

	public static User pending(String email, String normalizedEmail, String passwordHash) {
		return new User(email, normalizedEmail, passwordHash);
	}

	public static User active(
		String email,
		String normalizedEmail,
		String passwordHash,
		String nickname,
		Instant emailVerifiedAt,
		String serviceTermsVersion,
		String privacyTermsVersion,
		Instant now
	) {
		User user = new User(email, normalizedEmail, passwordHash);
		user.nickname = nickname;
		user.emailVerifiedAt = emailVerifiedAt;
		user.serviceTermsVersion = serviceTermsVersion;
		user.serviceTermsAgreedAt = now;
		user.privacyTermsVersion = privacyTermsVersion;
		user.privacyTermsAgreedAt = now;
		user.status = UserStatus.ACTIVE;
		user.activatedAt = now;
		return user;
	}

	public void activate(Instant now) {
		if (status == UserStatus.ACTIVE) {
			return;
		}
		if (status != UserStatus.PENDING_ACTIVATION) {
			throw new IllegalStateException("Only a pending user can be activated");
		}
		emailVerifiedAt = now;
		activatedAt = now;
		status = UserStatus.ACTIVE;
	}

	public String getEmail() { return email; }
	public String getNormalizedEmail() { return normalizedEmail; }
	public String getPasswordHash() { return passwordHash; }
	public String getNickname() { return nickname; }
	public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
	public UserStatus getStatus() { return status; }
	public Instant getActivatedAt() { return activatedAt; }
	public Instant getSuspendedAt() { return suspendedAt; }
	public Instant getWithdrawnAt() { return withdrawnAt; }
	public String getServiceTermsVersion() { return serviceTermsVersion; }
	public Instant getServiceTermsAgreedAt() { return serviceTermsAgreedAt; }
	public String getPrivacyTermsVersion() { return privacyTermsVersion; }
	public Instant getPrivacyTermsAgreedAt() { return privacyTermsAgreedAt; }
}
