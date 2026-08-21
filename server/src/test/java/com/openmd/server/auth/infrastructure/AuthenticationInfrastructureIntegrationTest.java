package com.openmd.server.auth.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openmd.server.auth.application.EmailVerificationStore;
import com.openmd.server.auth.application.IssuedRefreshToken;
import com.openmd.server.auth.application.RefreshSessionStore;
import com.openmd.server.auth.application.RefreshTokenService;
import com.openmd.server.auth.application.SignUpCredential;
import com.openmd.server.auth.application.SignUpTokenDigest;
import com.openmd.server.auth.domain.AuthErrorCode;
import com.openmd.server.auth.domain.User;
import com.openmd.server.auth.domain.UserRepository;
import com.openmd.server.global.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

@Testcontainers
@Tag("integration")
@SpringBootTest(properties = {
	"openmd.auth.enabled=false",
	"spring.jpa.open-in-view=false"
})
class AuthenticationInfrastructureIntegrationTest {

	@Container
	static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
		.withDatabaseName("openmd")
		.withUsername("openmd")
		.withPassword("openmd")
		.withStartupTimeout(Duration.ofMinutes(2));

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort())
		.withStartupTimeout(Duration.ofMinutes(1));

	@DynamicPropertySource
	static void infrastructureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired JdbcTemplate jdbcTemplate;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redisTemplate;

	@BeforeEach
	void clearRedis() {
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});
	}

	@Test
	void appliesFlywayMigrationAndEnforcesHibernateUniqueAndCheckContractsOnMySql84() {
		Integer migrationSucceeded = jdbcTemplate.queryForObject(
			"SELECT success FROM flyway_schema_history WHERE version = '3'",
			Integer.class
		);
		assertEquals(1, migrationSucceeded);

		User persisted = userRepository.saveAndFlush(User.pending(
			"learner@example.com",
			"learner@example.com",
			"$argon2id$test-hash"
		));
		assertNotNull(persisted.getId());
		assertNotNull(persisted.getCreatedAt());

		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
			INSERT INTO users (
			  email, normalized_email, password_hash, status, created_at, updated_at
			) VALUES (?, ?, ?, 'PENDING_ACTIVATION', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
			""", "different@example.com", "learner@example.com", "$argon2id$another-hash"));

		assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
			INSERT INTO users (
			  email, normalized_email, password_hash, status, created_at, updated_at
			) VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
			""", "invalid@example.com", "invalid@example.com", "$argon2id$invalid-hash"));

		int legacyActiveRows = jdbcTemplate.update("""
			INSERT INTO users (
			  email, normalized_email, password_hash, email_verified_at,
			  status, activated_at, created_at, updated_at
			) VALUES (?, ?, ?, CURRENT_TIMESTAMP(6), 'ACTIVE', CURRENT_TIMESTAMP(6),
			  CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
			""", "legacy@example.com", "legacy@example.com", "$argon2id$legacy-hash");
		assertEquals(1, legacyActiveRows, "V3 must preserve pre-existing ACTIVE rows without fabricated consent");

		Instant now = Instant.parse("2026-08-21T00:00:00Z");
		User active = userRepository.saveAndFlush(User.active(
			"study@example.com",
			"study@example.com",
			"$argon2id$study-hash",
			"Study7",
			now.minusSeconds(30),
			"TEMP-2026-08-20",
			"TEMP-2026-08-20",
			now
		));
		assertEquals("Study7", active.getNickname());
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
			INSERT INTO users (
			  email, normalized_email, password_hash, nickname, email_verified_at,
			  service_terms_version, service_terms_agreed_at,
			  privacy_terms_version, privacy_terms_agreed_at,
			  status, activated_at, created_at, updated_at
			) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6), ?, CURRENT_TIMESTAMP(6), ?, CURRENT_TIMESTAMP(6),
			  'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
			""",
			"other-study@example.com", "other-study@example.com", "$argon2id$other-hash", "study7",
			"TEMP-2026-08-20", "TEMP-2026-08-20"
		));
	}

	@Test
	void enforcesEmailVerificationTtlCooldownReplacementAndFiveFailureInvalidationInRedis74() {
		RedisEmailVerificationStore store = new RedisEmailVerificationStore(redisTemplate);
		Instant now = Instant.now();
		Duration ttl = Duration.ofSeconds(20);
		Duration cooldown = Duration.ofSeconds(60);
		String emailKey = "email-key-42";
		String key = RedisEmailVerificationStore.key(emailKey);

		assertTrue(store.issue(emailKey, "digest-one", now, ttl, cooldown, false).issued());
		assertTtlWithin(key, ttl);
		assertEquals("digest-one", redisTemplate.opsForHash().get(key, "codeDigest"));

		EmailVerificationStore.IssueResult limited = store.issue(
			emailKey, "digest-two", now.plusSeconds(30), ttl, cooldown, true
		);
		assertFalse(limited.issued());
		assertEquals(30L, limited.retryAfterSeconds());
		assertEquals("digest-one", redisTemplate.opsForHash().get(key, "codeDigest"));

		assertTrue(store.issue(emailKey, "digest-two", now.plusSeconds(61), ttl, cooldown, true).issued());
		assertEquals("digest-two", redisTemplate.opsForHash().get(key, "codeDigest"));
		assertEquals(EmailVerificationStore.VerificationResult.MATCHED, store.verify(emailKey, "digest-two"));

		for (int attempt = 1; attempt <= 4; attempt++) {
			assertEquals(EmailVerificationStore.VerificationResult.MISMATCHED, store.verify(emailKey, "wrong"));
		}
		assertEquals(EmailVerificationStore.VerificationResult.EXPIRED, store.verify(emailKey, "wrong"));
		assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(key)));
	}

	@Test
	void cancelsOnlyTheFailedMailIssueAndImmediatelyReleasesItsCooldownInRedis74() {
		RedisEmailVerificationStore store = new RedisEmailVerificationStore(redisTemplate);
		Instant now = Instant.now();
		String emailKey = "email-key-43";
		String key = RedisEmailVerificationStore.key(emailKey);

		assertTrue(store.issue(
			emailKey, "delivered-by-newer-request", now, Duration.ofMinutes(10), Duration.ofSeconds(60), false
		).issued());
		assertFalse(store.cancelIssue(emailKey, "stale-failed-digest"));
		assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(key)));

		assertTrue(store.cancelIssue(emailKey, "delivered-by-newer-request"));
		assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(key)));
		assertTrue(store.issue(
			emailKey, "retry-digest", now.plusSeconds(1), Duration.ofMinutes(10), Duration.ofSeconds(60), true
		).issued());
	}

	@Test
	void storesSignUpCredentialAndItsFifteenMinuteTtlInOneAtomicLuaExecution() {
		RedisSignUpCredentialStore store = new RedisSignUpCredentialStore(redisTemplate);
		String rawToken = "61d67fa8-1a2b-4f35-94fc-16ec63551b15";
		String tokenDigest = SignUpTokenDigest.create(rawToken);
		Duration ttl = Duration.ofMinutes(15);
		Instant verifiedAt = Instant.parse("2026-08-21T00:00:00Z");

		store.save(tokenDigest, new SignUpCredential(
			"Learner@Example.COM", "learner@example.com", verifiedAt
		), ttl);

		String key = RedisSignUpCredentialStore.key(tokenDigest);
		assertFalse(key.contains(rawToken));
		assertTtlWithin(key, ttl);
		assertEquals("learner@example.com", redisTemplate.opsForHash().get(key, "normalizedEmail"));
		assertEquals(verifiedAt.toString(), redisTemplate.opsForHash().get(key, "verifiedAt"));
		assertFalse(redisTemplate.opsForHash().entries(key).containsValue(rawToken));
		assertEquals("learner@example.com", store.find(tokenDigest).orElseThrow().normalizedEmail());
		store.consume(tokenDigest);
		assertTrue(store.find(tokenDigest).isEmpty());
	}

	@Test
	void rotatesRefreshTokensCreatesTombstonesAndRevokesTheSessionOnReuseInRedis74() throws Exception {
		RedisRefreshSessionStore store = new RedisRefreshSessionStore(redisTemplate);
		RefreshTokenService service = new RefreshTokenService(store, Clock.systemUTC(), Duration.ofSeconds(20));
		IssuedRefreshToken first = service.issue(7L);
		String sessionKey = RedisRefreshSessionStore.sessionKey(first.sessionId());
		assertTtlWithin(sessionKey, Duration.ofSeconds(20));

		String firstSecret = first.token().split("\\.", -1)[1];
		String firstDigest = digest(firstSecret);
		String storedDigest = (String) redisTemplate.opsForHash().get(sessionKey, "currentTokenDigest");
		assertEquals(firstDigest, storedDigest);
		assertNotEquals(firstSecret, storedDigest);
		var inspected = service.inspect(first.token());
		assertEquals(7L, inspected.userId());
		assertEquals(first.sessionId(), inspected.sessionId());
		assertEquals(firstDigest, redisTemplate.opsForHash().get(sessionKey, "currentTokenDigest"));

		var rotated = service.rotate(first.token());
		String tombstoneKey = RedisRefreshSessionStore.usedKey(first.sessionId(), firstDigest);
		assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(tombstoneKey)));
		assertTtlWithin(tombstoneKey, Duration.ofSeconds(20));
		assertNotEquals(firstDigest, redisTemplate.opsForHash().get(sessionKey, "currentTokenDigest"));
		assertEquals(first.expiresAt(), rotated.refreshToken().expiresAt());

		BusinessException reused = assertThrows(BusinessException.class, () -> service.inspect(first.token()));
		assertEquals(AuthErrorCode.INVALID_CREDENTIAL, reused.getErrorCode());
		assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(sessionKey)));
		assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(tombstoneKey)));
	}

	@Test
	void allowsOnlyOneConcurrentRotationAndRevokesTheSessionAsStrictReuseDetection() throws Exception {
		RedisRefreshSessionStore store = new RedisRefreshSessionStore(redisTemplate);
		RefreshTokenService service = new RefreshTokenService(store, Clock.systemUTC(), Duration.ofSeconds(20));
		IssuedRefreshToken first = service.issue(8L);
		String sessionKey = RedisRefreshSessionStore.sessionKey(first.sessionId());
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<Boolean> firstAttempt = executor.submit(() -> rotateAfterSignal(service, first, ready, start));
			Future<Boolean> secondAttempt = executor.submit(() -> rotateAfterSignal(service, first, ready, start));
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();

			int successes = (firstAttempt.get(5, TimeUnit.SECONDS) ? 1 : 0)
				+ (secondAttempt.get(5, TimeUnit.SECONDS) ? 1 : 0);
			assertEquals(1, successes);
			assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(sessionKey)));
		} finally {
			executor.shutdownNow();
		}
	}

	private boolean rotateAfterSignal(
		RefreshTokenService service,
		IssuedRefreshToken token,
		CountDownLatch ready,
		CountDownLatch start
	) throws InterruptedException {
		ready.countDown();
		start.await();
		try {
			service.rotate(token.token());
			return true;
		} catch (BusinessException exception) {
			assertEquals(AuthErrorCode.INVALID_CREDENTIAL, exception.getErrorCode());
			return false;
		}
	}

	private void assertTtlWithin(String key, Duration expectedMaximum) {
		Long ttlMillis = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
		assertNotNull(ttlMillis);
		assertTrue(ttlMillis > 0, () -> "Expected a positive TTL for " + key + " but was " + ttlMillis);
		assertTrue(ttlMillis <= expectedMaximum.toMillis(),
			() -> "Expected TTL <= " + expectedMaximum.toMillis() + " but was " + ttlMillis);
	}

	private String digest(String secret) throws Exception {
		byte[] value = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}
}
