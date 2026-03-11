package dalili.com.base.infra.appointment;

import dalili.com.base.application.service.AppointmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled maintenance for appointment lifecycle.
 */
@Component
public class AppointmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentScheduler.class);

    private final AppointmentService appointmentService;

    public AppointmentScheduler(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    /**
     * Every minute: deactivate appointments that missed check-in window.
     */
    @Scheduled(fixedDelay = 60_000)
    public void deactivateExpiredAppointments() {
        int deactivated = appointmentService.deactivateExpiredAppointments();
        if (deactivated > 0) {
            log.info("Scheduled appointment deactivation completed count={}", deactivated);
        }
    }
}
