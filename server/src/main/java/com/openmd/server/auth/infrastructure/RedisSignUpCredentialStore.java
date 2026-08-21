package com.openmd.server.auth.infrastructure;

import com.openmd.server.auth.application.SignUpCredential;
import com.openmd.server.auth.application.SignUpCredentialStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisSignUpCredentialStore implements SignUpCredentialStore {
	static final DefaultRedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
		redis.call('HSET', KEYS[1],
		  'displayEmail', ARGV[1],
		  'normalizedEmail', ARGV[2],
		  'verifiedAt', ARGV[3])
		redis.call('PEXPIRE', KEYS[1], ARGV[4])
		return 1
		""", Long.class);

	private final StringRedisTemplate redisTemplate;

	public RedisSignUpCredentialStore(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	public void save(String tokenDigest, SignUpCredential credential, Duration ttl) {
		Long saved = redisTemplate.execute(
			SAVE_SCRIPT,
			List.of(key(tokenDigest)),
			credential.displayEmail(),
			credential.normalizedEmail(),
			credential.verifiedAt().toString(),
			Long.toString(ttl.toMillis())
		);
		if (saved == null || saved != 1L) {
			throw new IllegalStateException("Unable to store sign-up credential");
		}
	}

	@Override
	public Optional<SignUpCredential> find(String tokenDigest) {
		Map<Object, Object> fields = redisTemplate.opsForHash().entries(key(tokenDigest));
		if (fields.isEmpty()) {
			return Optional.empty();
		}
		Object displayEmail = fields.get("displayEmail");
		Object normalizedEmail = fields.get("normalizedEmail");
		Object verifiedAt = fields.get("verifiedAt");
		if (displayEmail == null || normalizedEmail == null || verifiedAt == null) {
			return Optional.empty();
		}
		return Optional.of(new SignUpCredential(
			displayEmail.toString(),
			normalizedEmail.toString(),
			Instant.parse(verifiedAt.toString())
		));
	}

	@Override
	public void consume(String tokenDigest) {
		redisTemplate.delete(key(tokenDigest));
	}

	static String key(String tokenDigest) {
		return "auth:sign-up:credential:{" + tokenDigest + "}";
	}
}
