package dalili.com.base.application.service;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.encounter.model.Encounter;
import dalili.com.base.domain.encounter.model.EncounterAddendum;
import dalili.com.base.domain.encounter.repository.EncounterAddendumRepository;
import dalili.com.base.domain.encounter.repository.EncounterRepository;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing encounter addendums.
 *
 * <p>Addendums provide the only mechanism to add information to completed
 * encounters. This service ensures:
 * <ul>
 *   <li>Addendums can only be added to completed encounters</li>
 *   <li>Addendums are immutable once created</li>
 *   <li>All addendums are fully audited</li>
 *   <li>Proper attribution and signing</li>
 * </ul>
 * </p>
 *
 * <h3>Security Model:</h3>
 * <p>Any clinician can add an addendum to any encounter they have access to.
 * The addendum is attributed to the creating clinician, not the original
 * encounter author. This supports multi-provider care scenarios.</p>
 *
 * @see EncounterAddendum
 * @see Encounter
 */
@Service
public class AddendumService {

    private final EncounterAddendumRepository addendumRepository;
    private final EncounterRepository encounterRepository;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;

    /**
     * Constructs a new AddendumService.
     */
    public AddendumService(
            EncounterAddendumRepository addendumRepository,
            EncounterRepository encounterRepository,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext
    ) {
        this.addendumRepository = addendumRepository;
        this.encounterRepository = encounterRepository;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
    }

    // ==================== ADDENDUM CREATION ====================

    /**
     * Creates a new addendum to a completed encounter.
     *
     * @param encounterId the completed encounter UUID
     * @param type        the type of addendum
     * @param reason      the reason for the addendum
     * @param content     the addendum content
     * @return the created addendum
     * @throws AddendumException if encounter not found or not completed
     */
    @Transactional
    public EncounterAddendum createAddendum(
            UUID encounterId,
            EncounterAddendum.AddendumType type,
            EncounterAddendum.AddendumReason reason,
            String content
    ) {
        auditGuard.assertSessionActive();

        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new AddendumException("Encounter not found"));

        try {
            encounter.assertCanHaveAddendum();
        } catch (IllegalStateException e) {
            throw new AddendumException(e.getMessage());
        }

        UUID authorId = sessionContext.userId();
        String authorName = sessionContext.username();
        String authorRole = sessionContext.role() != null ? sessionContext.role().name() : "CLINICIAN";

        EncounterAddendum addendum;
        try {
            addendum = EncounterAddendum.create(
                    encounterId,
                    encounter.getPatientId(),
                    type,
                    reason,
                    content,
                    authorId,
                    authorName,
                    authorRole
            );
        } catch (IllegalArgumentException e) {
            throw new AddendumException("Invalid addendum: " + e.getMessage());
        }

        addendum = addendumRepository.save(addendum);

        auditService.record("ADDENDUM_CREATED", encounter.getPatientId(),
                String.format("Addendum added to encounter. Type: %s, Reason: %s", type, reason));

        return addendum;
    }

    /**
     * Creates an addendum with section reference.
     *
     * @param encounterId      the completed encounter UUID
     * @param type             the type of addendum
     * @param reason           the reason for the addendum
     * @param content          the addendum content
     * @param sectionReference the section being amended
     * @return the created addendum
     */
    @Transactional
    public EncounterAddendum createAddendumForSection(
            UUID encounterId,
            EncounterAddendum.AddendumType type,
            EncounterAddendum.AddendumReason reason,
            String content,
            String sectionReference
    ) {
        auditGuard.assertSessionActive();

        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new AddendumException("Encounter not found"));

        try {
            encounter.assertCanHaveAddendum();
        } catch (IllegalStateException e) {
            throw new AddendumException(e.getMessage());
        }

        UUID authorId = sessionContext.userId();
        String authorName = sessionContext.username();
        String authorRole = sessionContext.role() != null ? sessionContext.role().name() : "CLINICIAN";

        EncounterAddendum addendum = EncounterAddendum.create(
                encounterId,
                encounter.getPatientId(),
                type,
                reason,
                content,
                authorId,
                authorName,
                authorRole
        ).withSectionReference(sectionReference);

        addendum = addendumRepository.save(addendum);

        auditService.record("ADDENDUM_CREATED", encounter.getPatientId(),
                String.format("Addendum added to section '%s'. Type: %s", sectionReference, type));

        return addendum;
    }

    /**
     * Creates a diagnosis correction addendum.
     *
     * @param encounterId      the completed encounter UUID
     * @param originalIcdCode  the original incorrect ICD-10 code
     * @param correctedIcdCode the correct ICD-10 code
     * @param explanation      explanation for the correction
     * @return the created addendum
     */
    @Transactional
    public EncounterAddendum createDiagnosisCorrection(
            UUID encounterId,
            String originalIcdCode,
            String correctedIcdCode,
            String explanation
    ) {
        auditGuard.assertSessionActive();

        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new AddendumException("Encounter not found"));

        try {
            encounter.assertCanHaveAddendum();
        } catch (IllegalStateException e) {
            throw new AddendumException(e.getMessage());
        }

        UUID authorId = sessionContext.userId();
        String authorName = sessionContext.username();
        String authorRole = sessionContext.role() != null ? sessionContext.role().name() : "CLINICIAN";

        String content = String.format(
                "DIAGNOSIS CORRECTION\n\n" +
                        "Original Diagnosis: %s\n" +
                        "Corrected Diagnosis: %s\n\n" +
                        "Explanation: %s",
                originalIcdCode, correctedIcdCode, explanation
        );

        EncounterAddendum addendum = EncounterAddendum.create(
                        encounterId,
                        encounter.getPatientId(),
                        EncounterAddendum.AddendumType.CORRECTION,
                        EncounterAddendum.AddendumReason.ERROR_CORRECTION,
                        content,
                        authorId,
                        authorName,
                        authorRole
                )
                .withSectionReference("DIAGNOSIS")
                .withDiagnosisCorrection(originalIcdCode, correctedIcdCode);

        addendum = addendumRepository.save(addendum);

        auditService.record("DIAGNOSIS_CORRECTION", encounter.getPatientId(),
                String.format("Diagnosis corrected: %s → %s", originalIcdCode, correctedIcdCode));

        return addendum;
    }

    /**
     * Creates a medication correction addendum.
     *
     * @param encounterId         the completed encounter UUID
     * @param originalMedication  the original incorrect medication
     * @param correctedMedication the correct medication (or "DISCONTINUED")
     * @param explanation         explanation for the correction
     * @return the created addendum
     */
    @Transactional
    public EncounterAddendum createMedicationCorrection(
            UUID encounterId,
            String originalMedication,
            String correctedMedication,
            String explanation
    ) {
        auditGuard.assertSessionActive();

        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new AddendumException("Encounter not found"));

        try {
            encounter.assertCanHaveAddendum();
        } catch (IllegalStateException e) {
            throw new AddendumException(e.getMessage());
        }

        UUID authorId = sessionContext.userId();
        String authorName = sessionContext.username();
        String authorRole = sessionContext.role() != null ? sessionContext.role().name() : "CLINICIAN";

        String content = String.format(
                "MEDICATION CORRECTION\n\n" +
                        "Original Medication: %s\n" +
                        "Corrected Medication: %s\n\n" +
                        "Explanation: %s",
                originalMedication, correctedMedication, explanation
        );

        EncounterAddendum addendum = EncounterAddendum.create(
                        encounterId,
                        encounter.getPatientId(),
                        EncounterAddendum.AddendumType.CORRECTION,
                        EncounterAddendum.AddendumReason.ERROR_CORRECTION,
                        content,
                        authorId,
                        authorName,
                        authorRole
                )
                .withSectionReference("MEDICATIONS")
                .withMedicationCorrection(originalMedication, correctedMedication);

        addendum = addendumRepository.save(addendum);

        auditService.record("MEDICATION_CORRECTION", encounter.getPatientId(),
                String.format("Medication corrected: %s → %s", originalMedication, correctedMedication));

        return addendum;
    }

    /**
     * Creates a correction to an existing addendum.
     *
     * @param addendumId the addendum to correct
     * @param reason     the correction reason
     * @param content    the correction content
     * @return the correction addendum
     */
    @Transactional
    public EncounterAddendum createAddendumCorrection(
            UUID addendumId,
            EncounterAddendum.AddendumReason reason,
            String content
    ) {
        auditGuard.assertSessionActive();

        EncounterAddendum parentAddendum = addendumRepository.findById(addendumId)
                .orElseThrow(() -> new AddendumException("Addendum not found"));

        UUID authorId = sessionContext.userId();
        String authorName = sessionContext.username();
        String authorRole = sessionContext.role() != null ? sessionContext.role().name() : "CLINICIAN";

        EncounterAddendum correction;
        try {
            correction = EncounterAddendum.createCorrection(
                    parentAddendum,
                    reason,
                    content,
                    authorId,
                    authorName,
                    authorRole
            );
        } catch (IllegalArgumentException e) {
            throw new AddendumException("Invalid correction: " + e.getMessage());
        }

        correction = addendumRepository.save(correction);

        auditService.record("ADDENDUM_CORRECTED", parentAddendum.getPatientId(),
                String.format("Correction added to addendum %s", addendumId));

        return correction;
    }

    /**
     * Creates a late entry addendum.
     *
     * <p>Late entries are used when documentation was delayed beyond the
     * normal timeframe. They require additional justification.</p>
     *
     * @param encounterId the completed encounter UUID
     * @param content     the late entry content
     * @param delayReason reason for the delayed documentation
     * @return the created addendum
     */
    @Transactional
    public EncounterAddendum createLateEntry(
            UUID encounterId,
            String content,
            String delayReason
    ) {
        auditGuard.assertSessionActive();

        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new AddendumException("Encounter not found"));

        try {
            encounter.assertCanHaveAddendum();
        } catch (IllegalStateException e) {
            throw new AddendumException(e.getMessage());
        }

        UUID authorId = sessionContext.userId();
        String authorName = sessionContext.username();
        String authorRole = sessionContext.role() != null ? sessionContext.role().name() : "CLINICIAN";

        String fullContent = String.format(
                "LATE ENTRY\n\n" +
                        "Reason for Delay: %s\n\n" +
                        "Documentation:\n%s",
                delayReason, content
        );

        EncounterAddendum addendum = EncounterAddendum.create(
                encounterId,
                encounter.getPatientId(),
                EncounterAddendum.AddendumType.LATE_ENTRY,
                EncounterAddendum.AddendumReason.DELAYED_DOCUMENTATION,
                fullContent,
                authorId,
                authorName,
                authorRole
        );

        addendum = addendumRepository.save(addendum);

        auditService.record("LATE_ENTRY_CREATED", encounter.getPatientId(),
                "Late entry addendum created");

        return addendum;
    }

    // ==================== RETRIEVAL ====================

    /**
     * Gets all addendums for an encounter in chronological order.
     *
     * @param encounterId the encounter UUID
     * @return list of addendums
     */
    public List<EncounterAddendum> getAddendums(UUID encounterId) {
        return addendumRepository.findByEncounterIdOrderByCreatedAtAsc(encounterId);
    }

    /**
     * Gets a specific addendum by ID.
     *
     * @param addendumId the addendum UUID
     * @return the addendum if found
     */
    public Optional<EncounterAddendum> findById(UUID addendumId) {
        return addendumRepository.findById(addendumId);
    }

    /**
     * Gets corrections to a specific addendum.
     *
     * @param addendumId the parent addendum UUID
     * @return list of corrections
     */
    public List<EncounterAddendum> getCorrections(UUID addendumId) {
        return addendumRepository.findByParentAddendumIdOrderByCreatedAtAsc(addendumId);
    }

    /**
     * Gets diagnosis corrections for an encounter.
     *
     * @param encounterId the encounter UUID
     * @return list of diagnosis correction addendums
     */
    public List<EncounterAddendum> getDiagnosisCorrections(UUID encounterId) {
        return addendumRepository.findDiagnosisCorrections(encounterId);
    }

    /**
     * Gets medication corrections for an encounter.
     *
     * @param encounterId the encounter UUID
     * @return list of medication correction addendums
     */
    public List<EncounterAddendum> getMedicationCorrections(UUID encounterId) {
        return addendumRepository.findMedicationCorrections(encounterId);
    }

    /**
     * Checks if an encounter has any addendums.
     *
     * @param encounterId the encounter UUID
     * @return true if addendums exist
     */
    public boolean hasAddendums(UUID encounterId) {
        return addendumRepository.existsByEncounterId(encounterId);
    }

    /**
     * Counts addendums for an encounter.
     *
     * @param encounterId the encounter UUID
     * @return count of addendums
     */
    public long countAddendums(UUID encounterId) {
        return addendumRepository.countByEncounterId(encounterId);
    }

    /**
     * Verifies integrity of all addendums for an encounter.
     *
     * @param encounterId the encounter UUID
     * @return verification result
     */
    public IntegrityVerificationResult verifyIntegrity(UUID encounterId) {
        List<EncounterAddendum> addendums = getAddendums(encounterId);

        int total = addendums.size();
        int valid = 0;
        int invalid = 0;
        List<UUID> invalidIds = new java.util.ArrayList<>();

        for (EncounterAddendum addendum : addendums) {
            if (addendum.verifyIntegrity()) {
                valid++;
            } else {
                invalid++;
                invalidIds.add(addendum.getId());
            }
        }

        return new IntegrityVerificationResult(total, valid, invalid, invalidIds);
    }

    // ==================== RECORDS ====================

    /**
     * Result of integrity verification.
     */
    public record IntegrityVerificationResult(
            int totalAddendums,
            int validAddendums,
            int invalidAddendums,
            List<UUID> invalidAddendumIds
    ) {
        public boolean isFullyValid() {
            return invalidAddendums == 0;
        }
    }

    /**
     * Exception for addendum-related errors.
     */
    public static class AddendumException extends RuntimeException {
        public AddendumException(String message) {
            super(message);
        }
    }
}
