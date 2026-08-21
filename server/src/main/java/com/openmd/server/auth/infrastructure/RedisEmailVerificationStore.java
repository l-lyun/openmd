package com.openmd.server.auth.infrastructure;

import com.openmd.server.auth.application.EmailVerificationStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class RedisEmailVerificationStore implements EmailVerificationStore {

	static final DefaultRedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>("""
		local resendAt = redis.call('HGET', KEYS[1], 'resendAvailableAt')
		if ARGV[5] == '1' and resendAt and tonumber(ARGV[2]) < tonumber(resendAt) then
		  return math.ceil((tonumber(resendAt) - tonumber(ARGV[2])) / 1000)
		end
		redis.call('DEL', KEYS[1])
		redis.call('HSET', KEYS[1], 'codeDigest', ARGV[1], 'attemptCount', '0',
		  'issuedAt', ARGV[2], 'resendAvailableAt', ARGV[3])
		redis.call('PEXPIRE', KEYS[1], ARGV[4])
		return 0
		""", Long.class);

	static final DefaultRedisScript<Long> VERIFY_SCRIPT = new DefaultRedisScript<>("""
		local current = redis.call('HGET', KEYS[1], 'codeDigest')
		if not current then return -2 end
		if current == ARGV[1] then return 1 end
		local attempts = tonumber(redis.call('HINCRBY', KEYS[1], 'attemptCount', 1))
		if attempts >= 5 then
		  redis.call('DEL', KEYS[1])
		  return -2
		end
		return 0
		""", Long.class);

	static final DefaultRedisScript<Long> CANCEL_ISSUE_SCRIPT = new DefaultRedisScript<>("""
		if redis.call('HGET', KEYS[1], 'codeDigest') == ARGV[1] then
		  redis.call('DEL', KEYS[1])
		  return 1
		end
		return 0
		""", Long.class);

	private final StringRedisTemplate redisTemplate;

	public RedisEmailVerificationStore(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	public IssueResult issue(
		String emailKey,
		String digest,
		Instant now,
		Duration ttl,
		Duration resendCooldown,
		boolean enforceCooldown
	) {
		long nowMillis = now.toEpochMilli();
		Long result = redisTemplate.execute(
			ISSUE_SCRIPT,
			List.of(key(emailKey)),
			digest,
			Long.toString(nowMillis),
			Long.toString(nowMillis + resendCooldown.toMillis()),
			Long.toString(ttl.toMillis()),
			enforceCooldown ? "1" : "0"
		);
		return result != null && result == 0 ? IssueResult.success() : IssueResult.limited(result == null ? 1 : result);
	}

	@Override
	public VerificationResult verify(String emailKey, String digest) {
		Long result = redisTemplate.execute(VERIFY_SCRIPT, List.of(key(emailKey)), digest);
		if (result != null && result == 1) {
			return VerificationResult.MATCHED;
		}
		if (result != null && result == 0) {
			return VerificationResult.MISMATCHED;
		}
		return VerificationResult.EXPIRED;
	}

	@Override
	public boolean cancelIssue(String emailKey, String digest) {
		Long result = redisTemplate.execute(CANCEL_ISSUE_SCRIPT, List.of(key(emailKey)), digest);
		return result != null && result == 1;
	}

	@Override
	public void consume(String emailKey) {
		redisTemplate.delete(key(emailKey));
	}

	static String key(String emailKey) {
		return "auth:email-verification:email:{" + emailKey + "}";
	}
}
