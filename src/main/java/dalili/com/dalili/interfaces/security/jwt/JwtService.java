package dalili.com.dalili.interfaces.security.jwt;


import dalili.com.dalili.domain.user.model.ActorType;
import dalili.com.dalili.domain.user.model.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long staffExpirationHours;
    private final long patientExpirationHours;
    private final long kioskExpirationMinutes;

    public JwtService(
            @Value("${dalili.jwt.secret:your-256-bit-secret-key-for-jwt-signing-min-32-chars}") String secret,
            @Value("${dalili.jwt.staff-expiration-hours:12}") long staffExpirationHours,
            @Value("${dalili.jwt.patient-expiration-hours:24}") long patientExpirationHours,
            @Value("${dalili.jwt.kiosk-expiration-minutes:10}") long kioskExpirationMinutes
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.staffExpirationHours = staffExpirationHours;
        this.patientExpirationHours = patientExpirationHours;
        this.kioskExpirationMinutes = kioskExpirationMinutes;
    }

    public TokenResult generateStaffToken(UUID userId, String username, Role role) {
        UUID sessionId = UUID.randomUUID();
        String token = generateToken(sessionId, userId, username, role, ActorType.STAFF, null, staffExpirationHours, ChronoUnit.HOURS);
        return new TokenResult(token, sessionId);
    }

    public TokenResult generatePatientToken(UUID userId, String username, UUID patientId) {
        UUID sessionId = UUID.randomUUID();
        String token = generateToken(sessionId, userId, username, Role.PATIENT, ActorType.PATIENT, patientId, patientExpirationHours, ChronoUnit.HOURS);
        return new TokenResult(token, sessionId);
    }

    public TokenResult generateKioskToken(UUID kioskUserId, String deviceId, UUID patientId) {
        UUID sessionId = UUID.randomUUID();
        String token = generateToken(sessionId, kioskUserId, deviceId, Role.KIOSK, ActorType.KIOSK, patientId, kioskExpirationMinutes, ChronoUnit.MINUTES);
        return new TokenResult(token, sessionId);
    }

    public TokenResult generateSystemToken(UUID systemUserId, String systemName) {
        UUID sessionId = UUID.randomUUID();
        String token = generateToken(sessionId, systemUserId, systemName, Role.SYSTEM, ActorType.SYSTEM, null, 1, ChronoUnit.HOURS);
        return new TokenResult(token, sessionId);
    }

    private String generateToken(UUID sessionId, UUID userId, String username, Role role, ActorType actorType, UUID patientId, long expiration, ChronoUnit unit) {
        Instant now = Instant.now();

        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("role", role.name())
                .claim("actorType", actorType.name())
                .claim("sessionId", sessionId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration, unit)))
                .signWith(key);

        if (patientId != null) {
            builder.claim("patientId", patientId.toString());
        }

        return builder.compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    public UUID getUserId(String token) {
        return UUID.fromString(parseToken(token).getSubject());
    }

    public String getUsername(String token) {
        return parseToken(token).get("username", String.class);
    }

    public Role getRole(String token) {
        return Role.valueOf(parseToken(token).get("role", String.class));
    }

    public ActorType getActorType(String token) {
        return ActorType.valueOf(parseToken(token).get("actorType", String.class));
    }

    public UUID getSessionId(String token) {
        return UUID.fromString(parseToken(token).get("sessionId", String.class));
    }

    public UUID getPatientId(String token) {
        String patientId = parseToken(token).get("patientId", String.class);
        return patientId != null ? UUID.fromString(patientId) : null;
    }

    public record TokenResult(String token, UUID sessionId) {
    }
}