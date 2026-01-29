package dalili.com.dalili.domain.session.repository;

import dalili.com.dalili.domain.session.model.KioskSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface KioskSessionRepository extends JpaRepository<KioskSession, UUID> {
}
