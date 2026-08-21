package com.openmd.server.auth.config;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openmd.server.auth.api.BrowserAuthController;
import com.openmd.server.auth.api.BrowserRefreshCookie;
import com.openmd.server.auth.application.AuthService;
import com.openmd.server.auth.application.SessionTokens;
import com.openmd.server.auth.application.TwoStepSignUpService;
import com.openmd.server.auth.security.AccessTokenService;
import com.openmd.server.global.error.GlobalExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
	classes = BrowserSessionSecurityTest.TestApplication.class,
	properties = {
		"springdoc.api-docs.enabled=false",
		"openmd.cors.allowed-origins=http://localhost:5173",
		"openmd.auth.browser.allowed-origins=http://localhost:5173",
		"spring.autoconfigure.exclude="
			+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
			+ "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
			+ "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
			+ "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
	}
)
@AutoConfigureMockMvc
class BrowserSessionSecurityTest {

	@Autowired MockMvc mockMvc;
	@MockitoBean AuthService authService;
	@MockitoBean TwoStepSignUpService signUpService;
	@MockitoBean AccessTokenService accessTokenService;

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({BrowserAuthController.class, SecurityConfiguration.class, GlobalExceptionHandler.class})
	static class TestApplication {

		@Bean
		BrowserRefreshCookie browserRefreshCookie() {
			return new BrowserRefreshCookie(
				"openmd_refresh",
				false,
				"Lax",
				"/",
				Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC)
			);
		}
	}

	@Test
	void rejectsMissingCsrfHeaderBeforeLoginServiceIsCalled() throws Exception {
		mockMvc.perform(post("/api/v1/auth/web/sessions")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validLogin()))
			.andExpect(status().isForbidden())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
			.andExpect(jsonPath("$.error.code").value("AUTH_009"));

		verifyNoInteractions(authService);
	}

	@Test
	void rejectsMissingCsrfHeaderBeforeBrowserSignUpServiceIsCalled() throws Exception {
		mockMvc.perform(post("/api/v1/auth/web/sign-ups")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validSignUp()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("AUTH_009"));

		verifyNoInteractions(signUpService);
	}

	@Test
	void rejectsMissingCsrfHeaderWhenApplicationRunsUnderAContextPath() throws Exception {
		mockMvc.perform(post("/openmd/api/v1/auth/web/sessions")
				.contextPath("/openmd")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validLogin()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("AUTH_009"));

		verifyNoInteractions(authService);
	}

	@Test
	void exposesInvalidCsrfRejectionToTheAllowedBrowserOrigin() throws Exception {
		mockMvc.perform(post("/api/v1/auth/web/sessions")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.header("X-OpenMD-CSRF", "wrong")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validLogin()))
			.andExpect(status().isForbidden())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
			.andExpect(jsonPath("$.error.code").value("AUTH_009"));

		verifyNoInteractions(authService);
	}

	@Test
	void rejectsMissingOriginBeforeLoginServiceIsCalled() throws Exception {
		mockMvc.perform(post("/api/v1/auth/web/sessions")
				.header("X-OpenMD-CSRF", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validLogin()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("AUTH_009"));

		verifyNoInteractions(authService);
	}

	@Test
	void rejectsUnapprovedOriginBeforeLoginServiceIsCalled() throws Exception {
		mockMvc.perform(post("/api/v1/auth/web/sessions")
				.header(HttpHeaders.ORIGIN, "https://attacker.example")
				.header("X-OpenMD-CSRF", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validLogin()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("AUTH_009"));

		verifyNoInteractions(authService);
	}

	@Test
	void allowsExactOriginAndFixedCsrfHeader() throws Exception {
		when(authService.login("learner@example.com", "password1"))
			.thenReturn(new SessionTokens(
				"access-token",
				Instant.parse("2026-08-20T00:05:00Z"),
				"refresh-token",
				Instant.parse("2026-09-19T00:00:00Z")
			));

		mockMvc.perform(post("/api/v1/auth/web/sessions")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.header("X-OpenMD-CSRF", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validLogin()))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
			.andExpect(jsonPath("$.data.refreshToken").doesNotExist());
	}

	@Test
	void allowsBrowserSignUpOnlyWithExactOriginAndFixedCsrfHeader() throws Exception {
		when(signUpService.completeSignUp(org.mockito.ArgumentMatchers.any()))
			.thenReturn(new SessionTokens(
				"access-token",
				Instant.parse("2026-08-20T00:05:00Z"),
				"refresh-token",
				Instant.parse("2026-09-19T00:00:00Z")
			));

		mockMvc.perform(post("/api/v1/auth/web/sign-ups")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.header("X-OpenMD-CSRF", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validSignUp()))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("HttpOnly")))
			.andExpect(jsonPath("$.data.accessToken").value("access-token"))
			.andExpect(jsonPath("$.data.refreshToken").doesNotExist());
	}

	private String validLogin() {
		return """
			{"email":"learner@example.com","password":"password1"}
			""";
	}

	private String validSignUp() {
		return """
			{
			  "signUpToken":"61d67fa8-1a2b-4f35-94fc-16ec63551b15",
			  "password":"password1",
			  "nickname":"공부왕7",
			  "agreements":[
			    {"termsId":"SERVICE_TERMS","version":"TEMP-2026-08-20"},
			    {"termsId":"PRIVACY_COLLECTION","version":"TEMP-2026-08-20"}
			  ]
			}
			""";
	}
}
