package com.openmd.server.global.openapi;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openmd.server.auth.api.AuthController;
import com.openmd.server.auth.api.BrowserAuthController;
import com.openmd.server.auth.api.BrowserRefreshCookie;
import com.openmd.server.auth.api.UserController;
import com.openmd.server.auth.application.AuthService;
import com.openmd.server.auth.application.TwoStepSignUpService;
import com.openmd.server.auth.config.SecurityConfiguration;
import com.openmd.server.auth.security.AccessTokenService;
import com.openmd.server.learningmaterial.api.LearningMaterialController;
import com.openmd.server.learningmaterial.application.LearningMaterialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
	classes = OpenApiContractTest.TestApplication.class,
	properties = {
		"springdoc.api-docs.enabled=true",
		"springdoc.swagger-ui.enabled=true",
		"springdoc.paths-to-match=/api/v1/**",
		"openmd.auth.browser.cookie.name=openmd_refresh",
		"spring.autoconfigure.exclude="
			+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
			+ "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
			+ "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
			+ "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
	}
)
@AutoConfigureMockMvc
class OpenApiContractTest {

	@Autowired MockMvc mockMvc;
	@MockitoBean AuthService authService;
	@MockitoBean TwoStepSignUpService signUpService;
	@MockitoBean AccessTokenService accessTokenService;
	@MockitoBean BrowserRefreshCookie browserRefreshCookie;
	@MockitoBean LearningMaterialService learningMaterialService;

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({
		AuthController.class,
		BrowserAuthController.class,
		UserController.class,
		LearningMaterialController.class,
		SecurityConfiguration.class,
		OpenApiConfiguration.class
	})
	static class TestApplication {
	}

	@Test
	void exposesTheOpenApiContractWhenDocumentationIsEnabled() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.openapi").value("3.1.0"))
			.andExpect(jsonPath("$.info.title").value("OpenMD API"))
			.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
			.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
			.andExpect(jsonPath("$.security[0].bearerAuth").isArray())
			.andExpect(jsonPath("$.paths['/api/v1/auth/sign-ups'].post.operationId").value("completeSignUp"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/sign-ups'].post.security").isEmpty())
			.andExpect(jsonPath("$.paths['/api/v1/auth/sign-ups'].post.responses['201']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/auth/sign-ups'].post.requestBody.content"
				+ ".['application/json'].schema.$ref").value("#/components/schemas/SignUpRequest"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/email-verifications'].post.operationId")
				.value("requestEmailVerification"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/email-verifications'].post.security").isEmpty())
			.andExpect(jsonPath("$.paths['/api/v1/auth/email-verifications/confirm'].post.operationId")
				.value("confirmEmailVerification"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/email-verifications/confirm'].post.security").isEmpty())
			.andExpect(jsonPath("$.paths['/api/v1/auth/nickname-availability'].post.operationId")
				.value("checkNicknameAvailability"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/nickname-availability'].post.security").isEmpty())
			.andExpect(jsonPath("$.paths['/api/v1/auth/sessions'].post.operationId").value("createSession"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/sessions'].post.security").isEmpty())
			.andExpect(jsonPath("$.paths['/api/v1/auth/sessions'].post.responses['200'].content"
				+ ".['application/json'].schema.$ref").value("#/components/schemas/ApiResponseSessionTokens"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/sessions/refresh'].post.operationId")
				.value("refreshSession"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/sessions/refresh'].post.security").isEmpty())
			.andExpect(jsonPath("$.paths['/api/v1/auth/sessions/refresh'].post.requestBody.content"
				+ ".['application/json'].schema.$ref").value("#/components/schemas/RefreshTokenRequest"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/sessions/refresh'].post.parameters").doesNotExist())
			.andExpect(jsonPath("$.paths['/api/v1/auth/sessions/refresh'].post.responses['400'].content"
				+ ".['application/json'].schema.$ref").value("#/components/schemas/ApiResponse"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/sessions/refresh'].post.responses['401'].content"
				+ ".['application/json'].schema.$ref").value("#/components/schemas/ApiResponse"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/sessions/current'].delete.operationId")
				.value("deleteCurrentSession"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/sessions/current'].delete.security").isEmpty())
			.andExpect(jsonPath("$.paths['/api/v1/auth/sessions/current'].delete.requestBody.content"
				+ ".['application/json'].schema.$ref").value("#/components/schemas/RefreshTokenRequest"))
			.andExpect(jsonPath("$.paths['/api/v1/users/me'].get.operationId").value("getCurrentUser"))
			.andExpect(jsonPath("$.paths['/api/v1/users/me'].get.security").doesNotExist())
			.andExpect(jsonPath("$.components.schemas.CurrentUser.properties.nickname.type").value("string"))
			.andExpect(jsonPath("$.paths['/api/v1/learning-materials'].post.operationId")
				.value("createLearningMaterial"))
			.andExpect(jsonPath("$.paths['/api/v1/learning-materials'].post.security[0].bearerAuth")
				.isArray())
			.andExpect(jsonPath("$.paths['/api/v1/learning-materials'].post.parameters[0].name")
				.value("Idempotency-Key"))
			.andExpect(jsonPath("$.paths['/api/v1/learning-materials'].post.parameters[0].in")
				.value("header"))
			.andExpect(jsonPath("$.paths['/api/v1/learning-materials'].post.parameters[0].required")
				.value(true))
			.andExpect(jsonPath("$.paths['/api/v1/learning-materials'].post.requestBody.content"
				+ ".['application/json'].schema.$ref")
				.value("#/components/schemas/CreateLearningMaterialRequest"))
			.andExpect(jsonPath("$.paths['/api/v1/learning-materials'].post.responses['201'].content"
				+ ".['application/json'].schema.$ref")
				.value("#/components/schemas/ApiResponseCreatedLearningMaterial"))
			.andExpect(jsonPath("$.paths['/api/v1/learning-materials'].post.responses['400'].content"
				+ ".['application/json'].schema.$ref").value("#/components/schemas/ApiResponse"))
			.andExpect(jsonPath("$.paths['/api/v1/learning-materials'].post.responses['401'].content"
				+ ".['application/json'].schema.$ref").value("#/components/schemas/ApiResponse"))
			.andExpect(jsonPath("$.paths['/api/v1/learning-materials'].post.responses['413'].content"
				+ ".['application/json'].schema.$ref").value("#/components/schemas/ApiResponse"))
			.andExpect(jsonPath("$.paths['/api/v1/learning-materials'].post.responses['500'].content"
				+ ".['application/json'].schema.$ref").value("#/components/schemas/ApiResponse"))
			.andExpect(jsonPath("$.components.schemas.RefreshTokenRequest.properties.refreshToken.maxLength")
				.value(128))
			.andExpect(jsonPath("$.components.schemas.SessionTokens.properties.accessToken.type").value("string"))
			.andExpect(jsonPath("$.components.schemas.SessionTokens.properties.refreshToken.type").value("string"))
			.andExpect(jsonPath("$.components.securitySchemes.browserRefreshCookie.type").value("apiKey"))
			.andExpect(jsonPath("$.components.securitySchemes.browserRefreshCookie.in").value("cookie"))
			.andExpect(jsonPath("$.components.securitySchemes.browserRefreshCookie.name").value("openmd_refresh"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions'].post.operationId")
				.value("createBrowserSession"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sign-ups'].post.operationId")
				.value("completeBrowserSignUp"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sign-ups'].post.responses['201'].content"
				+ ".['application/json'].schema.$ref")
				.value("#/components/schemas/ApiResponseBrowserSessionTokens"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions'].post.responses['200'].content"
				+ ".['application/json'].schema.$ref")
				.value("#/components/schemas/ApiResponseBrowserSessionTokens"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions'].post.security").isEmpty())
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions'].post.parameters[0].name")
				.value("X-OpenMD-CSRF"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions'].post.parameters[0].in").value("header"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions'].post.parameters[0].required").value(true))
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions'].post.parameters[0].example").value("1"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions/refresh'].post.operationId")
				.value("refreshBrowserSession"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions/refresh'].post.requestBody").doesNotExist())
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions/refresh'].post.security[0]"
				+ ".browserRefreshCookie").isArray())
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions/refresh'].post.parameters[0].name")
				.value("X-OpenMD-CSRF"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions/refresh'].post.parameters[0].required")
				.value(true))
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions/current'].delete.operationId")
				.value("deleteCurrentBrowserSession"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions/current'].delete.requestBody").doesNotExist())
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions/current'].delete.security[0]"
				+ ".browserRefreshCookie").isArray())
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions/current'].delete.parameters[0].name")
				.value("X-OpenMD-CSRF"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/web/sessions/current'].delete.parameters[0].required")
				.value(true))
			.andExpect(jsonPath("$.components.schemas.BrowserSessionTokens.properties.accessToken.type")
				.value("string"))
			.andExpect(jsonPath("$.components.schemas.BrowserSessionTokens.properties.refreshExpiresAt.type")
				.value("string"))
			.andExpect(jsonPath("$.components.schemas.BrowserSessionTokens.properties.refreshToken").doesNotExist())
			.andExpect(content().string(not(containsString("eyJ"))));
	}

	@Test
	void exposesSwaggerUiWhenDocumentationIsEnabled() throws Exception {
		mockMvc.perform(get("/swagger-ui.html"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/swagger-ui/index.html"));
	}
}
