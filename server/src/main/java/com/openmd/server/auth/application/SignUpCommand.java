package com.openmd.server.auth.application;

import java.util.List;

public record SignUpCommand(
	String signUpToken,
	String password,
	String nickname,
	List<TermsAgreement> agreements
) {
}
