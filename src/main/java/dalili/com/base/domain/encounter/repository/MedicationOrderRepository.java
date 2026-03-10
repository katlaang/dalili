package dalili.com.base.domain.encounter.repository;

import dalili.com.base.domain.encounter.model.MedicationOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for medication order persistence and queries.
 */
@Repository
public interface MedicationOrderRepository extends JpaRepository<MedicationOrder, UUID> {

    /**
     * Finds orders for a patient ordered by date descending.
     *
     * @param patientId the patient UUID
     * @return list of medication orders
     */
    List<MedicationOrder> findByPatientIdOrderByOrderedAtDesc(UUID patientId);

    /**
     * Finds orders by status.
     *
     * @param status the order status
     * @return list of orders
     */
    List<MedicationOrder> findByStatus(MedicationOrder.OrderStatus status);

    /**
     * Finds orders for an encounter.
     *
     * @param encounterId the encounter UUID
     * @return list of orders for the encounter
     */
    List<MedicationOrder> findByEncounterId(UUID encounterId);

    /**
     * Finds pending orders (printed but not dispensed).
     *
     * @param status should be PRINTED
     * @return list of pending orders
     */
    List<MedicationOrder> findByStatusOrderByPrintedAtAsc(MedicationOrder.OrderStatus status);
}
