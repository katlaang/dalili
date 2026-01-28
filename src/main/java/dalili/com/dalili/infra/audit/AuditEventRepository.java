package dalili.com.dalili.infra.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    // Used only to build the hash chain
    Optional<AuditEvent> findTopByOrderByTimestampDesc();

    // For verifying the audit chain end-to-end
    List<AuditEvent> findAllByOrderByTimestampAsc();
}
