package dalili.com.dalili.interfaces.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AuditSyncAuthFilter extends OncePerRequestFilter {

    private final DeviceKeyService keyService;

    public AuditSyncAuthFilter(DeviceKeyService keyService) {
        this.keyService = keyService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws IOException, ServletException {

        if (!request.getRequestURI().startsWith("/api/audit/sync")) {
            chain.doFilter(request, response);
            return;
        }

        String payload = request.getHeader("X-Sync-Payload");
        String signature = request.getHeader("X-Sync-Signature");

        if (payload == null || signature == null ||
                !signature.equals(keyService.sign(payload))) {

            response.sendError(
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Invalid device signature"
            );
            return;
        }

        chain.doFilter(request, response);
    }
}

