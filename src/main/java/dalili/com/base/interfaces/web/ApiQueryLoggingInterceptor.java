package dalili.com.base.interfaces.web;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.user.model.ActorType;
import dalili.com.base.domain.user.model.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.net.URI;
import java.util.Set;
import java.util.TreeSet;

/**
 * Logs inbound API GET requests that are dispatched to Spring MVC controller methods.
 *
 * <p>This interceptor exists to give a single, centralized log entry for query-style
 * controller calls instead of repeating request logging across individual controllers.
 * It is intentionally limited to controller-backed {@code GET} requests under {@code /api/}
 * so the logs stay focused on read/query traffic.</p>
 *
 * <p>The emitted log line includes:</p>
 * <ul>
 *   <li>controller class name</li>
 *   <li>controller method name</li>
 *   <li>matched route pattern</li>
 *   <li>query parameter names only, not values</li>
 *   <li>origin or referer of the caller</li>
 *   <li>source port derived from the origin/referer</li>
 *   <li>authenticated actor type, role, and username when available</li>
 * </ul>
 *
 * <p>Query values are deliberately excluded to reduce the risk of logging sensitive data.</p>
 */
@Component
public class ApiQueryLoggingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiQueryLoggingInterceptor.class);

    private final SessionContext sessionContext;

    public ApiQueryLoggingInterceptor(SessionContext sessionContext) {
        this.sessionContext = sessionContext;
    }

    /**
     * Determines whether the current request should produce a query log entry.
     *
     * <p>The interceptor only logs requests that meet all of the following conditions:</p>
     * <ul>
     *   <li>HTTP method is {@code GET}</li>
     *   <li>request path starts with {@code /api/}</li>
     *   <li>the resolved handler is a Spring MVC {@link HandlerMethod}</li>
     * </ul>
     *
     * @param request current HTTP request
     * @param handler resolved handler object
     * @return {@code true} when the request should be logged
     */
    static boolean shouldLog(HttpServletRequest request, Object handler) {
        return "GET".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().startsWith("/api/")
                && handler instanceof HandlerMethod;
    }

    /**
     * Resolves the most useful route identifier for logging.
     *
     * <p>When Spring exposes a best-matching route pattern such as
     * {@code /api/patient/portal/profile}, that value is preferred because it is
     * stable and easier to scan than a raw URI. If no mapping pattern is available,
     * the raw request URI is used as a fallback.</p>
     *
     * @param request current HTTP request
     * @return matched route pattern or request URI
     */
    static String resolveRoute(HttpServletRequest request) {
        Object bestMatch = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (bestMatch instanceof String pattern && !pattern.isBlank()) {
            return pattern;
        }
        return request.getRequestURI();
    }

    /**
     * Collects the names of query parameters attached to the request.
     *
     * <p>Only parameter names are returned. Values are intentionally not logged so
     * patient data, identifiers, and free-text search terms are less likely to leak
     * into application logs. Keys are sorted for stable log output.</p>
     *
     * @param request current HTTP request
     * @return comma-separated parameter names, or {@code -} when none exist
     */
    static String resolveQueryKeys(HttpServletRequest request) {
        Set<String> queryKeys = new TreeSet<>(request.getParameterMap().keySet());
        return queryKeys.isEmpty() ? "-" : String.join(",", queryKeys);
    }

    /**
     * Extracts a numeric port from an origin or referer URL for simpler log filtering.
     *
     * <p>If the source is blank, the literal string {@code null}, or not a valid URI,
     * the method returns {@code -}. This keeps the log format stable even when callers
     * omit the {@code Origin} header or send malformed values.</p>
     *
     * @param requestSource origin or referer header value
     * @return port number as text, or {@code -} when unavailable
     */
    static String resolveSourcePort(String requestSource) {
        if (requestSource == null || requestSource.isBlank() || "null".equalsIgnoreCase(requestSource)) {
            return "-";
        }

        try {
            int port = URI.create(requestSource).getPort();
            return port >= 0 ? Integer.toString(port) : "-";
        } catch (IllegalArgumentException ignored) {
            return "-";
        }
    }

    /**
     * Returns the first non-blank string from the provided candidates.
     *
     * <p>This is used to prefer {@code Origin} over {@code Referer}, and to fall back
     * to a default marker when session or header data is missing.</p>
     *
     * @param values candidate values in priority order
     * @return first non-blank value, or {@code -} when none are present
     */
    static String firstPresent(String... values) {
        if (values == null) {
            return "-";
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return "-";
    }

    /**
     * Logs request metadata before the target controller method executes.
     *
     * <p>If the incoming request is not a controller-backed {@code GET /api/**} call,
     * the interceptor does nothing and allows the request to continue untouched.
     * For matching requests, it extracts route and caller metadata and writes a
     * single structured log entry.</p>
     *
     * @param request  current HTTP request
     * @param response current HTTP response
     * @param handler  resolved Spring MVC handler
     * @return {@code true} so normal request processing continues
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!shouldLog(request, handler)) {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        String requestSource = firstPresent(request.getHeader("Origin"), request.getHeader("Referer"));
        String route = resolveRoute(request);
        String queryKeys = resolveQueryKeys(request);
        ActorType actorType = sessionContext.actorType();
        Role role = sessionContext.role();

        log.info(
                "API query request controller={} handler={} route={} queryKeys={} source={} sourcePort={} actor={} role={} user={}",
                handlerMethod.getBeanType().getSimpleName(),
                handlerMethod.getMethod().getName(),
                route,
                queryKeys,
                requestSource,
                resolveSourcePort(requestSource),
                actorType != null ? actorType.name() : "ANONYMOUS",
                role != null ? role.name() : "ANONYMOUS",
                firstPresent(sessionContext.username(), "anonymous")
        );

        return true;
    }
}
