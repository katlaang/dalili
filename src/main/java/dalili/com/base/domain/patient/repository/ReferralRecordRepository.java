package dalili.com.base.domain.patient.repository;

import dalili.com.base.domain.patient.model.ReferralRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReferralRecordRepository extends JpaRepository<ReferralRecord, UUID> {

    List<ReferralRecord> findByPatientIdOrderByReferredAtDesc(UUID patientId);
}

