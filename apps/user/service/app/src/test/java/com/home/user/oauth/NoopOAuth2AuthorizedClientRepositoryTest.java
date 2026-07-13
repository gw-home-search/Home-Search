package com.home.user.oauth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
class NoopOAuth2AuthorizedClientRepositoryTest{
 @Test void neverPersistsProviderTokens(){var repository=new NoopOAuth2AuthorizedClientRepository();var request=new org.springframework.mock.web.MockHttpServletRequest();var response=new org.springframework.mock.web.MockHttpServletResponse();var principal=mock(Authentication.class);var client=mock(OAuth2AuthorizedClient.class);repository.saveAuthorizedClient(client,principal,request,response);OAuth2AuthorizedClient loaded=repository.loadAuthorizedClient("google",principal,request);assertThat(loaded).isNull();}
}
