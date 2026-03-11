package dalili.com.base.domain.patient.repository;

import dalili.com.base.domain.patient.model.LabResultRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LabResultRecordRepository extends JpaRepository<LabResultRecord, UUID> {

    List<LabResultRecord> findByPatientIdOrderByRecordedAtDesc(UUID patientId);
}

