package dalili.com.dalili.domain.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface ActiveSessionRepository extends JpaRepository<ActiveSession, UUID> {

    @Modifying
    @Transactional
    @Query("DELETE FROM ActiveSession s WHERE s.lastActivity < :cutoff")
    int deleteInactiveSessions(Instant cutoff);
}
