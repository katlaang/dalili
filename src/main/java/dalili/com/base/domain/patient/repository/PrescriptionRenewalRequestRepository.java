package dalili.com.base.domain.patient.repository;

import dalili.com.base.domain.patient.model.PrescriptionRenewalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PrescriptionRenewalRequestRepository extends JpaRepository<PrescriptionRenewalRequest, UUID> {

    List<PrescriptionRenewalRequest> findByPatientIdOrderByRequestedAtDesc(UUID patientId);

    boolean existsByMedicationOrderIdAndStatus(
            UUID medicationOrderId,
            PrescriptionRenewalRequest.RenewalStatus status
    );

    List<PrescriptionRenewalRequest> findByStatusAndOrderedByStaffIdOrderByRequestedAtAsc(
            PrescriptionRenewalRequest.RenewalStatus status,
            String orderedByStaffId
    );

    List<PrescriptionRenewalRequest> findByStatusOrderByRequestedAtAsc(
            PrescriptionRenewalRequest.RenewalStatus status
    );
}

