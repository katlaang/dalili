package dalili.com.base.application.service;

import dalili.com.base.domain.user.model.User;
import dalili.com.base.domain.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Ensures a default kiosk device identity exists for local/dev testing.
 */
@Component
public class KioskStartupProvisioner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KioskStartupProvisioner.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean autoProvisionDefault;
    private final String defaultDeviceId;
    private final String defaultDeviceSecret;
    private final String defaultLocationDescription;

    public KioskStartupProvisioner(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${dalili.kiosk.auto-provision-default:true}") boolean autoProvisionDefault,
            @Value("${dalili.kiosk.default-device-id:kiosk-front-desk-1}") String defaultDeviceId,
            @Value("${dalili.kiosk.default-device-secret:kiosk-secret-change-me}") String defaultDeviceSecret,
            @Value("${dalili.kiosk.default-location-description:Front Desk 1}") String defaultLocationDescription
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.autoProvisionDefault = autoProvisionDefault;
        this.defaultDeviceId = defaultDeviceId;
        this.defaultDeviceSecret = defaultDeviceSecret;
        this.defaultLocationDescription = defaultLocationDescription;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoProvisionDefault) {
            log.info("Default kiosk auto-provisioning disabled");
            return;
        }

        if (isBlank(defaultDeviceId) || isBlank(defaultDeviceSecret)) {
            log.warn("Default kiosk auto-provisioning skipped due to missing device credentials");
            return;
        }

        userRepository.findByUsername(defaultDeviceId).ifPresentOrElse(existing -> {
            if (!existing.isKiosk()) {
                log.warn("Default kiosk username exists but is not a kiosk account username={}", defaultDeviceId);
                return;
            }
            log.info("Default kiosk already provisioned deviceId={}", defaultDeviceId);
        }, () -> {
            User kiosk = User.createKiosk(
                    defaultDeviceId,
                    passwordEncoder.encode(defaultDeviceSecret),
                    isBlank(defaultLocationDescription) ? "Default Kiosk" : defaultLocationDescription.trim()
            );
            userRepository.save(kiosk);
            log.info("Default kiosk provisioned deviceId={}", defaultDeviceId);
        });
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

