package dalili.com.base.domain.patient.repository;

import dalili.com.base.domain.patient.model.PatientDataAuthorization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PatientDataAuthorizationRepository extends JpaRepository<PatientDataAuthorization, UUID> {

    List<PatientDataAuthorization> findByPatientIdOrderByGrantedAtDesc(UUID patientId);

    List<PatientDataAuthorization> findByPatientIdAndFacilityCodeOrderByGrantedAtDesc(
            UUID patientId,
            String facilityCode
    );

    @Query("SELECT a FROM PatientDataAuthorization a WHERE a.patientId = :patientId " +
            "AND a.facilityCode = :facilityCode " +
            "AND a.revoked = false " +
            "AND (a.expiresAt IS NULL OR a.expiresAt > :now) " +
            "ORDER BY a.grantedAt DESC")
    List<PatientDataAuthorization> findActiveByPatientAndFacility(
            UUID patientId,
            String facilityCode,
            Instant now
    );
}

