package dalili.com.dalili.infra.audit;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class DeviceContext {

    private final String deviceId;

    public DeviceContext() {
        this.deviceId = loadOrCreate();
    }

    private String loadOrCreate() {
        try {
            Path path = Path.of("device.id");
            if (Files.exists(path)) {
                return Files.readString(path);
            }
            String id = "KIOSK-" + UUID.randomUUID();
            Files.writeString(path, id);
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize device ID", e);
        }
    }

    public String deviceId() {
        return deviceId;
    }
}
