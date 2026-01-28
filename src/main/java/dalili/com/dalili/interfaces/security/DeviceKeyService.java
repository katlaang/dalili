package dalili.com.dalili.interfaces.security;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class DeviceKeyService {

    private static final Path KEY_FILE = Path.of("device.key");

    public String getDeviceKey() {
        try {
            if (Files.exists(KEY_FILE)) {
                return Files.readString(KEY_FILE);
            }
            String key = UUID.randomUUID().toString();
            Files.writeString(KEY_FILE, key);
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Device key unavailable", e);
        }
    }

    public String sign(String payload) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash =
                    digest.digest(
                            (payload + getDeviceKey()).getBytes()
                    );

            return HexFormat.of().formatHex(hash);

        } catch (Exception e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }
}

