package com.openmd.server.auth.api;

import com.openmd.server.auth.application.AuthService;
import com.openmd.server.auth.application.SessionTokens;
import com.openmd.server.auth.application.TwoStepSignUpService;
import com.openmd.server.auth.domain.AuthErrorCode;
import com.openmd.server.auth.security.BrowserSessionRequestGuard;
import com.openmd.server.global.api.ApiError;
import com.openmd.server.global.api.ApiResponse;
import com.openmd.server.global.error.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/web")
@ConditionalOnProperty(name = "openmd.auth.enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Browser Authentication", description = "HttpOnly Cookie 기반 브라우저 세션 관리")
public class BrowserAuthController {

	private static final String BROWSER_REFRESH_COOKIE = "browserRefreshCookie";
	private final AuthService authService;
	private final TwoStepSignUpService signUpService;
	private final BrowserRefreshCookie refreshCookie;

	public BrowserAuthController(
		AuthService authService,
		TwoStepSignUpService signUpService,
		BrowserRefreshCookie refreshCookie
	) {
		this.authService = authService;
		this.signUpService = signUpService;
		this.refreshCookie = refreshCookie;
	}

	@PostMapping("/sign-ups")
	@Operation(
		operationId = "completeBrowserSignUp",
		summary = "브라우저 회원가입을 완료하고 세션을 발급한다",
		responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "201", description = "가입 완료", useReturnTypeSchema = true
		)
	)
	@Parameter(
		name = BrowserSessionRequestGuard.CSRF_HEADER,
		description = "브라우저 인증 요청의 preflight를 강제하는 고정값",
		in = ParameterIn.HEADER,
		required = true,
		example = "1",
		schema = @Schema(allowableValues = "1")
	)
	public ResponseEntity<ApiResponse<BrowserSessionTokens>> signUp(
		@Valid @RequestBody SignUpRequest request
	) {
		return browserSessionResponse(signUpService.completeSignUp(request.toCommand()), HttpStatus.CREATED);
	}

	@PostMapping("/sessions")
	@Operation(operationId = "createBrowserSession", summary = "브라우저 세션으로 로그인한다")
	@Parameter(
		name = BrowserSessionRequestGuard.CSRF_HEADER,
		description = "브라우저 인증 요청의 preflight를 강제하는 고정값",
		in = ParameterIn.HEADER,
		required = true,
		example = "1",
		schema = @Schema(allowableValues = "1")
	)
	public ResponseEntity<ApiResponse<BrowserSessionTokens>> login(
		@Valid @RequestBody LoginRequest request
	) {
		SessionTokens tokens = authService.login(request.email(), request.password());
		return browserSessionResponse(tokens, HttpStatus.OK);
	}

	@PostMapping("/sessions/refresh")
	@Operation(
		operationId = "refreshBrowserSession",
		summary = "브라우저 세션 토큰을 회전한다",
		security = @SecurityRequirement(name = BROWSER_REFRESH_COOKIE)
	)
	@Parameter(
		name = BrowserSessionRequestGuard.CSRF_HEADER,
		description = "브라우저 인증 요청의 preflight를 강제하는 고정값",
		in = ParameterIn.HEADER,
		required = true,
		example = "1",
		schema = @Schema(allowableValues = "1")
	)
	public ResponseEntity<ApiResponse<BrowserSessionTokens>> refresh(HttpServletRequest request) {
		String token = refreshCookie.read(request);
		if (token == null) {
			return invalidRefreshResponse();
		}
		try {
			return browserSessionResponse(authService.refresh(token), HttpStatus.OK);
		} catch (BusinessException exception) {
			if (exception.getErrorCode() == AuthErrorCode.INVALID_CREDENTIAL) {
				return invalidRefreshResponse();
			}
			throw exception;
		}
	}

	@DeleteMapping("/sessions/current")
	@Operation(
		operationId = "deleteCurrentBrowserSession",
		summary = "현재 브라우저 세션에서 로그아웃한다",
		security = @SecurityRequirement(name = BROWSER_REFRESH_COOKIE)
	)
	@Parameter(
		name = BrowserSessionRequestGuard.CSRF_HEADER,
		description = "브라우저 인증 요청의 preflight를 강제하는 고정값",
		in = ParameterIn.HEADER,
		required = true,
		example = "1",
		schema = @Schema(allowableValues = "1")
	)
	public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
		String token = refreshCookie.read(request);
		try {
			if (token != null) {
				authService.logout(token);
			}
			return ApiResponse.successWithoutData();
		} finally {
			response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.expire().toString());
		}
	}

	private ResponseEntity<ApiResponse<BrowserSessionTokens>> browserSessionResponse(
		SessionTokens tokens,
		HttpStatus status
	) {
		BrowserSessionTokens body = new BrowserSessionTokens(
			tokens.accessToken(),
			tokens.accessExpiresAt(),
			tokens.refreshExpiresAt()
		);
		return ResponseEntity.status(status)
			.header(HttpHeaders.SET_COOKIE, refreshCookie.issue(tokens.refreshToken(), tokens.refreshExpiresAt()).toString())
			.body(ApiResponse.success(body));
	}

	private ResponseEntity<ApiResponse<BrowserSessionTokens>> invalidRefreshResponse() {
		AuthErrorCode errorCode = AuthErrorCode.INVALID_CREDENTIAL;
		ApiResponse<BrowserSessionTokens> body = new ApiResponse<>(
			false,
			null,
			ApiError.of(errorCode.code(), errorCode.message())
		);
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.header(HttpHeaders.SET_COOKIE, refreshCookie.expire().toString())
			.body(body);
	}
}
