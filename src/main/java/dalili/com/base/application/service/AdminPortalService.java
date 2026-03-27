package dalili.com.base.application.service;

import dalili.com.base.domain.session.ActiveSession;
import dalili.com.base.domain.session.ActiveSessionRepository;
import dalili.com.base.domain.user.model.ActorType;
import dalili.com.base.domain.user.model.Role;
import dalili.com.base.domain.user.model.User;
import dalili.com.base.domain.user.repository.UserRepository;
import dalili.com.base.infra.audit.AuditEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AdminPortalService {

    private static final int MAX_AUDIT_LIMIT = 500;

    private final UserRepository userRepository;
    private final ActiveSessionRepository activeSessionRepository;
    private final AuditEventRepository auditEventRepository;

    public AdminPortalService(
            UserRepository userRepository,
            ActiveSessionRepository activeSessionRepository,
            AuditEventRepository auditEventRepository
    ) {
        this.userRepository = userRepository;
        this.activeSessionRepository = activeSessionRepository;
        this.auditEventRepository = auditEventRepository;
    }

    /**
     * Returns only non-patient accounts so super admins can manage login identities
     * without viewing patient-linked account details.
     */
    public List<StaffAccountView> getNonPatientAccounts() {
        return userRepository.findAll().stream()
                .filter(user -> user.getRole() != Role.PATIENT)
                .sorted(Comparator
                        .comparing((User user) -> user.getRole().name())
                        .thenComparing(User::getUsername, String.CASE_INSENSITIVE_ORDER))
                .map(user -> new StaffAccountView(
                        user.getId(),
                        user.getUsername(),
                        user.getEmail(),
                        user.getFullName(),
                        user.getRole().name(),
                        user.getActorType().name(),
                        user.isActive()
                ))
                .toList();
    }

    /**
     * Returns active sessions excluding patient actor sessions.
     */
    public List<ActiveSessionView> getActiveNonPatientSessions() {
        List<ActiveSession> sessions = activeSessionRepository.findAllByOrderByLastActivityDesc();
        Set<UUID> userIds = sessions.stream().map(ActiveSession::getUserId).collect(Collectors.toSet());
        Map<UUID, User> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return sessions.stream()
                .filter(session -> session.getActorType() != ActorType.PATIENT)
                .map(session -> {
                    User user = usersById.get(session.getUserId());
                    return new ActiveSessionView(
                            session.getSessionId(),
                            session.getUserId(),
                            user == null ? null : user.getUsername(),
                            user == null ? null : user.getFullName(),
                            user == null ? null : user.getRole().name(),
                            session.getActorType().name(),
                            session.getCreatedAt(),
                            session.getLastActivity()
                    );
                })
                .toList();
    }

    /**
     * Returns recent audit events with patient context redacted.
     */
    public List<AuditLogView> getRecentAuditEventsRedacted(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, MAX_AUDIT_LIMIT));
        return auditEventRepository.findAllByOrderByTimestampDesc(PageRequest.of(0, limit)).stream()
                .map(event -> new AuditLogView(
                        event.getId(),
                        event.getTimestamp(),
                        event.getEventType(),
                        event.getSessionId(),
                        event.getPhysicianId(),
                        event.getDeviceId(),
                        event.getPatientId() != null
                ))
                .toList();
    }

    public record StaffAccountView(
            UUID userId,
            String username,
            String email,
            String fullName,
            String role,
            String actorType,
            boolean active
    ) {
    }

    public record ActiveSessionView(
            UUID sessionId,
            UUID userId,
            String username,
            String fullName,
            String role,
            String actorType,
            Instant createdAt,
            Instant lastActivity
    ) {
    }

    public record AuditLogView(
            UUID eventId,
            Instant timestamp,
            String eventType,
            UUID sessionId,
            String actorId,
            String deviceId,
            boolean patientContextRedacted
    ) {
    }
}
