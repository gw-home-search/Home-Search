package com.home.user.web;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class OAuthProviderFallbackControllerTest{
 @Test void returnsProblemForUnsupportedProvider()throws Exception{MockMvcBuilders.standaloneSetup(new OAuthProviderFallbackController()).build().perform(get("/oauth2/authorization/unknown")).andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json")).andExpect(jsonPath("$.code").value("OAUTH_PROVIDER_NOT_SUPPORTED"));}
}
