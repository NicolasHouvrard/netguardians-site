package com.logicielpourtous.site.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "Logiciel Pour Tous — API Mobile",
        version = "1.0",
        description = "API REST pour l'application mobile des techniciens. " +
                      "Authentification via JWT Bearer token — obtenez un token sur POST /api/v1/mobile/auth/login.",
        contact = @Contact(name = "Logiciel Pour Tous", email = "nicolas@logicielpourtous.com")
    ),
    servers = @Server(url = "/", description = "Serveur courant"),
    security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description = "Collez le token JWT obtenu via POST /api/v1/mobile/auth/login"
)
public class OpenApiConfig {}
