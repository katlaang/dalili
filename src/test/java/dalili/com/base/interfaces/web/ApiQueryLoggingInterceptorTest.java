package dalili.com.base.interfaces.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.user.model.ActorType;
import dalili.com.base.domain.user.model.Role;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ApiQueryLoggingInterceptor}.
 *
 * <p>These tests verify the interceptor logs the expected metadata for controller-backed
 * query requests and skips request shapes that should not be logged.</p>
 */
class ApiQueryLoggingInterceptorTest {

    /**
     * Verifies that a controller GET request produces one log entry containing
     * route, query-key, origin, port, and authenticated-session metadata.
     *
     * @throws Exception if reflective handler lookup fails
     */
    @Test
    void logsControllerGetRequestsWithOriginAndQueryKeys() throws Exception {
        SessionContext sessionContext = new SessionContext();
        sessionContext.bind(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "portal-user",
                Role.PATIENT,
                ActorType.PATIENT,
                UUID.randomUUID()
        );

        ApiQueryLoggingInterceptor interceptor = new ApiQueryLoggingInterceptor(sessionContext);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/patient/portal/profile");
        request.addHeader("Origin", "http://localhost:8082");
        request.setParameter("sort", "desc");
        request.setParameter("tab", "labs");
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/patient/portal/profile"
        );

        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(
                new TestController(),
                TestController.class.getDeclaredMethod("handle")
        );

        Logger logger = (Logger) LoggerFactory.getLogger(ApiQueryLoggingInterceptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertTrue(interceptor.preHandle(request, response, handler));
            assertEquals(1, appender.list.size());

            String message = appender.list.getFirst().getFormattedMessage();
            assertTrue(message.contains("controller=TestController"));
            assertTrue(message.contains("handler=handle"));
            assertTrue(message.contains("route=/api/patient/portal/profile"));
            assertTrue(message.contains("queryKeys=sort,tab"));
            assertTrue(message.contains("source=http://localhost:8082"));
            assertTrue(message.contains("sourcePort=8082"));
            assertTrue(message.contains("actor=PATIENT"));
            assertTrue(message.contains("role=PATIENT"));
            assertTrue(message.contains("user=portal-user"));
        } finally {
            logger.detachAppender(appender);
            sessionContext.clear();
        }
    }

    /**
     * Verifies that non-GET requests are excluded from query logging.
     *
     * @throws NoSuchMethodException if reflective handler lookup fails
     */
    @Test
    void skipsNonGetRequests() throws NoSuchMethodException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/patient/portal/profile");
        HandlerMethod handler = new HandlerMethod(
                new TestController(),
                TestController.class.getDeclaredMethod("handle")
        );

        assertFalse(ApiQueryLoggingInterceptor.shouldLog(request, handler));
    }

    /**
     * Minimal handler target used to build a {@link HandlerMethod} for tests.
     */
    static class TestController {

        /**
         * Empty controller-style method used only as a reflective handler target.
         */
        public void handle() {
        }
    }
}
