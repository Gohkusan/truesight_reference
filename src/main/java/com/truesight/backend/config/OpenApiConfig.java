package com.truesight.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Bearer-token scheme so Swagger UI shows an "Authorize" button that
 * actually works — without this, every protected endpoint would need its token pasted
 * into an Authorization header manually per request from the docs UI.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI trueSightOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("TrueSight API")
                        .version("v1")
                        .description("Supply-chain risk analysis for equity portfolios. "
                                + "Every endpoint except /api/auth/** requires a Bearer token "
                                + "obtained from /api/auth/login, and is scoped to the "
                                + "authenticated account (AC 1.3): a resource belonging to "
                                + "another account returns 404, never 403."))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
