package dalili.com.base.infra.audit.anchoring;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AnchorRepository extends JpaRepository<AnchorRecord, UUID> {
    List<AnchorRecord> findAllByOrderByAnchorTimeDesc();
}
