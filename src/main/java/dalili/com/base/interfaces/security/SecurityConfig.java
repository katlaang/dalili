package dalili.com.base.interfaces.security;

import dalili.com.base.interfaces.security.jwt.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
 *   <li>ADMIN - Full system access</li>
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

    /**
     * Constructs SecurityConfig with required filters.
     *
     * @param jwtAuthenticationFilter filter for JWT validation
     * @param sessionPresenceFilter   filter for session context
     */
    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            SessionPresenceFilter sessionPresenceFilter
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.sessionPresenceFilter = sessionPresenceFilter;
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

                // Stateless session management
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // Authorization rules
                .authorizeHttpRequests(auth -> auth

                        // ==================== PUBLIC ENDPOINTS ====================

                        // Health checks (no authentication required)
                        .requestMatchers("/health", "/audit/health").permitAll()

                        // Authentication endpoints
                        .requestMatchers("/api/auth/staff/login").permitAll()
                        .requestMatchers("/api/auth/patient/login").permitAll()
                        .requestMatchers("/api/auth/kiosk/checkin").permitAll()
                        .requestMatchers("/api/auth/staff/register").permitAll()
                        .requestMatchers("/api/auth/patient/register").permitAll()

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
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/auth/kiosk/register").hasRole("ADMIN")

                        // ==================== QUEUE MANAGEMENT ====================

                        // Queue ticket issuance - reception and kiosk
                        .requestMatchers("/api/queue/issue").hasAnyRole("RECEPTIONIST", "NURSE", "ADMIN")
                        .requestMatchers("/api/queue/emergency").hasAnyRole("NURSE", "PHYSICIAN", "ADMIN")

                        // Queue viewing - clinical and administrative staff
                        .requestMatchers("/api/queue/triage").hasAnyRole("NURSE", "ADMIN")
                        .requestMatchers("/api/queue/consultation").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                        .requestMatchers("/api/queue/waiting").hasAnyRole("PHYSICIAN", "NURSE", "RECEPTIONIST", "ADMIN")
                        .requestMatchers("/api/queue/today").hasAnyRole("PHYSICIAN", "NURSE", "RECEPTIONIST", "ADMIN")
                        .requestMatchers("/api/queue/overdue").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                        .requestMatchers("/api/queue/stats").hasAnyRole("PHYSICIAN", "NURSE", "RECEPTIONIST", "ADMIN")

                        // Queue calling and workflow
                        .requestMatchers("/api/queue/call-next/triage").hasAnyRole("NURSE", "ADMIN")
                        .requestMatchers("/api/queue/call-next/consultation").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                        .requestMatchers("/api/queue/*/call").hasAnyRole("PHYSICIAN", "NURSE", "RECEPTIONIST", "ADMIN")
                        .requestMatchers("/api/queue/*/missed-call").hasAnyRole("PHYSICIAN", "NURSE", "RECEPTIONIST", "ADMIN")
                        .requestMatchers("/api/queue/*/start").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                        .requestMatchers("/api/queue/*/complete").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                        .requestMatchers("/api/queue/*/no-show").hasAnyRole("PHYSICIAN", "NURSE", "RECEPTIONIST", "ADMIN")
                        .requestMatchers("/api/queue/*/cancel").hasAnyRole("PHYSICIAN", "NURSE", "RECEPTIONIST", "ADMIN")
                        .requestMatchers("/api/queue/*/escalate").hasAnyRole("PHYSICIAN", "NURSE", "ADMIN")
                        .requestMatchers("/api/queue/*").hasAnyRole("PHYSICIAN", "NURSE", "RECEPTIONIST", "ADMIN")

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

                        // Patient registration and management
                        .requestMatchers("/api/patients/register/**").hasAnyRole("RECEPTIONIST", "NURSE", "ADMIN")
                        .requestMatchers("/api/patients/**").hasAnyRole("PHYSICIAN", "NURSE", "RECEPTIONIST", "ADMIN")

                        // ==================== CLINICAL ENDPOINTS ====================

                        // Clinical care - physicians and nurses
                        .requestMatchers("/api/clinical/**").hasAnyRole("PHYSICIAN", "NURSE")

                        // ==================== SPECIALTY ENDPOINTS ====================

                        // Pharmacy - pharmacists
                        .requestMatchers("/api/pharmacy/**").hasRole("PHARMACIST")

                        // Laboratory - lab technicians
                        .requestMatchers("/api/lab/**").hasRole("LAB_TECHNICIAN")

                        // ==================== KIOSK ENDPOINTS ====================

                        // Kiosk-specific operations
                        .requestMatchers("/api/kiosk/**").hasRole("KIOSK")

                        // ==================== PATIENT PORTAL ====================

                        // Patient self-service
                        .requestMatchers("/api/patient/portal/**").hasRole("PATIENT")
                        .requestMatchers("/api/patient/messages/**").hasRole("PATIENT")
                        .requestMatchers("/api/patient/appointments/**").hasRole("PATIENT")
                        .requestMatchers("/api/patient/prescriptions/**").hasRole("PATIENT")

                        // ==================== AUDIT ENDPOINTS ====================

                        // Audit access - admin and physicians for compliance review
                        .requestMatchers("/api/anchor/**").hasAnyRole("ADMIN", "PHYSICIAN")

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
}