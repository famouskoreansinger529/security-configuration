package com.cesintern.securityconfiguration.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "google")
public class GoogleOAuthProperties {
    private String clientId;
    private String clientSecret;
}
