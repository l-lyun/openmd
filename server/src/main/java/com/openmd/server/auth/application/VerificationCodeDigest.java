package com.openmd.server.auth.application;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class VerificationCodeDigest {

	private final byte[] secret;

	public VerificationCodeDigest(byte[] secret) {
		if (secret == null || secret.length < 32) {
			throw new IllegalArgumentException("Email verification HMAC secret must be at least 32 bytes");
		}
		this.secret = secret.clone();
	}

	public String emailKey(String normalizedEmail) {
		return hmac("EMAIL_KEY:" + normalizedEmail);
	}

	public String create(String emailKey, String code) {
		return hmac("EMAIL_VERIFICATION:" + emailKey + ":" + code);
	}

	private String hmac(String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException("Unable to calculate verification digest", exception);
		}
	}
}
