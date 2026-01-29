package dalili.com.dalili.interfaces.security;

import dalili.com.dalili.ambient.session.SessionContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;


@Component
public class SessionPresenceFilter extends OncePerRequestFilter {

    private final SessionContext sessionContext;

    public SessionPresenceFilter(SessionContext sessionContext) {
        this.sessionContext = sessionContext;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String uri = request.getRequestURI();

        // Allow health check and anchor operations without clinical session
        if (uri.equals("/health") ||
                uri.equals("/audit/health") ||
                uri.startsWith("/api/auth/") ||
                uri.startsWith("/api/anchor/")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (sessionContext.sessionId() == null ||
                sessionContext.physicianId() == null) {

            response.sendError(
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "No active clinical session"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }
}

