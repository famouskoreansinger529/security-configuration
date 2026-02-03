package com.cesintern.securityconfiguration.service;


import com.cesintern.securityconfiguration.config.properties.GoogleOAuthProperties;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleOAuthService {

    private final GoogleOAuthProperties googleOAuthProperties;

    private static final String TOKEN_SERVER_URL = "https://oauth2.googleapis.com/token";

    /**
     * Exchange authorization code for tokens and get user info
     */
    public GoogleUserInfo exchangeCodeForUserInfo(String code, String redirectUri) throws IOException {
        try {
            // Exchange code for tokens
            GoogleTokenResponse tokenResponse = new GoogleAuthorizationCodeTokenRequest(
                    new NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    TOKEN_SERVER_URL,
                    googleOAuthProperties.getClientId(),
                    googleOAuthProperties.getClientSecret(),
                    code,
                    redirectUri
            ).execute();

            // Get ID token which contains user info
            GoogleIdToken idToken = tokenResponse.parseIdToken();
            GoogleIdToken.Payload payload = idToken.getPayload();

            return GoogleUserInfo.builder()
                    .email(payload.getEmail())
                    .name((String) payload.get("name"))
                    .pictureUrl((String) payload.get("picture"))
                    .emailVerified(payload.getEmailVerified())
                    .build();

        } catch (IOException e) {
            log.error("Error exchanging code for user info: {}", e.getMessage());
            throw e;
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GoogleUserInfo {
        private String email;
        private String name;
        private String pictureUrl;
        private Boolean emailVerified;
    }
}

