package dalili.com.base.domain.patient.repository;

import dalili.com.base.domain.patient.model.PatientDataTransferRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PatientDataTransferRequestRepository extends JpaRepository<PatientDataTransferRequest, UUID> {

    List<PatientDataTransferRequest> findByPatientIdOrderByRequestedAtDesc(UUID patientId);

    List<PatientDataTransferRequest> findByStatusOrderByRequestedAtAsc(PatientDataTransferRequest.TransferStatus status);
}

