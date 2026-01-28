package dalili.com.dalili.infra.persistence;

import dalili.com.dalili.domain.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PatientRepository extends JpaRepository<Patient, UUID> {
    // read-only access; all auditing enforced in service layer
}
