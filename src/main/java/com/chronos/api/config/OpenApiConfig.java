package com.chronos.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Chronos Temporal State Reconstruction Engine API")
                .description("Production-oriented REST API for Event-Sourced Banking Core, Temporal Queries, CQRS Projections, and RBAC")
                .version("1.0.0")
                .license(new License().name("Apache 2.0").url("https://springdoc.org"))
                .contact(new Contact().name("Chronos Engineering Team"))
            )
            .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH_SCHEME))
            .components(new Components()
                .addSecuritySchemes(BEARER_AUTH_SCHEME, new SecurityScheme()
                    .name(BEARER_AUTH_SCHEME)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Enter JWT Bearer token obtained from POST /api/v1/auth/login")
                )
            );
    }
}
