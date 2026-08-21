package com.openmd.server.global.openapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openmd.server.auth.application.AuthService;
import com.openmd.server.auth.application.TwoStepSignUpService;
import com.openmd.server.auth.api.BrowserRefreshCookie;
import com.openmd.server.auth.security.AccessTokenService;
import com.openmd.server.learningmaterial.application.LearningMaterialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
	classes = OpenApiContractTest.TestApplication.class,
	properties = {
		"springdoc.api-docs.enabled=false",
		"springdoc.swagger-ui.enabled=false",
		"spring.autoconfigure.exclude="
			+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
			+ "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
			+ "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
			+ "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
	}
)
@AutoConfigureMockMvc
class OpenApiDisabledTest {

	@Autowired MockMvc mockMvc;
	@MockitoBean AuthService authService;
	@MockitoBean TwoStepSignUpService signUpService;
	@MockitoBean AccessTokenService accessTokenService;
	@MockitoBean BrowserRefreshCookie browserRefreshCookie;
	@MockitoBean LearningMaterialService learningMaterialService;

	@Test
	void doesNotExposeApiDocumentationWhenDocumentationIsDisabled() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/v3/api-docs.yaml"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/swagger-ui.html"))
			.andExpect(status().isUnauthorized());
	}
}
