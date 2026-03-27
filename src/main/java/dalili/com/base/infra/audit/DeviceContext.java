package dalili.com.base.infra.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class DeviceContext {

    private final String deviceId;
    private final Path deviceIdPath;

    public DeviceContext(
            @Value("${dalili.audit.device-id:}") String configuredDeviceId,
            @Value("${dalili.storage.dir:${user.home}/.dalili}") String storageDir
    ) {
        this.deviceIdPath = resolvePath(storageDir);
        this.deviceId = loadOrCreate(configuredDeviceId);
    }

    private String loadOrCreate(String configuredDeviceId) {
        try {
            if (configuredDeviceId != null && !configuredDeviceId.isBlank()) {
                return configuredDeviceId.trim();
            }

            if (Files.exists(deviceIdPath)) {
                String existing = Files.readString(deviceIdPath).trim();
                if (!existing.isBlank()) {
                    return existing;
                }
            }

            String id = "KIOSK-" + UUID.randomUUID();
            Path parent = deviceIdPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(deviceIdPath, id);
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize device ID", e);
        }
    }

    private Path resolvePath(String storageDir) {
        Path legacyPath = Path.of("device.id");
        if (Files.exists(legacyPath)) {
            return legacyPath;
        }
        return Path.of(storageDir, "device.id");
    }

    public String deviceId() {
        return deviceId;
    }
}
