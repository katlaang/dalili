package dalili.com.base.application.controller;

import dalili.com.base.application.service.AddendumService;
import dalili.com.base.application.service.EncounterService;
import dalili.com.base.domain.encounter.model.*;
import dalili.com.base.domain.triage.TriageAssessment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for clinical encounter management.
 *
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Pre-encounter preview with triage data</li>
 *   <li>Encounter creation and management</li>
 *   <li>Clinical documentation (transcript, AI draft, physician note)</li>
 *   <li>Diagnoses and medication orders</li>
 *   <li>Prescription generation and printing</li>
 *   <li>Encounter completion</li>
 *   <li>Addendum management for completed encounters</li>
 * </ul>
 * </p>
 *
 * <h3>Immutability Rules:</h3>
 * <ul>
 *   <li>Completed encounters cannot be modified</li>
 *   <li>Use addendum endpoints for corrections</li>
 *   <li>Encounters cannot be deleted</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/encounters")
@Tag(name = "Encounters", description = "Clinical encounter management APIs")
public class EncounterController {

    private final EncounterService encounterService;
    private final AddendumService addendumService;

    public EncounterController(
            EncounterService encounterService,
            AddendumService addendumService
    ) {
        this.encounterService = encounterService;
        this.addendumService = addendumService;
    }

    // ==================== PRE-ENCOUNTER ====================

    @Operation(
            summary = "Get pre-encounter preview",
            description = "Returns comprehensive patient information before consultation including " +
                    "triage data, vitals, red flags, medication history, and clinical alerts."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preview retrieved"),
            @ApiResponse(responseCode = "404", description = "Queue ticket or patient not found")
    })
    @GetMapping("/preview/queue/{queueTicketId}")
    public ResponseEntity<?> getPreEncounterPreview(
            @Parameter(description = "Queue ticket UUID")
            @PathVariable UUID queueTicketId
    ) {
        try {
            EncounterPreview preview = encounterService.getPreEncounterPreview(queueTicketId);
            return ResponseEntity.ok(preview);
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== ENCOUNTER CREATION ====================

    @Operation(
            summary = "Create encounter from queue ticket",
            description = "Creates a new encounter linked to a queue ticket and triage assessment."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Encounter created"),
            @ApiResponse(responseCode = "400", description = "Invalid request or encounter already exists")
    })
    @PostMapping("/from-queue")
    public ResponseEntity<?> createFromQueue(@RequestBody CreateFromQueueRequest request) {
        try {
            Encounter encounter = encounterService.createFromQueue(
                    request.queueTicketId(),
                    request.encounterType()
            );
            return ResponseEntity.ok(EncounterResponse.from(encounter));
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Create standalone encounter",
            description = "Creates an encounter not linked to queue (e.g., walk-in or follow-up)."
    )
    @PostMapping("/standalone")
    public ResponseEntity<?> createStandalone(@RequestBody CreateStandaloneRequest request) {
        try {
            Encounter encounter = encounterService.createStandalone(
                    request.patientId(),
                    request.encounterType(),
                    request.chiefComplaint()
            );
            return ResponseEntity.ok(EncounterResponse.from(encounter));
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== CLINICAL NOTES ====================

    @Operation(
            summary = "Record transcript",
            description = "Records the ambient AI transcript. Can only be done once per encounter."
    )
    @PostMapping("/{encounterId}/transcript")
    public ResponseEntity<?> recordTranscript(
            @PathVariable UUID encounterId,
            @RequestBody TranscriptRequest request
    ) {
        try {
            Encounter encounter = encounterService.recordTranscript(encounterId, request.transcript());
            return ResponseEntity.ok(EncounterResponse.from(encounter));
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Record AI draft",
            description = "Records AI-generated SOAP note. Requires transcript. Can only be done once."
    )
    @PostMapping("/{encounterId}/ai-draft")
    public ResponseEntity<?> recordAiDraft(
            @PathVariable UUID encounterId,
            @RequestBody AiDraftRequest request
    ) {
        try {
            Encounter encounter = encounterService.recordAiDraft(
                    encounterId,
                    request.draftNote(),
                    request.modelVersion(),
                    request.promptVersion()
            );
            return ResponseEntity.ok(EncounterResponse.from(encounter));
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Record physician note",
            description = "Records physician's own authored note. Can be edited until confirmation."
    )
    @PostMapping("/{encounterId}/physician-note")
    public ResponseEntity<?> recordPhysicianNote(
            @PathVariable UUID encounterId,
            @RequestBody PhysicianNoteRequest request
    ) {
        try {
            Encounter encounter = encounterService.recordPhysicianNote(encounterId, request.note());
            return ResponseEntity.ok(EncounterResponse.from(encounter));
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Confirm final note",
            description = "Confirms the final note with AI accuracy rating. Can only be done once. " +
                    "After confirmation, documentation becomes immutable."
    )
    @PostMapping("/{encounterId}/confirm-note")
    public ResponseEntity<?> confirmNote(
            @PathVariable UUID encounterId,
            @RequestBody ConfirmNoteRequest request
    ) {
        try {
            Encounter encounter = encounterService.confirmNote(
                    encounterId,
                    request.finalNote(),
                    request.accuracyRating(),
                    request.correctionComments()
            );
            return ResponseEntity.ok(EncounterResponse.from(encounter));
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== DIAGNOSES ====================

    @Operation(summary = "Add diagnosis")
    @PostMapping("/{encounterId}/diagnoses")
    public ResponseEntity<?> addDiagnosis(
            @PathVariable UUID encounterId,
            @RequestBody DiagnosisRequest request
    ) {
        try {
            Encounter encounter = encounterService.addDiagnosis(
                    encounterId,
                    request.icdCode(),
                    request.description(),
                    request.isPrimary(),
                    request.type()
            );
            return ResponseEntity.ok(EncounterResponse.from(encounter));
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(summary = "Remove diagnosis")
    @DeleteMapping("/{encounterId}/diagnoses/{diagnosisId}")
    public ResponseEntity<?> removeDiagnosis(
            @PathVariable UUID encounterId,
            @PathVariable UUID diagnosisId
    ) {
        try {
            Encounter encounter = encounterService.removeDiagnosis(encounterId, diagnosisId);
            return ResponseEntity.ok(EncounterResponse.from(encounter));
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== MEDICATION ORDERS ====================

    @Operation(summary = "Add medication order")
    @PostMapping("/{encounterId}/medications")
    public ResponseEntity<?> addMedicationOrder(
            @PathVariable UUID encounterId,
            @RequestBody MedicationRequest request
    ) {
        try {
            EncounterService.MedicationOrderInput input = new EncounterService.MedicationOrderInput(
                    request.medicationName(),
                    request.brandName(),
                    request.dosage(),
                    request.dosageForm(),
                    request.frequency(),
                    request.route(),
                    request.durationDays(),
                    request.quantity(),
                    request.instructions(),
                    request.indication()
            );
            Encounter encounter = encounterService.addMedicationOrder(encounterId, input);
            return ResponseEntity.ok(EncounterResponse.from(encounter));
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(summary = "Remove medication order")
    @DeleteMapping("/{encounterId}/medications/{medicationOrderId}")
    public ResponseEntity<?> removeMedicationOrder(
            @PathVariable UUID encounterId,
            @PathVariable UUID medicationOrderId
    ) {
        try {
            Encounter encounter = encounterService.removeMedicationOrder(encounterId, medicationOrderId);
            return ResponseEntity.ok(EncounterResponse.from(encounter));
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== PRESCRIPTION ====================

    @Operation(
            summary = "Generate prescription",
            description = "Generates printable prescription content for the encounter."
    )
    @GetMapping("/{encounterId}/prescription")
    public ResponseEntity<?> generatePrescription(@PathVariable UUID encounterId) {
        try {
            EncounterService.PrescriptionContent content = encounterService.generatePrescription(encounterId);
            return ResponseEntity.ok(content);
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Mark prescription printed",
            description = "Records that the prescription was printed for pharmacy."
    )
    @PostMapping("/{encounterId}/prescription/print")
    public ResponseEntity<?> markPrescriptionPrinted(@PathVariable UUID encounterId) {
        try {
            Encounter encounter = encounterService.markPrescriptionPrinted(encounterId);
            return ResponseEntity.ok(EncounterResponse.from(encounter));
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== ENCOUNTER COMPLETION ====================

    @Operation(summary = "Get completion readiness")
    @GetMapping("/{encounterId}/completion-readiness")
    public ResponseEntity<?> getCompletionReadiness(@PathVariable UUID encounterId) {
        try {
            EncounterService.CompletionReadiness readiness = encounterService.getCompletionReadiness(encounterId);
            return ResponseEntity.ok(readiness);
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Complete encounter",
            description = "Completes the encounter. Requires confirmed note. " +
                    "After completion, encounter becomes immutable."
    )
    @PostMapping("/{encounterId}/complete")
    public ResponseEntity<?> completeEncounter(@PathVariable UUID encounterId) {
        try {
            Encounter encounter = encounterService.completeEncounter(encounterId);
            return ResponseEntity.ok(EncounterResponse.from(encounter));
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(summary = "Cancel encounter")
    @PostMapping("/{encounterId}/cancel")
    public ResponseEntity<?> cancelEncounter(
            @PathVariable UUID encounterId,
            @RequestBody CancelRequest request
    ) {
        try {
            Encounter encounter = encounterService.cancelEncounter(encounterId, request.reason());
            return ResponseEntity.ok(EncounterResponse.from(encounter));
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== RETRIEVAL ====================

    @Operation(summary = "Get encounter by ID")
    @GetMapping("/{encounterId}")
    public ResponseEntity<?> getEncounter(@PathVariable UUID encounterId) {
        Optional<Encounter> encounter = encounterService.findById(encounterId);
        if (encounter.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(EncounterResponse.from(encounter.get()));
    }

    @Operation(summary = "Get triage data for encounter")
    @GetMapping("/{encounterId}/triage")
    public ResponseEntity<?> getTriageData(@PathVariable UUID encounterId) {
        try {
            Optional<TriageAssessment> triage = encounterService.getTriageData(encounterId);
            if (triage.isEmpty()) {
                return ResponseEntity.ok(new ErrorResponse("No triage assessment linked"));
            }
            return ResponseEntity.ok(triage.get());
        } catch (EncounterService.EncounterException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(summary = "Get patient encounter history")
    @GetMapping("/patient/{patientId}")
    public ResponseEntity<?> getPatientEncounters(@PathVariable UUID patientId) {
        List<Encounter> encounters = encounterService.getPatientEncounters(patientId);
        List<EncounterSummaryResponse> summaries = encounters.stream()
                .map(EncounterSummaryResponse::from)
                .toList();
        return ResponseEntity.ok(summaries);
    }

    @Operation(summary = "Get my open encounters")
    @GetMapping("/my/open")
    public ResponseEntity<?> getMyOpenEncounters() {
        List<Encounter> encounters = encounterService.getMyOpenEncounters();
        List<EncounterSummaryResponse> summaries = encounters.stream()
                .map(EncounterSummaryResponse::from)
                .toList();
        return ResponseEntity.ok(summaries);
    }

    // ==================== ADDENDUMS ====================

    @Operation(
            summary = "Create addendum",
            description = "Creates an addendum to a completed encounter. " +
                    "This is the only way to add information to a completed encounter."
    )
    @PostMapping("/{encounterId}/addendums")
    public ResponseEntity<?> createAddendum(
            @PathVariable UUID encounterId,
            @RequestBody AddendumRequest request
    ) {
        try {
            EncounterAddendum addendum = addendumService.createAddendum(
                    encounterId,
                    request.type(),
                    request.reason(),
                    request.content()
            );
            return ResponseEntity.ok(AddendumResponse.from(addendum));
        } catch (AddendumService.AddendumException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(summary = "Create diagnosis correction addendum")
    @PostMapping("/{encounterId}/addendums/diagnosis-correction")
    public ResponseEntity<?> createDiagnosisCorrection(
            @PathVariable UUID encounterId,
            @RequestBody DiagnosisCorrectionRequest request
    ) {
        try {
            EncounterAddendum addendum = addendumService.createDiagnosisCorrection(
                    encounterId,
                    request.originalIcdCode(),
                    request.correctedIcdCode(),
                    request.explanation()
            );
            return ResponseEntity.ok(AddendumResponse.from(addendum));
        } catch (AddendumService.AddendumException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(summary = "Create medication correction addendum")
    @PostMapping("/{encounterId}/addendums/medication-correction")
    public ResponseEntity<?> createMedicationCorrection(
            @PathVariable UUID encounterId,
            @RequestBody MedicationCorrectionRequest request
    ) {
        try {
            EncounterAddendum addendum = addendumService.createMedicationCorrection(
                    encounterId,
                    request.originalMedication(),
                    request.correctedMedication(),
                    request.explanation()
            );
            return ResponseEntity.ok(AddendumResponse.from(addendum));
        } catch (AddendumService.AddendumException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(summary = "Create late entry addendum")
    @PostMapping("/{encounterId}/addendums/late-entry")
    public ResponseEntity<?> createLateEntry(
            @PathVariable UUID encounterId,
            @RequestBody LateEntryRequest request
    ) {
        try {
            EncounterAddendum addendum = addendumService.createLateEntry(
                    encounterId,
                    request.content(),
                    request.delayReason()
            );
            return ResponseEntity.ok(AddendumResponse.from(addendum));
        } catch (AddendumService.AddendumException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(summary = "Get addendums for encounter")
    @GetMapping("/{encounterId}/addendums")
    public ResponseEntity<?> getAddendums(@PathVariable UUID encounterId) {
        List<EncounterAddendum> addendums = addendumService.getAddendums(encounterId);
        List<AddendumResponse> responses = addendums.stream()
                .map(AddendumResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Verify addendum integrity")
    @GetMapping("/{encounterId}/addendums/verify-integrity")
    public ResponseEntity<?> verifyAddendumIntegrity(@PathVariable UUID encounterId) {
        AddendumService.IntegrityVerificationResult result = addendumService.verifyIntegrity(encounterId);
        return ResponseEntity.ok(result);
    }

    // ==================== DTOs ====================

    @Schema(description = "Create encounter from queue request")
    public record CreateFromQueueRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID queueTicketId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Encounter.EncounterType encounterType
    ) {
    }

    @Schema(description = "Create standalone encounter request")
    public record CreateStandaloneRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID patientId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Encounter.EncounterType encounterType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String chiefComplaint
    ) {
    }

    @Schema(description = "Transcript request")
    public record TranscriptRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String transcript
    ) {
    }

    @Schema(description = "AI draft request")
    public record AiDraftRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String draftNote,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String modelVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String promptVersion
    ) {
    }

    @Schema(description = "Physician note request")
    public record PhysicianNoteRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String note
    ) {
    }

    @Schema(description = "Confirm note request")
    public record ConfirmNoteRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String finalNote,
            @Schema(description = "Required if AI draft exists") Encounter.NoteAccuracyRating accuracyRating,
            String correctionComments
    ) {
    }

    @Schema(description = "Diagnosis request")
    public record DiagnosisRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "J06.9") String icdCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Acute upper respiratory infection") String description,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isPrimary,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Diagnosis.DiagnosisType type
    ) {
    }

    @Schema(description = "Medication request")
    public record MedicationRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String medicationName,
            String brandName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String dosage,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MedicationOrder.DosageForm dosageForm,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "TDS") String frequency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MedicationOrder.RouteOfAdministration route,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int durationDays,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int quantity,
            String instructions,
            String indication
    ) {
    }

    @Schema(description = "Cancel request")
    public record CancelRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason
    ) {
    }

    @Schema(description = "Addendum request")
    public record AddendumRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EncounterAddendum.AddendumType type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EncounterAddendum.AddendumReason reason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String content
    ) {
    }

    @Schema(description = "Diagnosis correction request")
    public record DiagnosisCorrectionRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String originalIcdCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String correctedIcdCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String explanation
    ) {
    }

    @Schema(description = "Medication correction request")
    public record MedicationCorrectionRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String originalMedication,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String correctedMedication,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String explanation
    ) {
    }

    @Schema(description = "Late entry request")
    public record LateEntryRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String content,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String delayReason
    ) {
    }

    @Schema(description = "Encounter response")
    public record EncounterResponse(
            UUID id,
            UUID patientId,
            UUID queueTicketId,
            UUID triageAssessmentId,
            UUID clinicianId,
            String clinicianName,
            String clinicianRole,
            Encounter.EncounterType encounterType,
            Encounter.EncounterStatus status,
            String chiefComplaint,
            boolean hasTranscript,
            boolean hasAiDraft,
            boolean hasPhysicianNote,
            boolean noteConfirmed,
            Encounter.NoteAccuracyRating aiAccuracyRating,
            int diagnosisCount,
            int medicationCount,
            String startedAt,
            String completedAt,
            boolean canModify,
            boolean canHaveAddendum
    ) {
        public static EncounterResponse from(Encounter e) {
            return new EncounterResponse(
                    e.getId(),
                    e.getPatientId(),
                    e.getQueueTicketId(),
                    e.getTriageAssessmentId(),
                    e.getClinicianId(),
                    e.getClinicianName(),
                    e.getClinicianRole(),
                    e.getEncounterType(),
                    e.getStatus(),
                    e.getChiefComplaint(),
                    e.hasTranscript(),
                    e.hasAiDraft(),
                    e.hasPhysicianNote(),
                    e.isNoteConfirmed(),
                    e.getAiAccuracyRating(),
                    e.getDiagnoses().size(),
                    e.getMedicationOrders().size(),
                    e.getStartedAt().toString(),
                    e.getCompletedAt() != null ? e.getCompletedAt().toString() : null,
                    !e.isTerminal() && !e.isNoteConfirmed(),
                    e.canHaveAddendum()
            );
        }
    }

    @Schema(description = "Encounter summary response")
    public record EncounterSummaryResponse(
            UUID id,
            Encounter.EncounterType encounterType,
            Encounter.EncounterStatus status,
            String chiefComplaint,
            String clinicianName,
            String startedAt,
            String completedAt,
            int diagnosisCount,
            int medicationCount,
            long addendumCount
    ) {
        public static EncounterSummaryResponse from(Encounter e) {
            return new EncounterSummaryResponse(
                    e.getId(),
                    e.getEncounterType(),
                    e.getStatus(),
                    e.getChiefComplaint(),
                    e.getClinicianName(),
                    e.getStartedAt().toString(),
                    e.getCompletedAt() != null ? e.getCompletedAt().toString() : null,
                    e.getDiagnoses().size(),
                    e.getMedicationOrders().size(),
                    0 // Would need to query addendum count
            );
        }
    }

    @Schema(description = "Addendum response")
    public record AddendumResponse(
            UUID id,
            UUID encounterId,
            UUID parentAddendumId,
            EncounterAddendum.AddendumType type,
            EncounterAddendum.AddendumReason reason,
            String content,
            String sectionReference,
            String authorName,
            String authorRole,
            String authorCredentials,
            String createdAt,
            String signatureLine,
            boolean isCorrection,
            boolean integrityValid
    ) {
        public static AddendumResponse from(EncounterAddendum a) {
            return new AddendumResponse(
                    a.getId(),
                    a.getEncounterId(),
                    a.getParentAddendumId(),
                    a.getType(),
                    a.getReason(),
                    a.getContent(),
                    a.getSectionReference(),
                    a.getAuthorName(),
                    a.getAuthorRole(),
                    a.getAuthorCredentials(),
                    a.getCreatedAt().toString(),
                    a.getSignatureLine(),
                    a.isCorrection(),
                    a.verifyIntegrity()
            );
        }
    }

    @Schema(description = "Error response")
    public record ErrorResponse(String error) {
    }
}
