package dalili.com.dalili.infra.session;

import dalili.com.dalili.domain.session.ActiveSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class SessionCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionCleanupScheduler.class);

    private final ActiveSessionRepository activeSessionRepository;

    public SessionCleanupScheduler(ActiveSessionRepository activeSessionRepository) {
        this.activeSessionRepository = activeSessionRepository;
    }

    /**
     * Clean up stale sessions every hour.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void cleanupStaleSessions() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        int deleted = activeSessionRepository.deleteInactiveSessions(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} stale sessions", deleted);
        }
    }
}
