package com.cesintern.securityconfiguration;

import com.cesintern.securityconfiguration.config.properties.GoogleOAuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@RequiredArgsConstructor
public class SecurityConfigurationApplication implements CommandLineRunner {

    private final GoogleOAuthProperties props;

    public static void main(String[] args) {
        SpringApplication.run(SecurityConfigurationApplication.class, args);
    }

    @Override
    public void run(String... args) {
        System.out.println(props.getClientId());
        System.out.println(props.getClientSecret());
    }
}

