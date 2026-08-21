package com.openmd.server.auth.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openmd.server.auth.application.AuthService;
import com.openmd.server.auth.application.SessionTokens;
import com.openmd.server.auth.application.TwoStepSignUpService;
import com.openmd.server.auth.domain.AuthErrorCode;
import com.openmd.server.global.error.BusinessException;
import com.openmd.server.global.error.GlobalExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BrowserAuthControllerTest {

	private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
	private static final Instant ACCESS_EXPIRES_AT = Instant.parse("2026-08-20T00:05:00Z");
	private static final Instant REFRESH_EXPIRES_AT = Instant.parse("2026-09-19T00:00:00Z");
	private final AuthService authService = org.mockito.Mockito.mock(AuthService.class);
	private final TwoStepSignUpService signUpService = org.mockito.Mockito.mock(TwoStepSignUpService.class);
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		BrowserRefreshCookie refreshCookie = new BrowserRefreshCookie(
			"openmd_refresh",
			false,
			"Lax",
			"/",
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		mockMvc = MockMvcBuilders.standaloneSetup(new BrowserAuthController(authService, signUpService, refreshCookie))
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void signUpReturnsCreatedAccessMetadataAndRefreshTokenOnlyAsHttpOnlyCookie() throws Exception {
		when(signUpService.completeSignUp(org.mockito.ArgumentMatchers.any()))
			.thenReturn(tokens("access-token", "refresh-token"));

		mockMvc.perform(post("/api/v1/auth/web/sign-ups")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "signUpToken":"61d67fa8-1a2b-4f35-94fc-16ec63551b15",
					  "password":"password1",
					  "nickname":"공부왕7",
					  "agreements":[
					    {"termsId":"SERVICE_TERMS","version":"TEMP-2026-08-20"},
					    {"termsId":"PRIVACY_COLLECTION","version":"TEMP-2026-08-20"}
					  ]
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.accessToken").value("access-token"))
			.andExpect(jsonPath("$.data.refreshToken").doesNotExist())
			.andExpect(content().string(not(containsString("refresh-token"))))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("openmd_refresh=refresh-token")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")));
	}

	@Test
	void loginReturnsAccessMetadataAndRefreshTokenOnlyAsHttpOnlyCookie() throws Exception {
		when(authService.login("learner@example.com", "password1"))
			.thenReturn(tokens("access-token", "refresh-token"));

		mockMvc.perform(post("/api/v1/auth/web/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"learner@example.com","password":"password1"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accessToken").value("access-token"))
			.andExpect(jsonPath("$.data.accessExpiresAt").value(ACCESS_EXPIRES_AT.toString()))
			.andExpect(jsonPath("$.data.refreshExpiresAt").value(REFRESH_EXPIRES_AT.toString()))
			.andExpect(jsonPath("$.data.refreshToken").doesNotExist())
			.andExpect(content().string(not(containsString("refresh-token"))))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("openmd_refresh=refresh-token")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=2592000")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString("Secure"))));
	}

	@Test
	void refreshReadsOnlyTheCookieAndRotatesIt() throws Exception {
		when(authService.refresh("old-refresh"))
			.thenReturn(tokens("new-access", "new-refresh"));

		mockMvc.perform(post("/api/v1/auth/web/sessions/refresh")
				.cookie(new MockCookie("openmd_refresh", "old-refresh")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accessToken").value("new-access"))
			.andExpect(jsonPath("$.data.refreshToken").doesNotExist())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("openmd_refresh=new-refresh")));

		verify(authService).refresh("old-refresh");
	}

	@Test
	void missingRefreshCookieReturnsAuth005AndExpiresTheCookieWithoutCallingService() throws Exception {
		mockMvc.perform(post("/api/v1/auth/web/sessions/refresh"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("AUTH_005"))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("openmd_refresh=")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

		verifyNoInteractions(authService);
	}

	@Test
	void definitiveRefreshRejectionExpiresTheCookie() throws Exception {
		when(authService.refresh("invalid-refresh"))
			.thenThrow(new BusinessException(AuthErrorCode.INVALID_CREDENTIAL));

		mockMvc.perform(post("/api/v1/auth/web/sessions/refresh")
				.cookie(new MockCookie("openmd_refresh", "invalid-refresh")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("AUTH_005"))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
	}

	@Test
	void transientRefreshFailureKeepsTheCookieUntouched() throws Exception {
		when(authService.refresh("current-refresh"))
			.thenThrow(new IllegalStateException("Redis unavailable"));

		mockMvc.perform(post("/api/v1/auth/web/sessions/refresh")
				.cookie(new MockCookie("openmd_refresh", "current-refresh")))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error.code").value("COMMON_999"))
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
	}

	@Test
	void logoutWithoutCookieIsIdempotentAndStillExpiresTheCookie() throws Exception {
		mockMvc.perform(delete("/api/v1/auth/web/sessions/current"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

		verifyNoInteractions(authService);
	}

	@Test
	void logoutRevokesCookieSessionAndAlwaysExpiresBrowserCookie() throws Exception {
		mockMvc.perform(delete("/api/v1/auth/web/sessions/current")
				.cookie(new MockCookie("openmd_refresh", "current-refresh")))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

		verify(authService).logout("current-refresh");
	}

	@Test
	void logoutFailureStillExpiresTheLocalCookie() throws Exception {
		doThrow(new IllegalStateException("Redis unavailable"))
			.when(authService).logout("current-refresh");

		mockMvc.perform(delete("/api/v1/auth/web/sessions/current")
				.cookie(new MockCookie("openmd_refresh", "current-refresh")))
			.andExpect(status().isInternalServerError())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
	}

	private SessionTokens tokens(String accessToken, String refreshToken) {
		return new SessionTokens(accessToken, ACCESS_EXPIRES_AT, refreshToken, REFRESH_EXPIRES_AT);
	}
}
