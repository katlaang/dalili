package dalili.com.base.domain.session.repository;

import dalili.com.base.domain.session.model.KioskSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface KioskSessionRepository extends JpaRepository<KioskSession, UUID> {
}
