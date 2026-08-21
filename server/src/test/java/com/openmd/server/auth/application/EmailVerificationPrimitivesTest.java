package com.openmd.server.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class EmailVerificationPrimitivesTest {

	@Test
	void generatesSixCharactersOnlyFromTheApprovedAlphabet() {
		VerificationCodeGenerator generator = new VerificationCodeGenerator(new SecureRandom());

		for (int i = 0; i < 100; i++) {
			String code = generator.generate();
			assertEquals(6, code.length());
			assertTrue(code.matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}"));
		}
	}

	@Test
	void createsAKeyedEmailAndPurposeSeparatedDigest() {
		VerificationCodeDigest digest = new VerificationCodeDigest("0123456789abcdef0123456789abcdef".getBytes());
		String firstEmailKey = digest.emailKey("learner@example.com");
		String secondEmailKey = digest.emailKey("other@example.com");

		assertEquals(digest.create(firstEmailKey, "A7K9M2"), digest.create(firstEmailKey, "A7K9M2"));
		assertNotEquals(digest.create(firstEmailKey, "A7K9M2"), digest.create(secondEmailKey, "A7K9M2"));
		assertNotEquals("learner@example.com", firstEmailKey);
		assertNotEquals("A7K9M2", digest.create(firstEmailKey, "A7K9M2"));
	}

	@Test
	void signUpTokenDigestNeverContainsTheUuidCredential() {
		String token = "61d67fa8-1a2b-4f35-94fc-16ec63551b15";

		String digest = SignUpTokenDigest.create(token);

		assertEquals(64, digest.length());
		assertNotEquals(token, digest);
		assertTrue(digest.matches("[0-9a-f]{64}"));
	}
}
