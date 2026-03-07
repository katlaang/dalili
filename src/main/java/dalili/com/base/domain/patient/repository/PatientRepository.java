package dalili.com.base.domain.patient.repository;


import dalili.com.base.domain.patient.model.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientRepository extends JpaRepository<Patient, UUID> {

    Optional<Patient> findByMrn(String mrn);

    Optional<Patient> findByNationalId(String nationalId);

    Optional<Patient> findByMrnAndActiveTrue(String mrn);

    boolean existsByMrn(String mrn);

    boolean existsByNationalId(String nationalId);
}
