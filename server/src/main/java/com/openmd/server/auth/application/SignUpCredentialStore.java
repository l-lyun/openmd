package com.openmd.server.auth.application;

import java.time.Duration;
import java.util.Optional;

public interface SignUpCredentialStore {

	void save(String tokenDigest, SignUpCredential credential, Duration ttl);

	Optional<SignUpCredential> find(String tokenDigest);

	void consume(String tokenDigest);
}
