package com.habitflow.habits.client;

import com.habitflow.habits.dto.UserValidationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class AuthServiceClient {
    
    private final RestTemplate restTemplate;
    private final String authServiceUrl;
    
    public AuthServiceClient(@Value("${auth-service.url}") String authServiceUrl) {
        this.restTemplate = new RestTemplate();
        this.authServiceUrl = authServiceUrl;
    }
    
    public UserValidationResponse validateToken(String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token);
            
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            
            ResponseEntity<UserValidationResponse> response = restTemplate.exchange(
                authServiceUrl + "/api/auth/validate",
                HttpMethod.GET,
                entity,
                UserValidationResponse.class
            );
            
            return response.getBody();
        } catch (Exception e) {
            return UserValidationResponse.builder()
                    .valid(false)
                    .build();
        }
    }
}