package com.openmd.server.auth.security;

import com.openmd.server.auth.domain.BrowserAuthErrorCode;
import com.openmd.server.global.api.ApiError;
import com.openmd.server.global.api.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public final class BrowserSessionRequestGuard extends OncePerRequestFilter {

	public static final String CSRF_HEADER = "X-OpenMD-CSRF";
	private static final String CSRF_VALUE = "1";
	private static final String LOGIN_PATH = "/api/v1/auth/web/sessions";
	private static final String SIGN_UP_PATH = "/api/v1/auth/web/sign-ups";
	private static final String REFRESH_PATH = "/api/v1/auth/web/sessions/refresh";
	private static final String LOGOUT_PATH = "/api/v1/auth/web/sessions/current";

	private final Set<String> allowedOrigins;
	private final ObjectMapper mapper;

	public BrowserSessionRequestGuard(List<String> allowedOrigins, ObjectMapper mapper) {
		this.allowedOrigins = Set.copyOf(allowedOrigins);
		this.mapper = mapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String method = request.getMethod();
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !("POST".equals(method) && (SIGN_UP_PATH.equals(path) || LOGIN_PATH.equals(path) || REFRESH_PATH.equals(path)))
			&& !("DELETE".equals(method) && LOGOUT_PATH.equals(path));
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		String csrf = request.getHeader(CSRF_HEADER);
		if (origin == null || !allowedOrigins.contains(origin) || !CSRF_VALUE.equals(csrf)) {
			if (origin != null && allowedOrigins.contains(origin)) {
				response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
				response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
				response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
			}
			BrowserAuthErrorCode errorCode = BrowserAuthErrorCode.CSRF_REJECTED;
			response.setStatus(errorCode.status().value());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding("UTF-8");
			mapper.writeValue(response.getOutputStream(), ApiResponse.failure(
				ApiError.of(errorCode.code(), errorCode.message())
			));
			return;
		}
		filterChain.doFilter(request, response);
	}
}
