package dalili.com.base.infra.audit;

/**
 * Thrown when clock manipulation is detected.
 */
public class ClockManipulationException extends RuntimeException {

    public ClockManipulationException(String message) {
        super(message);
    }
}
