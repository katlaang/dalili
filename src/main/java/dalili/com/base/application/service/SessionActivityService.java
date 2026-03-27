package dalili.com.base.application.service;

import dalili.com.base.domain.session.ActiveSession;
import dalili.com.base.domain.session.ActiveSessionRepository;
import dalili.com.base.domain.session.model.KioskSession;
import dalili.com.base.domain.session.repository.KioskSessionRepository;
import dalili.com.base.domain.user.model.ActorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class SessionActivityService {

    private static final Logger log = LoggerFactory.getLogger(SessionActivityService.class);

    private final ActiveSessionRepository activeSessionRepository;
    private final KioskSessionRepository kioskSessionRepository;
    private final long patientInactivitySeconds;
    private final long staffInactivitySeconds;

    public SessionActivityService(
            ActiveSessionRepository activeSessionRepository,
            KioskSessionRepository kioskSessionRepository,
            @Value("${dalili.session.patient-inactivity-seconds:300}") long patientInactivitySeconds,
            @Value("${dalili.session.staff-inactivity-seconds:300}") long staffInactivitySeconds
    ) {
        this.activeSessionRepository = activeSessionRepository;
        this.kioskSessionRepository = kioskSessionRepository;
        this.patientInactivitySeconds = patientInactivitySeconds;
        this.staffInactivitySeconds = staffInactivitySeconds;
    }

    /**
     * Update last activity timestamp for session.
     */
    @Transactional
    public void touch(UUID sessionId, UUID userId, ActorType actorType) {
        log.debug("Touching session sessionId={} userId={} actorType={}", sessionId, userId, actorType);
        ActiveSession session = activeSessionRepository.findById(sessionId)
                .orElse(null);

        if (session == null) {
            session = new ActiveSession(sessionId, userId, actorType);
        } else {
            session.touch();
        }

        activeSessionRepository.save(session);
    }

    /**
     * Check if session is still active (not timed out due to inactivity).
     */
    public boolean isSessionActive(UUID sessionId, ActorType actorType) {
        boolean active = activeSessionRepository.findById(sessionId)
                .map(session -> {
                    long inactivityLimit = getInactivityLimit(actorType);
                    Instant cutoff = Instant.now().minusSeconds(inactivityLimit);
                    return session.getLastActivity().isAfter(cutoff);
                })
                .orElse(false);
        log.debug("Session active check sessionId={} actorType={} active={}", sessionId, actorType, active);
        return active;
    }

    /**
     * Get inactivity limit based on actor type.
     */
    private long getInactivityLimit(ActorType actorType) {
        return switch (actorType) {
            case PATIENT -> patientInactivitySeconds;  // 5 minutes
            case STAFF -> staffInactivitySeconds;       // 30 minutes
            case KIOSK, SYSTEM -> 60;                   // 1 minute (kiosk should be single-use anyway)
        };
    }

    // ==================== KIOSK SESSION MANAGEMENT ====================

    /**
     * Create a new kiosk session (called when generating kiosk token).
     */
    @Transactional
    public void createKioskSession(UUID sessionId, UUID kioskUserId, UUID patientId) {
        KioskSession session = new KioskSession(sessionId, kioskUserId, patientId);
        kioskSessionRepository.save(session);
        log.info("Kiosk session created sessionId={} kioskUserId={} patientId={}", sessionId, kioskUserId, patientId);
    }

    /**
     * Check if kiosk session has already been used.
     */
    public boolean isKioskSessionUsed(UUID sessionId) {
        boolean used = kioskSessionRepository.findById(sessionId)
                .map(KioskSession::isUsed)
                .orElse(true); // If not found, treat as used (invalid)
        log.debug("Kiosk session usage check sessionId={} used={}", sessionId, used);
        return used;
    }

    /**
     * Mark kiosk session as used (called after queue number is issued).
     */
    @Transactional
    public void markKioskSessionUsed(UUID sessionId) {
        kioskSessionRepository.findById(sessionId)
                .ifPresent(session -> {
                    session.markUsed();
                    kioskSessionRepository.save(session);
                    log.info("Kiosk session marked used sessionId={} patientId={}", sessionId, session.getPatientId());
                });
    }

    /**
     * Invalidate a session (logout).
     */
    @Transactional
    public void invalidateSession(UUID sessionId) {
        activeSessionRepository.deleteById(sessionId);
        log.info("Session invalidated sessionId={}", sessionId);
    }
}


