package dalili.com.base.domain.patient.repository;

import dalili.com.base.domain.patient.model.PortalMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PortalMessageRepository extends JpaRepository<PortalMessage, UUID> {

    List<PortalMessage> findByPatientIdOrderByCreatedAtDesc(UUID patientId);

    @Query("SELECT m FROM PortalMessage m WHERE m.direction = 'FROM_PATIENT' " +
            "AND (m.recipientId IS NULL OR m.recipientId = :staffId) " +
            "AND (:patientId IS NULL OR m.patientId = :patientId) " +
            "ORDER BY m.createdAt DESC")
    List<PortalMessage> findClinicianInbox(
            String staffId,
            UUID patientId
    );
}

