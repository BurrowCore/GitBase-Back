package org.os.gitbase.config;

import org.os.gitbase.google.GoogleTokenVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

@Configuration
public class OAuth2Config {

    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository) {
        return new org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService(
                clientRegistrationRepository);
    }

    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository();
    }
    
    @Bean
    public GoogleTokenVerifier googleTokenVerifier() {
        return new GoogleTokenVerifier();
    }
} 