package dalili.com.base.domain.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Monitors connectivity state for graceful AI degradation.
 * <p>
 * States:
 * - ONLINE: Normal operation, AI features available
 * - DEGRADED: Auto-switch after 3 consecutive timeouts
 * - OFFLINE: Manual override or extended outage
 * <p>
 * Mode changes are logged with timestamp, reason, duration.
 */
public class ConnectivityMonitor {

    private static final Logger log = LoggerFactory.getLogger(ConnectivityMonitor.class);
    private static final int DEGRADED_THRESHOLD = 3;

    private final AtomicReference<ConnectivityMode> currentMode = new AtomicReference<>(ConnectivityMode.ONLINE);
    private final AtomicReference<ModeOverride> override = new AtomicReference<>(ModeOverride.AUTO);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<Instant> lastModeChange = new AtomicReference<>(Instant.now());
    private final AtomicReference<String> lastChangeReason = new AtomicReference<>("Initial state");

    /**
     * Get effective connectivity mode (considering overrides).
     */
    public ConnectivityMode getEffectiveMode() {
        ModeOverride ov = override.get();
        return switch (ov) {
            case FORCE_ON -> ConnectivityMode.ONLINE;
            case FORCE_OFF -> ConnectivityMode.OFFLINE;
            case AUTO -> currentMode.get();
        };
    }

    /**
     * Record a successful AI call.
     */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        if (currentMode.get() == ConnectivityMode.DEGRADED) {
            transitionTo(ConnectivityMode.ONLINE, "Connectivity restored");
        }
    }

    /**
     * Record a failed AI call (timeout or error).
     */
    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= DEGRADED_THRESHOLD && currentMode.get() == ConnectivityMode.ONLINE) {
            transitionTo(ConnectivityMode.DEGRADED,
                    "Auto-degraded after " + failures + " consecutive failures");
        }
    }

    /**
     * Set manual override.
     */
    public void setOverride(ModeOverride newOverride) {
        ModeOverride previous = override.getAndSet(newOverride);
        if (previous != newOverride) {
            log.info("Connectivity override changed: {} -> {}", previous, newOverride);
            lastModeChange.set(Instant.now());
            lastChangeReason.set("Manual override: " + newOverride);
        }
    }

    /**
     * Clear override, return to auto mode.
     */
    public void clearOverride() {
        setOverride(ModeOverride.AUTO);
    }

    /**
     * Check if AI features are available.
     */
    public boolean isAiAvailable() {
        return getEffectiveMode() == ConnectivityMode.ONLINE;
    }

    /**
     * Get current status for UI display.
     */
    public ConnectivityStatus getStatus() {
        return new ConnectivityStatus(
                getEffectiveMode(),
                override.get(),
                consecutiveFailures.get(),
                lastModeChange.get(),
                lastChangeReason.get()
        );
    }

    private void transitionTo(ConnectivityMode newMode, String reason) {
        ConnectivityMode previous = currentMode.getAndSet(newMode);
        if (previous != newMode) {
            log.info("Connectivity mode changed: {} -> {} ({})", previous, newMode, reason);
            lastModeChange.set(Instant.now());
            lastChangeReason.set(reason);
        }
    }

    // ==================== NESTED TYPES ====================

    /**
     * Connectivity mode enumeration.
     */
    public enum ConnectivityMode {
        /**
         * Normal operation, AI features available
         */
        ONLINE,
        /**
         * Degraded mode after consecutive failures
         */
        DEGRADED,
        /**
         * Offline due to manual override or extended outage
         */
        OFFLINE
    }

    /**
     * Manual override mode for connectivity.
     */
    public enum ModeOverride {
        /**
         * Automatic mode detection
         */
        AUTO,
        /**
         * Force online regardless of failures
         */
        FORCE_ON,
        /**
         * Force offline regardless of connectivity
         */
        FORCE_OFF
    }

    /**
     * Immutable status record for connectivity state.
     */
    public record ConnectivityStatus(
            ConnectivityMode effectiveMode,
            ModeOverride override,
            int consecutiveFailures,
            Instant lastModeChange,
            String lastChangeReason
    ) {
        /**
         * Checks if AI is available in current state.
         *
         * @return true if mode is ONLINE
         */
        public boolean isAiAvailable() {
            return effectiveMode == ConnectivityMode.ONLINE;
        }
    }
}
