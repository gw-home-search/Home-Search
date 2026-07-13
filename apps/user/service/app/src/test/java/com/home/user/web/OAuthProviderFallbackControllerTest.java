package com.home.user.web;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class OAuthProviderFallbackControllerTest{
 @Test void returnsProblemForUnsupportedProvider()throws Exception{MockMvcBuilders.standaloneSetup(new OAuthProviderFallbackController()).build().perform(get("/oauth2/authorization/unknown")).andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json")).andExpect(jsonPath("$.type").value("/docs/index.html#error-code-list")).andExpect(jsonPath("$.title").value("지원하지 않는 OAuth provider입니다")).andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.detail").isNotEmpty()).andExpect(jsonPath("$.code").value("OAUTH_PROVIDER_NOT_SUPPORTED")).andExpect(jsonPath("$.exception").value("OAuthProviderNotSupportedException")).andExpect(jsonPath("$.timestamp").isNotEmpty());}
}
