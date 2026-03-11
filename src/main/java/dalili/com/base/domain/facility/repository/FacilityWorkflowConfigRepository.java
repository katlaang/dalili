package dalili.com.base.domain.facility.repository;

import dalili.com.base.domain.facility.model.FacilityWorkflowConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FacilityWorkflowConfigRepository extends JpaRepository<FacilityWorkflowConfig, UUID> {
    Optional<FacilityWorkflowConfig> findByFacilityCode(String facilityCode);
}
