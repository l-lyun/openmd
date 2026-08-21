package com.openmd.server.auth.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RedisKeyContractTest {

	@Test
	void keepsRefreshSessionAndTombstonesInTheSameClusterHashSlot() {
		String session = RedisRefreshSessionStore.sessionKey("session123");
		String used = RedisRefreshSessionStore.usedKey("session123", "digest456");

		assertEquals("session123", hashTag(session));
		assertEquals(hashTag(session), hashTag(used));
		assertFalse(session.contains("digest456"));
	}

	@Test
	void emailVerificationKeyContainsOnlyTheKeyedEmailDigest() {
		assertEquals(
			"auth:email-verification:email:{digest42}",
			RedisEmailVerificationStore.key("digest42")
		);
	}

	@Test
	void verificationAttemptsAndRefreshRotationAreEachOneAtomicLuaExecution() {
		String verificationScript = RedisEmailVerificationStore.VERIFY_SCRIPT.getScriptAsString();
		assertTrue(verificationScript.contains("HINCRBY"));
		assertTrue(verificationScript.contains("attempts >= 5"));
		assertTrue(verificationScript.contains("DEL"));

		String rotationScript = RedisRefreshSessionStore.ROTATE_SCRIPT.getScriptAsString();
		assertTrue(rotationScript.contains("EXISTS', KEYS[2]"));
		assertTrue(rotationScript.contains("currentTokenDigest"));
		assertTrue(rotationScript.contains("PEXPIREAT"));

		String inspectionScript = RedisRefreshSessionStore.INSPECT_SCRIPT.getScriptAsString();
		assertTrue(inspectionScript.contains("EXISTS', KEYS[2]"));
		assertTrue(inspectionScript.contains("currentTokenDigest"));
		assertFalse(inspectionScript.contains("HSET', KEYS[1], 'currentTokenDigest'"));

		String createScript = RedisRefreshSessionStore.CREATE_SCRIPT.getScriptAsString();
		assertTrue(createScript.contains("HSET"));
		assertTrue(createScript.contains("PEXPIREAT"));

		String cancellationScript = RedisEmailVerificationStore.CANCEL_ISSUE_SCRIPT.getScriptAsString();
		assertTrue(cancellationScript.contains("codeDigest') == ARGV[1]"));
		assertTrue(cancellationScript.contains("DEL"));

		String signUpCredentialSaveScript = RedisSignUpCredentialStore.SAVE_SCRIPT.getScriptAsString();
		assertTrue(signUpCredentialSaveScript.contains("HSET"));
		assertTrue(signUpCredentialSaveScript.contains("PEXPIRE"));
	}

	private String hashTag(String key) {
		return key.substring(key.indexOf('{') + 1, key.indexOf('}'));
	}
}
