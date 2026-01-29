package dalili.com.dalili.interfaces.security;

import dalili.com.dalili.interfaces.security.jwt.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            SessionPresenceFilter sessionPresenceFilter
    ) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers("/health").permitAll()
                        .requestMatchers("/audit/health").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()

                        // Patient-only endpoints
                        .requestMatchers("/api/patient/portal/**").hasRole("PATIENT")
                        .requestMatchers("/api/patient/messages/**").hasRole("PATIENT")
                        .requestMatchers("/api/patient/appointments/**").hasRole("PATIENT")
                        .requestMatchers("/api/patient/prescriptions/**").hasRole("PATIENT")

                        // Kiosk-only endpoints (limited scope)
                        .requestMatchers("/api/kiosk/checkin/**").hasRole("KIOSK")
                        .requestMatchers("/api/kiosk/symptoms/**").hasRole("KIOSK")
                        .requestMatchers("/api/kiosk/queue/**").hasRole("KIOSK")

                        // Clinical staff endpoints
                        .requestMatchers("/api/clinical/**").hasAnyRole("PHYSICIAN", "NURSE")
                        .requestMatchers("/api/pharmacy/**").hasRole("PHARMACIST")
                        .requestMatchers("/api/lab/**").hasRole("LAB_TECHNICIAN")

                        // Admin endpoints
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/anchor/**").hasAnyRole("ADMIN", "PHYSICIAN")

                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(sessionPresenceFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}


