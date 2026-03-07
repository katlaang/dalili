package dalili.com.base.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI/Swagger configuration for the Dalili Health API documentation.
 *
 * <p>This configuration provides:
 * <ul>
 *   <li>API metadata (title, version, description)</li>
 *   <li>JWT Bearer token authentication scheme</li>
 *   <li>Server configuration for different environments</li>
 * </ul>
 * </p>
 * local swagger UI access: http://localhost:8080/swagger-ui.html
 * <p>Access Swagger UI at: /swagger-ui.html</p>
 * <p>Access OpenAPI spec at: /v3/api-docs</p>
 */
@Configuration
public class OpenApiConfig {

    /**
     * Configures the OpenAPI documentation.
     *
     * @return OpenAPI configuration
     */
    @Bean
    public OpenAPI daliliHealthOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Dalili Health API")
                        .version("1.0.0")
                        .description("""
                                Dalili Health is a triage-led clinical decision support and healthcare 
                                operations platform designed for resource-variable health systems.
                                
                                ## Key Features
                                
                                - **Queue Management**: Patient check-in, priority-based queuing, and workflow tracking
                                - **Triage Assessment**: Vital signs recording, red flag identification, and acuity classification
                                - **Clinical Decision Support**: Evidence-based triage calculations with nurse override capability
                                - **Audit Trail**: Complete audit logging for regulatory compliance
                                
                                ## Authentication
                                
                                All API endpoints (except public authentication endpoints) require JWT Bearer token authentication.
                                Obtain a token by calling `/api/auth/staff/login` or `/api/auth/patient/login`.
                                
                                ## Triage Levels (Manchester Triage System)
                                
                                | Level | Color | Name | Target Wait |
                                |-------|-------|------|-------------|
                                | 1 | RED | Immediate | 0 min |
                                | 2 | ORANGE | Very Urgent | 10 min |
                                | 3 | YELLOW | Urgent | 60 min |
                                | 4 | GREEN | Standard | 120 min |
                                | 5 | BLUE | Non-Urgent | 240 min |
                                """)
                        .contact(new Contact()
                                .name("Dalili Health Support")
                                .email("support@dalilihealth.com")
                                .url("https://dalilihealth.com"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://dalilihealth.com/license")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Local Development"),
                        new Server()
                                .url("https://api.dalilihealth.com")
                                .description("Production")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
                .components(new Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new SecurityScheme()
                                        .name("Bearer Authentication")
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Enter JWT token obtained from login endpoint")));
    }
}
