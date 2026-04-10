package com.ciberaccion.paypro.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${account.service.url}")
    private String accountServiceUrl;

    @Value("${provider.service.url}")
    private String providerServiceUrl;

    @Bean
    public WebClient accountWebClient() {
        return WebClient.builder()
                .baseUrl(accountServiceUrl)
                .build();
    }

    @Bean
    public WebClient providerWebClient() {
        return WebClient.builder()
                .baseUrl(providerServiceUrl)
                .build();
    }
}