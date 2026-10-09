package com.academy.paybridge.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI payBridgeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayBridge API")
                        .version("1.0")
                        .description("""
                                Move naira between PayBridge accounts, or pay out to a Nigerian bank account.

                                How to try it: 1) POST /api/v1/auth/register, 2) POST /api/v1/auth/login and copy the token,
                                3) click Authorize and paste the token, 4) POST /api/v1/customers/me/pin,
                                5) POST /api/v1/accounts, 6) fund it with POST /api/v1/dev/accounts/{n}/fund (development only),
                                7) send money with POST /api/v1/transfers/internal or /external. Every transfer needs a unique
                                Idempotency-Key header and your PIN."""))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
