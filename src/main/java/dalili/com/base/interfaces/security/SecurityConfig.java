package dalili.com.base.interfaces.security;

import dalili.com.base.interfaces.security.jwt.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security configuration for the Dalili Health platform.
 *
 * <p>This configuration establishes:
 * <ul>
 *   <li>JWT-based stateless authentication</li>
 *   <li>Role-based access control for all API endpoints</li>
 *   <li>Public endpoints for authentication and health checks</li>
 *   <li>Clinical workflow endpoint authorization</li>
 * </ul>
 * </p>
 *
 * <h3>Role Hierarchy:</h3>
 * <ul>
 *   <li>SUPER_ADMIN - Identity/audit/config administration (no direct patient-data routes)</li>
 *   <li>ADMIN - Operational administration and staff provisioning</li>
 *   <li>PHYSICIAN - Clinical care, diagnosis, prescribing</li>
 *   <li>NURSE - Triage, vitals, clinical support</li>
 *   <li>PHARMACIST - Medication dispensing</li>
 *   <li>LAB_TECHNICIAN - Laboratory services</li>
 *   <li>RECEPTIONIST - Patient registration, queue management</li>
 *   <li>PATIENT - Patient portal access</li>
 *   <li>KIOSK - Self-service check-in</li>
 *   <li>SYSTEM - Internal service communication</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SessionPresenceFilter sessionPresenceFilter;
    private final List<String> allowedOrigins;

    /**
     * Constructs SecurityConfig with required filters.
     *
     * @param jwtAuthenticationFilter filter for JWT validation
     * @param sessionPresenceFilter   filter for session context
     */
    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            SessionPresenceFilter sessionPresenceFilter,
            @Value("${dalili.security.allowed-origins:http://localhost:*,http://127.0.0.1:*,http://[::1]:*,http://192.168.*:*,http://10.*:*,http://172.*:*}") String allowedOrigins
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.sessionPresenceFilter = sessionPresenceFilter;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
    }

    /**
     * Configures the security filter chain.
     *
     * @param http the HttpSecurity to configure
     * @return the configured SecurityFilterChain
     * @throws Exception if configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF for stateless JWT authentication
                .csrf(AbstractHttpConfigurer::disable)
                // Enable CORS for React Native Web/browser clients
                .cors(Customizer.withDefaults())

                // Stateless session management
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // Authorization rules
                .authorizeHttpRequests(auth -> auth

                                // ==================== PUBLIC ENDPOINTS ====================

                                // Allow preflight requests
                                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                                // Health checks (no authentication required)
                                .requestMatchers("/health", "/audit/health").permitAll()

                                // Authentication endpoints
                                .requestMatchers("/api/auth/staff/login").permitAll()
                                .requestMatchers("/api/auth/patient/login").permitAll()
                                .requestMatchers("/api/auth/kiosk/login").permitAll()
                                .requestMatchers("/api/auth/kiosk/checkin").permitAll()
                                .requestMatchers("/api/auth/kiosk/identify").permitAll()
                                .requestMatchers("/api/auth/super-admin/bootstrap").permitAll()
                                .requestMatchers("/api/auth/super-admin/bootstrap-status").permitAll()

                                // Swagger/OpenAPI documentation
                                .requestMatchers(
                                        "/swagger-ui.html",
                                        "/swagger-ui/**",
                                        "/v3/api-docs",
                                        "/v3/api-docs/**",
                                        "/swagger-resources/**",
                                        "/webjars/**"
                                ).permitAll()
                                // Also allow access to the root and swagger redirect
                                .requestMatchers("/swagger-ui/index.html").permitAll()

                                // ==================== AUTHENTICATED ENDPOINTS ====================

                                // Logout requires authentication
                                .requestMatchers("/api/auth/logout").authenticated()

                                // ==================== ADMIN ENDPOINTS ====================

                                // Admin-only operations
                                .requestMatchers("/api/admin/super/**").hasRole("SUPER_ADMIN")
                                .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                                .requestMatchers("/api/auth/admin/register").hasRole("SUPER_ADMIN")
                                .requestMatchers("/api/auth/staff/register").hasAnyRole("ADMIN", "SUPER_ADMIN")
                                .requestMatchers("/api/auth/patient/register").hasAnyRole("ADMIN", "SUPER_ADMIN")
                                .requestMatchers("/api/auth/kiosk/register").hasAnyRole("ADMIN", "SUPER_ADMIN")
                                .requestMatchers(HttpMethod.PUT, "/api/facility/workflow-config").hasRole("SUPER_ADMIN")
                                .requestMatchers(HttpMethod.GET, "/api/facility/workflow-config")
                                .hasAnyRole("ADMIN", "SUPER_ADMIN", "PHYSICIAN", "NURSE", "PHARMACIST", "LAB_TECHNICIAN", "RECEPTIONIST", "PATIENT", "KIOSK")

                                // ==================== QUEUE MANAGEMENT ====================

                                // Queue ticket issuance - reception and kiosk
                                .requestMatchers("/api/queue/issue").hasAnyRole("RECEPTIONIST", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/emergency").hasAnyRole("NURSE", "PHYSICIAN", "ADMIN")

                                // Queue viewing - clinical and administrative staff
                                .requestMatchers("/api/queue/triage").hasAnyRole("NURSE", "ADMIN")
                                .requestMatchers("/api/queue/consultation").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/waiting").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/today").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/overdue").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/stats").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")

                                // Queue calling and workflow
                                .requestMatchers("/api/queue/call-next/triage").hasAnyRole("NURSE", "ADMIN")
                                .requestMatchers("/api/queue/call-next/consultation").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/*/call").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/*/missed-call").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/*/start").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/*/return-to-waiting").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/*/complete").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/*/admit").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/*/handoff").hasAnyRole("NURSE", "ADMIN")
                                .requestMatchers("/api/queue/*/no-show").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/*/cancel").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/*/escalate").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/*/triage-outcome").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/queue/*").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")

                                // ==================== TRIAGE ENDPOINTS ====================

                                // Triage assessment - nurses and physicians
                                .requestMatchers("/api/triage/begin").hasAnyRole("NURSE", "ADMIN")
                                .requestMatchers("/api/triage/reassess/**").hasAnyRole("NURSE", "ADMIN")
                                .requestMatchers("/api/triage/*/vitals").hasAnyRole("NURSE", "ADMIN")
                                .requestMatchers("/api/triage/*/red-flags").hasAnyRole("NURSE", "ADMIN")
                                .requestMatchers("/api/triage/*/observations").hasAnyRole("NURSE", "ADMIN")
                                .requestMatchers("/api/triage/*/accept").hasAnyRole("NURSE", "ADMIN")
                                .requestMatchers("/api/triage/*/override").hasAnyRole("NURSE", "ADMIN")

                                // Triage viewing - clinical staff
                                .requestMatchers("/api/triage/**").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")

                                // ==================== PATIENT MANAGEMENT ====================
                                .requestMatchers("/api/frontdesk/**").hasAnyRole("RECEPTIONIST", "NURSE", "ADMIN")

                                // Patient registration and management
                                .requestMatchers("/api/patients/register/**").hasAnyRole("NURSE", "ADMIN")
                                .requestMatchers("/api/patients/**").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers("/api/appointments/**").hasAnyRole("PHYSICIAN", "NURSE", "RECEPTIONIST", "ADMIN")

                                // ==================== CLINICAL ENDPOINTS ====================

                                // Clinical care - physicians and nurses
                                .requestMatchers(HttpMethod.GET, "/api/clinical/patient-data/messages/inbox")
                                .hasAnyRole("ADMIN", "PHYSICIAN", "NURSE", "PHARMACIST", "LAB_TECHNICIAN")
                                .requestMatchers(HttpMethod.POST, "/api/clinical/patient-data/*/messages")
                                .hasAnyRole("ADMIN", "PHYSICIAN", "NURSE", "PHARMACIST", "LAB_TECHNICIAN")
                                .requestMatchers(HttpMethod.POST, "/api/clinical/patient-data/messages/*/read")
                                .hasAnyRole("ADMIN", "PHYSICIAN", "NURSE", "PHARMACIST", "LAB_TECHNICIAN")
                                .requestMatchers(HttpMethod.GET, "/api/clinical/patient-data/renewals/pending")
                                .hasAnyRole("PHYSICIAN", "PHARMACIST", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/clinical/patient-data/renewals/*/review")
                                .hasAnyRole("PHYSICIAN", "PHARMACIST", "ADMIN")
                                .requestMatchers("/api/clinical/**").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")

                                // ==================== SPECIALTY ENDPOINTS ====================

                                // Pharmacy - pharmacists
                                .requestMatchers("/api/pharmacy/**").hasRole("PHARMACIST")

                                // Laboratory - lab technicians
                                .requestMatchers("/api/lab/**").hasRole("LAB_TECHNICIAN")

                                // ==================== KIOSK ENDPOINTS ====================

                                // Kiosk-specific operations
                                .requestMatchers("/api/kiosk/public/**").permitAll()
                                .requestMatchers("/api/kiosk/**").hasRole("KIOSK")

                                // ==================== PATIENT PORTAL ====================

                                // Patient self-service (restricted to results + appointment confirmation)
                                .requestMatchers(HttpMethod.GET, "/api/patient/portal/labs").hasRole("PATIENT")
                                .requestMatchers(HttpMethod.GET, "/api/patient/portal/referrals").hasRole("PATIENT")
                                .requestMatchers(HttpMethod.GET, "/api/patient/portal/notes").hasRole("PATIENT")
                                .requestMatchers(HttpMethod.GET, "/api/patient/portal/profile").hasRole("PATIENT")
                                .requestMatchers(HttpMethod.PUT, "/api/patient/portal/profile/emergency-contact").hasRole("PATIENT")
                                .requestMatchers(HttpMethod.GET, "/api/patient/appointments/pending").hasRole("PATIENT")
                                .requestMatchers(HttpMethod.GET, "/api/patient/appointments/history").hasRole("PATIENT")
                                .requestMatchers(HttpMethod.POST, "/api/patient/appointments/*/checkin").hasRole("PATIENT")
                                .requestMatchers("/api/patient/portal/**").denyAll()
                                .requestMatchers("/api/patient/messages/**").denyAll()
                                .requestMatchers("/api/patient/appointments/**").denyAll()
                                .requestMatchers("/api/patient/prescriptions/**").denyAll()

                                // ==================== AUDIT ENDPOINTS ====================

                                // Audit access - admin and physicians for compliance review
                                .requestMatchers("/api/anchor/**").hasAnyRole("ADMIN", "SUPER_ADMIN", "PHYSICIAN")
                                // Encounter endpoints
                                .requestMatchers(HttpMethod.GET, "/api/encounters/preview/**").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/from-queue").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/standalone").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/transcript").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/ai-draft").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/ambient/transcribe").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/ambient/transcribe/background").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/ai-draft/generate").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/differentials/generate").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/care-plan/suggest").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/care-plan/agree").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/physician-note").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/family-history").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/confirm-note").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/diagnoses").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/diagnoses/agree").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.DELETE, "/api/encounters/*/diagnoses/*").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/medications").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.DELETE, "/api/encounters/*/medications/*").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.GET, "/api/encounters/*/prescription").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/prescription/print").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/complete").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/cancel").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.GET, "/api/encounters/dashboard/physician").hasAnyRole("PHYSICIAN", "ADMIN")
                                .requestMatchers(HttpMethod.GET, "/api/encounters/**").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
// Addendums - any clinician can add addendums
                                .requestMatchers(HttpMethod.POST, "/api/encounters/*/addendums/**").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                                .requestMatchers(HttpMethod.GET, "/api/encounters/*/addendums/**").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")

                                // ==================== DEFAULT ====================

                                // All other requests require authentication
                                .anyRequest().authenticated()
                )

                // Add JWT filter before UsernamePasswordAuthenticationFilter
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(sessionPresenceFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Password encoder bean using BCrypt.
     *
     * @return BCrypt password encoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Origin"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
