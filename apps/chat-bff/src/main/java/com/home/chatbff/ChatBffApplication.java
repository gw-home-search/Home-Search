package com.home.chatbff;

import com.home.chatbff.ai.ChatbotAiProperties;
import com.home.chatbff.auth.UserJwtProperties;
import com.home.chatbff.ratelimit.ChatbotRateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootApplication
@EnableConfigurationProperties({ChatbotAiProperties.class, UserJwtProperties.class, ChatbotRateLimitProperties.class})
public class ChatBffApplication {
    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    public static void main(String[] args) {
        SpringApplication.run(ChatBffApplication.class, args);
    }
}
