package dalili.com.dalili.interfaces.security.jwt;


import dalili.com.dalili.ambient.session.SessionContext;
import dalili.com.dalili.application.SessionActivityService;
import dalili.com.dalili.domain.user.model.ActorType;
import dalili.com.dalili.domain.user.model.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SessionContext sessionContext;
    private final SessionActivityService sessionActivityService;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            SessionContext sessionContext,
            SessionActivityService sessionActivityService
    ) {
        this.jwtService = jwtService;
        this.sessionContext = sessionContext;
        this.sessionActivityService = sessionActivityService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            if (jwtService.isValid(token)) {
                UUID userId = jwtService.getUserId(token);
                String username = jwtService.getUsername(token);
                Role role = jwtService.getRole(token);
                ActorType actorType = jwtService.getActorType(token);
                UUID sessionId = jwtService.getSessionId(token);
                UUID patientId = jwtService.getPatientId(token);

                // Check kiosk session usage (single-use enforcement)
                if (actorType == ActorType.KIOSK) {
                    if (sessionActivityService.isKioskSessionUsed(sessionId)) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Kiosk session already used");
                        return;
                    }
                }

                // Check inactivity timeout for patients and staff
                if (actorType == ActorType.PATIENT || actorType == ActorType.STAFF) {
                    if (!sessionActivityService.isSessionActive(sessionId, actorType)) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Session timed out due to inactivity");
                        return;
                    }
                }

                // Bind to SessionContext for audit
                sessionContext.bind(sessionId, userId, username, role, actorType, patientId);

                // Update last activity
                sessionActivityService.touch(sessionId, userId, actorType);

                // Set Spring Security context
                var authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_" + role.name()),
                        new SimpleGrantedAuthority("ACTOR_" + actorType.name())
                );
                var authentication = new UsernamePasswordAuthenticationToken(username, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            sessionContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
