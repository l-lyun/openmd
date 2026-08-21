package com.openmd.server.auth.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openmd.server.auth.application.AuthService;
import com.openmd.server.auth.application.IssuedSignUpToken;
import com.openmd.server.auth.application.SessionTokens;
import com.openmd.server.auth.application.TwoStepSignUpService;
import com.openmd.server.global.error.GlobalExceptionHandler;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {

	private final AuthService authService = mock(AuthService.class);
	private final TwoStepSignUpService signUpService = mock(TwoStepSignUpService.class);
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, signUpService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void completesNativeSignUpAndReturnsTheFullSession() throws Exception {
		when(signUpService.completeSignUp(org.mockito.ArgumentMatchers.any())).thenReturn(new SessionTokens(
			"access-token",
			Instant.parse("2026-08-21T00:05:00Z"),
			"refresh-token",
			Instant.parse("2026-09-20T00:00:00Z")
		));
		mockMvc.perform(post("/api/v1/auth/sign-ups")
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
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.accessToken").value("access-token"))
			.andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
			.andExpect(jsonPath("$.error").doesNotExist());
	}

	@Test
	void rejectsPasswordsOutsideTheConfirmedPolicyBeforeCallingTheService() throws Exception {
		mockMvc.perform(post("/api/v1/auth/sign-ups")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "signUpToken":"61d67fa8-1a2b-4f35-94fc-16ec63551b15",
					  "password":"onlyletters",
					  "nickname":"공부왕7",
					  "agreements":[
					    {"termsId":"SERVICE_TERMS","version":"TEMP-2026-08-20"},
					    {"termsId":"PRIVACY_COLLECTION","version":"TEMP-2026-08-20"}
					  ]
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("COMMON_001"))
			.andExpect(jsonPath("$.error.fields[0].field").value("password"));
		verifyNoInteractions(signUpService);
	}

	@Test
	void emailConfirmationReturnsTheMemoryOnlySignUpToken() throws Exception {
		when(signUpService.confirmEmail("learner@example.com", "A7K9M2"))
			.thenReturn(new IssuedSignUpToken("61d67fa8-1a2b-4f35-94fc-16ec63551b15"));

		mockMvc.perform(post("/api/v1/auth/email-verifications/confirm")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"learner@example.com","code":"A7K9M2"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.emailVerified").value(true))
			.andExpect(jsonPath("$.data.signUpToken")
				.value("61d67fa8-1a2b-4f35-94fc-16ec63551b15"))
			.andExpect(jsonPath("$.data.nextAction").value("COMPLETE_PROFILE"));
	}

	@Test
	void rejectsOversizedCredentialsBeforeHashingOrParsingThem() throws Exception {
		mockMvc.perform(post("/api/v1/auth/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"learner@example.com\",\"password\":\"" + "a".repeat(65) + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COMMON_001"));

		mockMvc.perform(post("/api/v1/auth/email-verifications/confirm")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"learner@example.com\",\"code\":\"" + "A".repeat(65) + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COMMON_001"));

		mockMvc.perform(post("/api/v1/auth/sessions/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + "A".repeat(129) + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COMMON_001"));

		verifyNoInteractions(authService, signUpService);
	}
}
