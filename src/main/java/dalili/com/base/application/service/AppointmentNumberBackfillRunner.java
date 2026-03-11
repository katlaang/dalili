package dalili.com.base.application.service;

import dalili.com.base.domain.appointment.repository.AppointmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AppointmentNumberBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AppointmentNumberBackfillRunner.class);

    private final AppointmentRepository appointmentRepository;

    public AppointmentNumberBackfillRunner(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var missing = appointmentRepository.findByAppointmentNumberIsNullOrderByCreatedAtAsc();
        if (missing.isEmpty()) {
            return;
        }

        int nextSequence = appointmentRepository.findMaxAppointmentNumberSequence()
                .map(previous -> previous + 1)
                .orElse(1);

        for (var appointment : missing) {
            appointment.assignAppointmentNumber(String.format("PR-%03d", nextSequence), nextSequence);
            nextSequence++;
        }
        appointmentRepository.saveAll(missing);
        log.info("Backfilled appointment numbers count={}", missing.size());
    }
}

