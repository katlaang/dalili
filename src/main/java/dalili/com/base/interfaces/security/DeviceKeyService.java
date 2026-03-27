package dalili.com.base.interfaces.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class DeviceKeyService {

    private final Path keyFile;

    public DeviceKeyService(
            @Value("${dalili.storage.dir:${user.home}/.dalili}") String storageDir
    ) {
        this.keyFile = resolvePath(storageDir);
    }

    public String getDeviceKey() {
        try {
            if (Files.exists(keyFile)) {
                String existing = Files.readString(keyFile).trim();
                if (!existing.isBlank()) {
                    return existing;
                }
            }
            String key = UUID.randomUUID().toString();
            Path parent = keyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(keyFile, key);
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Device key unavailable", e);
        }
    }

    private Path resolvePath(String storageDir) {
        Path legacyPath = Path.of("device.key");
        if (Files.exists(legacyPath)) {
            return legacyPath;
        }
        return Path.of(storageDir, "device.key");
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

