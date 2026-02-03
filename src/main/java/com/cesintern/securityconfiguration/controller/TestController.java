package com.cesintern.securityconfiguration.controller;

import com.cesintern.securityconfiguration.config.properties.GoogleOAuthProperties;
import com.cesintern.securityconfiguration.dto.request.GoogleAuthRequest;
import com.cesintern.securityconfiguration.service.GoogleOAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {
    private final GoogleOAuthService googleOAuthService;
    private final GoogleOAuthProperties googleOAuthProperties;

    /**
     * Step 1: Visit this endpoint in your browser to initiate Google OAuth
     * Example: http://localhost:8080/api/test/google-login?redirectUri=http://localhost:8080/api/test/callback
     */
    @GetMapping("/google-login")
    public RedirectView initiateGoogleLogin(@RequestParam String redirectUri) {
        String authUrl = String.format(
                "https://accounts.google.com/o/oauth2/v2/auth?client_id=%s&redirect_uri=%s&response_type=code&scope=%s&access_type=offline",
                googleOAuthProperties.getClientId(),
                URLEncoder.encode(redirectUri, StandardCharsets.UTF_8),
                URLEncoder.encode("openid email profile", StandardCharsets.UTF_8)
        );
        return new RedirectView(authUrl);
    }

    /**
     * Step 2: Google will redirect back to this endpoint with the authorization code
     * This endpoint will display the code so you can copy it
     */
    @GetMapping("/callback")
    public String handleCallback(@RequestParam String code, @RequestParam(required = false) String error) {
        if (error != null) {
            return "Error: " + error;
        }
        return String.format(
                "<html><body>" +
                "<h2>Authorization Code Received!</h2>" +
                "<p>Copy this code:</p>" +
                "<pre style='background:#f4f4f4;padding:10px;'>%s</pre>" +
                "<p>Now you can test your POST endpoint with this code and redirectUri: http://localhost:8080/api/test/callback</p>" +
                "<hr>" +
                "<h3>Test with curl:</h3>" +
                "<pre style='background:#f4f4f4;padding:10px;'>curl -X POST http://localhost:8080/api/test/google-oauth-config \\\n" +
                "  -H \"Content-Type: application/json\" \\\n" +
                "  -d '{\"code\":\"%s\",\"redirectUri\":\"http://localhost:8080/api/test/callback\"}'</pre>" +
                "</body></html>",
                code, code
        );
    }

    /**
     * Step 3: Use this endpoint to exchange the code for user info
     */
    @PostMapping("/google-oauth-config")
    public GoogleOAuthService.GoogleUserInfo getGoogleOAuthConfig(@Valid @RequestBody GoogleAuthRequest googleAuthRequest) throws IOException {
        return googleOAuthService.exchangeCodeForUserInfo(googleAuthRequest.getCode(), googleAuthRequest.getRedirectUri());
    }

    /**
     * Helper: Get the authorization URL without redirecting
     */
    @GetMapping("/get-auth-url")
    public String getAuthUrl(@RequestParam(defaultValue = "http://localhost:8080/api/test/callback") String redirectUri) {
        return String.format(
                "https://accounts.google.com/o/oauth2/v2/auth?client_id=%s&redirect_uri=%s&response_type=code&scope=%s&access_type=offline",
                googleOAuthProperties.getClientId(),
                URLEncoder.encode(redirectUri, StandardCharsets.UTF_8),
                URLEncoder.encode("openid email profile", StandardCharsets.UTF_8)
        );
    }
}
