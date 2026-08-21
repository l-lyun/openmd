package com.openmd.server.auth.domain;

import com.openmd.server.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuthErrorCode implements ErrorCode {

	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "AUTH_001", "이메일 또는 비밀번호를 확인해 주세요."),
	EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "AUTH_002", "이메일 인증을 완료해 주세요."),
	INVALID_VERIFICATION_CODE(HttpStatus.BAD_REQUEST, "AUTH_003", "인증 코드를 확인해 주세요."),
	EXPIRED_VERIFICATION_CODE(HttpStatus.GONE, "AUTH_004", "인증 코드가 만료되었거나 사용할 수 없습니다."),
	INVALID_CREDENTIAL(HttpStatus.UNAUTHORIZED, "AUTH_005", "인증 정보가 유효하지 않습니다."),
	ACCOUNT_UNAVAILABLE(HttpStatus.FORBIDDEN, "AUTH_006", "이 계정으로 로그인할 수 없습니다."),
	TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "AUTH_007", "잠시 후 다시 시도해 주세요."),
	EMAIL_DELIVERY_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_008", "인증 메일을 보낼 수 없습니다."),
	NICKNAME_CONFLICT(HttpStatus.CONFLICT, "AUTH_010", "이미 사용 중인 닉네임입니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;

	AuthErrorCode(HttpStatus status, String code, String message) {
		this.status = status;
		this.code = code;
		this.message = message;
	}

	@Override public HttpStatus status() { return status; }
	@Override public String code() { return code; }
	@Override public String message() { return message; }
}
