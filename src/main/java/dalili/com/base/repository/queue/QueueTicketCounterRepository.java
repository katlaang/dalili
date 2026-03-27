package dalili.com.base.repository.queue;

import dalili.com.base.domain.queue.QueueTicket;
import dalili.com.base.domain.queue.QueueTicketCounter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QueueTicketCounterRepository extends JpaRepository<QueueTicketCounter, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM QueueTicketCounter c WHERE c.queueDate = :queueDate AND c.category = :category")
    Optional<QueueTicketCounter> findByQueueDateAndCategoryForUpdate(
            LocalDate queueDate,
            QueueTicket.QueueCategory category
    );
}
