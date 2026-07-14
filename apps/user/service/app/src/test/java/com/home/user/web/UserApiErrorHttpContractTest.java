package com.home.user.web;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.favorite.FavoriteService;
import com.home.user.security.AuthenticatedUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class UserApiErrorHttpContractTest {
    @Test
    void malformedPaginationUsesTheDocumentedProblemDetail() throws Exception {
        favoriteMockMvc()
                .perform(get("/api/v1/favorites?page=abc&size=20"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"))
                .andExpect(jsonPath("$.exception").value("InvalidPaginationException"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void malformedComplexIdUsesTheDocumentedProblemDetail() throws Exception {
        favoriteMockMvc()
                .perform(get("/api/v1/favorites/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INVALID_COMPLEX_ID"))
                .andExpect(jsonPath("$.exception").value("InvalidComplexIdException"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void unexpectedFailureUsesANonDisclosingProblemDetail() throws Exception {
        MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new UserApiExceptionHandler())
                .build()
                .perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/docs/index.html#error-code-list"))
                .andExpect(jsonPath("$.detail").value("An unexpected server error occurred."))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.exception").value("InternalServerException"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    private MockMvc favoriteMockMvc() {
        var controller = new FavoriteController(mock(FavoriteService.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new UserApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticatedPrincipalResolver())
                .build();
    }

    static class AuthenticatedPrincipalResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer container,
                NativeWebRequest request,
                WebDataBinderFactory binderFactory) {
            return new AuthenticatedUserPrincipal(1L);
        }
    }

    @RestController
    static class FailingController {
        @GetMapping("/test/unexpected")
        String fail() {
            throw new IllegalStateException("database password must stay private");
        }
    }
}
