package dalili.com.base.domain.queue;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Daily counter for issuing queue numbers atomically across kiosk and reception.
 */
@Entity
@Table(
        name = "queue_ticket_counters",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_queue_ticket_counter_date_category",
                        columnNames = {"queueDate", "category"}
                )
        },
        indexes = {
                @Index(name = "idx_queue_ticket_counter_date_category", columnList = "queueDate, category")
        }
)
@Getter
public class QueueTicketCounter {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private LocalDate queueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private QueueTicket.QueueCategory category;

    @Column(nullable = false)
    private int lastIssuedSequence;

    @Column(nullable = false)
    private Instant updatedAt;

    protected QueueTicketCounter() {
    }

    public static QueueTicketCounter initialize(LocalDate queueDate, QueueTicket.QueueCategory category) {
        if (queueDate == null) {
            throw new IllegalArgumentException("queueDate is required");
        }
        if (category == null) {
            throw new IllegalArgumentException("category is required");
        }
        QueueTicketCounter counter = new QueueTicketCounter();
        counter.queueDate = queueDate;
        counter.category = category;
        counter.lastIssuedSequence = 0;
        counter.updatedAt = Instant.now();
        return counter;
    }

    public int reserveNextSequence() {
        this.lastIssuedSequence += 1;
        this.updatedAt = Instant.now();
        return this.lastIssuedSequence;
    }
}
