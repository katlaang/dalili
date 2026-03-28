package dalili.com.base.application.controller;

import dalili.com.base.application.service.ClinicalAiService;
import dalili.com.base.application.service.TriageService;
import dalili.com.base.domain.triage.TriageAssessment;
import dalili.com.base.domain.triage.TriageLevel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for triage assessment operations.
 *
 * <p>This controller provides endpoints for:
 * <ul>
 *   <li>Beginning triage assessments</li>
 *   <li>Recording vital signs and clinical observations</li>
 *   <li>Recording red flag indicators</li>
 *   <li>Accepting or overriding system triage recommendations</li>
 *   <li>Viewing assessment history</li>
 * </ul>
 * </p>
 *
 * <p>Access is restricted to nurses and clinical staff with triage authority.</p>
 */
@RestController
@RequestMapping("/api/triage")
@Tag(name = "Triage Assessment", description = "APIs for clinical triage assessment and vital sign recording")
public class TriageController {

    private static final Logger log = LoggerFactory.getLogger(TriageController.class);

    private final TriageService triageService;
    private final ClinicalAiService clinicalAiService;

    /**
     * Constructs a new TriageController.
     *
     * @param triageService the triage service
     */
    public TriageController(TriageService triageService, ClinicalAiService clinicalAiService) {
        this.triageService = triageService;
        this.clinicalAiService = clinicalAiService;
    }

    // ==================== ASSESSMENT CREATION ====================

    /**
     * Begins a new triage assessment for a queue ticket.
     *
     * @param request the begin assessment request
     * @return the new assessment
     */
    @Operation(
            summary = "Begin triage assessment",
            description = "Starts a new triage assessment for a patient in the queue. " +
                    "Creates an assessment record linked to the queue ticket."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assessment started successfully",
                    content = @Content(schema = @Schema(implementation = AssessmentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or ticket not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/begin")
    public ResponseEntity<?> beginAssessment(@RequestBody BeginAssessmentRequest request) {
        try {
            log.info("Begin triage assessment request queueTicketId={}", request.queueTicketId());
            TriageAssessment assessment = triageService.beginAssessment(
                    request.queueTicketId(),
                    request.chiefComplaint()
            );
            log.info("Triage assessment started assessmentId={} patientId={}", assessment.getId(), assessment.getPatientId());
            return ResponseEntity.ok(AssessmentResponse.from(assessment));
        } catch (TriageService.TriageException e) {
            log.warn("Begin triage assessment failed queueTicketId={} reason={}", request.queueTicketId(), e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Begins a reassessment for a waiting patient.
     *
     * @param queueTicketId the queue ticket UUID
     * @return the new reassessment
     */
    @Operation(
            summary = "Begin reassessment",
            description = "Starts a reassessment for a patient whose condition may have changed while waiting."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reassessment started successfully",
                    content = @Content(schema = @Schema(implementation = AssessmentResponse.class))),
            @ApiResponse(responseCode = "400", description = "No previous assessment found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/reassess/{queueTicketId}")
    public ResponseEntity<?> beginReassessment(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID queueTicketId
    ) {
        try {
            TriageAssessment assessment = triageService.beginReassessment(queueTicketId);
            return ResponseEntity.ok(AssessmentResponse.from(assessment));
        } catch (TriageService.TriageException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== VITAL SIGNS RECORDING ====================

    /**
     * Records vital signs for an assessment.
     *
     * @param assessmentId the assessment UUID
     * @param request      the vital signs data
     * @return the updated assessment with system triage suggestion
     */
    @Operation(
            summary = "Record vital signs",
            description = "Records vital signs for a triage assessment. " +
                    "After recording, the system calculates a suggested triage level."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Vitals recorded successfully",
                    content = @Content(schema = @Schema(implementation = AssessmentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or assessment not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{assessmentId}/vitals")
    public ResponseEntity<?> recordVitals(
            @Parameter(description = "Assessment UUID") @PathVariable UUID assessmentId,
            @RequestBody VitalsRequest request
    ) {
        try {
            log.info("Record triage vitals request assessmentId={}", assessmentId);
            TriageService.VitalsInput vitals = new TriageService.VitalsInput(
                    request.temperatureCelsius(),
                    request.heartRateBpm(),
                    request.bloodPressureSystolic(),
                    request.bloodPressureDiastolic(),
                    request.respiratoryRate(),
                    request.oxygenSaturation(),
                    request.weightKg(),
                    request.heightCm(),
                    request.painScore(),
                    request.bloodGlucoseMmol(),
                    request.consciousnessLevel()
            );
            TriageAssessment assessment = triageService.recordVitals(assessmentId, vitals);
            log.info("Triage vitals recorded assessmentId={} patientId={} suggestedLevel={}",
                    assessment.getId(), assessment.getPatientId(), assessment.getSystemTriageLevel());
            return ResponseEntity.ok(AssessmentResponse.from(assessment));
        } catch (TriageService.TriageException e) {
            log.warn("Record triage vitals failed assessmentId={} reason={}", assessmentId, e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Records red flag indicators.
     *
     * @param assessmentId the assessment UUID
     * @param request      the red flag data
     * @return the updated assessment
     */
    @Operation(
            summary = "Record red flags",
            description = "Records red flag indicators that may indicate life-threatening conditions. " +
                    "Triggers recalculation of suggested triage level."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Red flags recorded successfully",
                    content = @Content(schema = @Schema(implementation = AssessmentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Assessment not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{assessmentId}/red-flags")
    public ResponseEntity<?> recordRedFlags(
            @Parameter(description = "Assessment UUID") @PathVariable UUID assessmentId,
            @RequestBody RedFlagsRequest request
    ) {
        try {
            TriageService.RedFlagsInput redFlags = new TriageService.RedFlagsInput(
                    request.chestPain(),
                    request.difficultyBreathing(),
                    request.strokeSymptoms(),
                    request.severebleeding(),
                    request.allergicReaction(),
                    request.alteredMentalStatus(),
                    request.pregnancyConcern(),
                    request.severeAbdominalPain()
            );
            TriageAssessment assessment = triageService.recordRedFlags(assessmentId, redFlags);
            return ResponseEntity.ok(AssessmentResponse.from(assessment));
        } catch (TriageService.TriageException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Records clinical observations and patient history.
     *
     * @param assessmentId the assessment UUID
     * @param request      the clinical observations
     * @return the updated assessment
     */
    @Operation(
            summary = "Record clinical observations",
            description = "Records clinical observations including history of present illness, " +
                    "allergies, medications, and nursing notes."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Observations recorded successfully",
                    content = @Content(schema = @Schema(implementation = AssessmentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Assessment not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{assessmentId}/observations")
    public ResponseEntity<?> recordObservations(
            @Parameter(description = "Assessment UUID") @PathVariable UUID assessmentId,
            @RequestBody ObservationsRequest request
    ) {
        try {
            TriageService.ClinicalObservationsInput observations = new TriageService.ClinicalObservationsInput(
                    request.historyOfPresentIllness(),
                    request.allergies(),
                    request.currentMedications(),
                    request.pastMedicalHistory(),
                    request.nursingNotes(),
                    request.emergencyContactName(),
                    request.emergencyContactPhone(),
                    request.pregnancyStatus(),
                    request.vulnerabilityIndicators(),
                    request.vulnerabilityNotes(),
                    request.lastMenstrualPeriodDate(),
                    request.remembersLastMenstrualPeriod(),
                    request.pregnancyTestStatus(),
                    request.fetalHealthCheckRequired(),
                    request.fetalHealthNotes(),
                    request.manualRedFlag(),
                    request.manualRedFlagReason()
            );
            TriageAssessment assessment = triageService.recordClinicalObservations(assessmentId, observations);
            return ResponseEntity.ok(AssessmentResponse.from(assessment));
        } catch (TriageService.TriageException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== TRIAGE FINALIZATION ====================

    /**
     * Accepts the system's triage recommendation.
     *
     * @param assessmentId the assessment UUID
     * @return the finalized assessment
     */
    @Operation(
            summary = "Accept system triage",
            description = "Accepts the system-calculated triage level and finalizes the assessment. " +
                    "Updates the queue ticket priority accordingly."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Triage accepted successfully",
                    content = @Content(schema = @Schema(implementation = AssessmentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Assessment not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{assessmentId}/accept")
    public ResponseEntity<?> acceptSystemTriage(
            @Parameter(description = "Assessment UUID") @PathVariable UUID assessmentId
    ) {
        try {
            log.info("Accept triage request assessmentId={}", assessmentId);
            TriageAssessment assessment = triageService.acceptSystemTriage(assessmentId);
            log.info("Triage accepted assessmentId={} patientId={} finalLevel={}",
                    assessment.getId(), assessment.getPatientId(), assessment.getFinalTriageLevel());
            return ResponseEntity.ok(AssessmentResponse.from(assessment));
        } catch (TriageService.TriageException e) {
            log.warn("Accept triage failed assessmentId={} reason={}", assessmentId, e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Overrides the system's triage recommendation.
     *
     * @param assessmentId the assessment UUID
     * @param request      the override request with reason
     * @return the finalized assessment
     */
    @Operation(
            summary = "Override triage",
            description = "Overrides the system triage recommendation with nurse clinical judgment. " +
                    "Requires a documented clinical reason for audit purposes."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Triage overridden successfully",
                    content = @Content(schema = @Schema(implementation = AssessmentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or override reason missing",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{assessmentId}/override")
    public ResponseEntity<?> overrideTriage(
            @Parameter(description = "Assessment UUID") @PathVariable UUID assessmentId,
            @RequestBody OverrideRequest request
    ) {
        try {
            log.info("Override triage request assessmentId={} newLevel={}", assessmentId, request.newTriageLevel());
            TriageAssessment assessment = triageService.overrideTriage(
                    assessmentId,
                    request.newTriageLevel(),
                    request.reason()
            );
            log.info("Triage overridden assessmentId={} patientId={} finalLevel={}",
                    assessment.getId(), assessment.getPatientId(), assessment.getFinalTriageLevel());
            return ResponseEntity.ok(AssessmentResponse.from(assessment));
        } catch (TriageService.TriageException e) {
            log.warn("Override triage failed assessmentId={} reason={}", assessmentId, e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== RETRIEVAL ====================

    /**
     * Gets an assessment by ID.
     *
     * @param assessmentId the assessment UUID
     * @return the assessment details
     */
    @Operation(
            summary = "Get assessment by ID",
            description = "Returns details for a specific triage assessment."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assessment found",
                    content = @Content(schema = @Schema(implementation = AssessmentResponse.class))),
            @ApiResponse(responseCode = "404", description = "Assessment not found")
    })
    @GetMapping("/{assessmentId}")
    public ResponseEntity<?> getAssessment(
            @Parameter(description = "Assessment UUID") @PathVariable UUID assessmentId
    ) {
        return triageService.findById(assessmentId)
                .map(a -> ResponseEntity.ok(AssessmentResponse.from(a)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Gets the current assessment for a queue ticket.
     *
     * @param queueTicketId the queue ticket UUID
     * @return the most recent assessment
     */
    @Operation(
            summary = "Get assessment for ticket",
            description = "Returns the most recent triage assessment for a queue ticket."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assessment found",
                    content = @Content(schema = @Schema(implementation = AssessmentResponse.class))),
            @ApiResponse(responseCode = "404", description = "No assessment found for ticket")
    })
    @GetMapping("/ticket/{queueTicketId}")
    public ResponseEntity<?> getAssessmentForTicket(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID queueTicketId
    ) {
        return triageService.findAssessmentForTicket(queueTicketId)
                .map(a -> ResponseEntity.ok(AssessmentResponse.from(a)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Gets all assessments for a queue ticket.
     *
     * @param queueTicketId the queue ticket UUID
     * @return list of assessments
     */
    @Operation(
            summary = "Get assessment history for ticket",
            description = "Returns all triage assessments for a queue ticket including reassessments."
    )
    @ApiResponse(responseCode = "200", description = "Assessment history retrieved")
    @GetMapping("/ticket/{queueTicketId}/history")
    public ResponseEntity<List<AssessmentResponse>> getAssessmentHistory(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID queueTicketId
    ) {
        List<AssessmentResponse> history = triageService.getAssessmentHistoryForTicket(queueTicketId)
                .stream()
                .map(AssessmentResponse::from)
                .toList();
        return ResponseEntity.ok(history);
    }

    /**
     * Gets the triage summary explaining system recommendation.
     *
     * @param assessmentId the assessment UUID
     * @return the triage summary
     */
    @Operation(
            summary = "Get triage summary",
            description = "Returns a summary explaining the clinical factors that influenced " +
                    "the system's triage calculation."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Summary retrieved"),
            @ApiResponse(responseCode = "404", description = "Assessment not found")
    })
    @GetMapping("/{assessmentId}/summary")
    public ResponseEntity<?> getTriageSummary(
            @Parameter(description = "Assessment UUID") @PathVariable UUID assessmentId
    ) {
        try {
            String summary = triageService.getTriageSummary(assessmentId);
            return ResponseEntity.ok(new TriageSummaryResponse(summary));
        } catch (TriageService.TriageException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(
            summary = "Get triage outcome with queue impact and AI suggestions",
            description = "Returns final triage output, queue position impact, and AI-assisted differential suggestions."
    )
    @PostMapping("/{assessmentId}/outcome")
    public ResponseEntity<?> getTriageOutcome(
            @Parameter(description = "Assessment UUID") @PathVariable UUID assessmentId,
            @RequestBody(required = false) OutcomeRequest request
    ) {
        try {
            String physicalExam = request != null ? request.physicalExam() : null;
            var outcome = clinicalAiService.generateTriageOutcome(assessmentId, physicalExam);
            return ResponseEntity.ok(outcome);
        } catch (ClinicalAiService.ClinicalAiException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== DTOs ====================

    /**
     * Request to begin a triage assessment.
     */
    @Schema(description = "Request to begin a triage assessment")
    public record BeginAssessmentRequest(
            @Schema(description = "Queue ticket UUID", required = true)
            UUID queueTicketId,

            @Schema(description = "Patient's chief complaint", required = true,
                    example = "Severe headache for 3 days with nausea")
            String chiefComplaint
    ) {
    }

    /**
     * Request to record vital signs.
     */
    @Schema(description = "Vital signs data")
    public record VitalsRequest(
            @Schema(description = "Temperature in Celsius", example = "37.5")
            BigDecimal temperatureCelsius,

            @Schema(description = "Heart rate in BPM", example = "85")
            Integer heartRateBpm,

            @Schema(description = "Systolic blood pressure in mmHg", example = "120")
            Integer bloodPressureSystolic,

            @Schema(description = "Diastolic blood pressure in mmHg", example = "80")
            Integer bloodPressureDiastolic,

            @Schema(description = "Respiratory rate in breaths/min", example = "16")
            Integer respiratoryRate,

            @Schema(description = "Oxygen saturation SpO2 percentage", example = "98")
            Integer oxygenSaturation,

            @Schema(description = "Weight in kilograms", example = "70.5")
            BigDecimal weightKg,

            @Schema(description = "Height in centimeters", example = "175")
            BigDecimal heightCm,

            @Schema(description = "Pain score 0-10", example = "5")
            Integer painScore,

            @Schema(description = "Blood glucose in mmol/L", example = "5.2")
            BigDecimal bloodGlucoseMmol,

            @Schema(description = "Level of consciousness (AVPU scale)")
            TriageAssessment.ConsciousnessLevel consciousnessLevel
    ) {
    }

    /**
     * Request to record red flag indicators.
     */
    @Schema(description = "Red flag indicators for critical symptoms")
    public record RedFlagsRequest(
            @Schema(description = "Chest pain or discomfort")
            boolean chestPain,

            @Schema(description = "Difficulty breathing")
            boolean difficultyBreathing,

            @Schema(description = "Stroke symptoms (FAST positive)")
            boolean strokeSymptoms,

            @Schema(description = "Severe or uncontrolled bleeding")
            boolean severebleeding,

            @Schema(description = "Signs of severe allergic reaction")
            boolean allergicReaction,

            @Schema(description = "Altered mental status or confusion")
            boolean alteredMentalStatus,

            @Schema(description = "Pregnancy with concerning symptoms")
            boolean pregnancyConcern,

            @Schema(description = "Severe abdominal pain")
            boolean severeAbdominalPain
    ) {
    }

    /**
     * Request to record clinical observations.
     */
    @Schema(description = "Clinical observations and patient history")
    public record ObservationsRequest(
            @Schema(description = "History of present illness",
                    example = "Headache started 3 days ago, gradual onset, constant, 7/10 severity")
            String historyOfPresentIllness,

            @Schema(description = "Known allergies", example = "Penicillin - rash")
            String allergies,

            @Schema(description = "Current medications", example = "Lisinopril 10mg daily, Metformin 500mg BD")
            String currentMedications,

            @Schema(description = "Past medical history", example = "Hypertension, Type 2 Diabetes")
            String pastMedicalHistory,

            @Schema(description = "Nursing notes and observations",
                    example = "Patient appears distressed, holding head. Photophobia noted.")
            String nursingNotes,

            @Schema(description = "Emergency contact name")
            String emergencyContactName,

            @Schema(description = "Emergency contact phone")
            String emergencyContactPhone,

            @Schema(description = "Pregnancy status")
            TriageAssessment.PregnancyStatus pregnancyStatus,

            @Schema(description = "Selected vulnerability indicators")
            java.util.Set<TriageAssessment.VulnerabilityIndicator> vulnerabilityIndicators,

            @Schema(description = "Free-form vulnerability notes")
            String vulnerabilityNotes,

            @Schema(description = "Last menstrual period date")
            java.time.LocalDate lastMenstrualPeriodDate,

            @Schema(description = "Whether the patient remembers the last menstrual period date")
            Boolean remembersLastMenstrualPeriod,

            @Schema(description = "Pregnancy test workflow status")
            TriageAssessment.PregnancyTestStatus pregnancyTestStatus,

            @Schema(description = "Whether fetal health should be checked")
            boolean fetalHealthCheckRequired,

            @Schema(description = "Fetal health notes or concerns")
            String fetalHealthNotes,

            @Schema(description = "Manual nurse-defined red flag")
            boolean manualRedFlag,

            @Schema(description = "Reason for the manual red flag")
            String manualRedFlagReason
    ) {
    }

    /**
     * Request to override triage level.
     */
    @Schema(description = "Request to override system triage recommendation")
    public record OverrideRequest(
            @Schema(description = "New triage level", required = true, example = "ORANGE")
            TriageLevel newTriageLevel,

            @Schema(description = "Clinical reason for override", required = true,
                    example = "Patient has history of MI, atypical presentation warrants higher acuity")
            String reason
    ) {
    }

    /**
     * Error response.
     */
    @Schema(description = "Error response")
    public record ErrorResponse(
            @Schema(description = "Error message")
            String error
    ) {
    }

    /**
     * Triage summary response.
     */
    @Schema(description = "Triage calculation summary")
    public record TriageSummaryResponse(
            @Schema(description = "Summary explaining triage factors")
            String summary
    ) {
    }

    @Schema(description = "Optional additional clinical exam input for outcome generation")
    public record OutcomeRequest(
            @Schema(description = "Physical exam summary to improve differential suggestions")
            String physicalExam
    ) {
    }

    /**
     * Triage assessment response.
     */
    @Schema(description = "Triage assessment details")
    public record AssessmentResponse(
            @Schema(description = "Assessment UUID")
            UUID id,

            @Schema(description = "Patient UUID")
            UUID patientId,

            @Schema(description = "Queue ticket UUID")
            UUID queueTicketId,

            @Schema(description = "Chief complaint")
            String chiefComplaint,

            // Vital signs
            @Schema(description = "Temperature in Celsius")
            BigDecimal temperatureCelsius,

            @Schema(description = "Heart rate in BPM")
            Integer heartRateBpm,

            @Schema(description = "Systolic blood pressure")
            Integer bloodPressureSystolic,

            @Schema(description = "Diastolic blood pressure")
            Integer bloodPressureDiastolic,

            @Schema(description = "Respiratory rate")
            Integer respiratoryRate,

            @Schema(description = "Oxygen saturation")
            Integer oxygenSaturation,

            @Schema(description = "Weight in kg")
            BigDecimal weightKg,

            @Schema(description = "Height in cm")
            BigDecimal heightCm,

            @Schema(description = "Pain score 0-10")
            Integer painScore,

            @Schema(description = "Blood glucose in mmol/L")
            BigDecimal bloodGlucoseMmol,

            @Schema(description = "Level of consciousness")
            TriageAssessment.ConsciousnessLevel consciousnessLevel,

            @Schema(description = "Calculated BMI")
            BigDecimal bmi,

            @Schema(description = "Mean arterial pressure")
            Integer meanArterialPressure,

            // Red flags
            @Schema(description = "Whether any red flags are present")
            boolean hasRedFlags,

            @Schema(description = "Chest pain present")
            boolean chestPain,

            @Schema(description = "Difficulty breathing")
            boolean difficultyBreathing,

            @Schema(description = "Stroke symptoms")
            boolean strokeSymptoms,

            @Schema(description = "Severe bleeding")
            boolean severebleeding,

            @Schema(description = "Allergic reaction")
            boolean allergicReaction,

            @Schema(description = "Altered mental status")
            boolean alteredMentalStatus,

            @Schema(description = "Pregnancy concern")
            boolean pregnancyConcern,

            @Schema(description = "Severe abdominal pain")
            boolean severeAbdominalPain,

            // Clinical observations
            @Schema(description = "History of present illness")
            String historyOfPresentIllness,

            @Schema(description = "Allergies")
            String allergies,

            @Schema(description = "Current medications")
            String currentMedications,

            @Schema(description = "Past medical history")
            String pastMedicalHistory,

            @Schema(description = "Nursing notes")
            String nursingNotes,

            @Schema(description = "Emergency contact name")
            String emergencyContactName,

            @Schema(description = "Emergency contact phone")
            String emergencyContactPhone,

            @Schema(description = "Pregnancy status")
            TriageAssessment.PregnancyStatus pregnancyStatus,

            @Schema(description = "Whether active pregnancy is recorded")
            boolean pregnant,

            @Schema(description = "Whether patient is a newborn (28 days or younger)")
            boolean newborn,

            @Schema(description = "Selected vulnerability indicators")
            java.util.Set<TriageAssessment.VulnerabilityIndicator> vulnerabilityIndicators,

            @Schema(description = "Free-form vulnerability notes")
            String vulnerabilityNotes,

            @Schema(description = "Last menstrual period date")
            java.time.LocalDate lastMenstrualPeriodDate,

            @Schema(description = "Whether the patient remembers the last menstrual period date")
            Boolean remembersLastMenstrualPeriod,

            @Schema(description = "Pregnancy test workflow status")
            TriageAssessment.PregnancyTestStatus pregnancyTestStatus,

            @Schema(description = "Whether fetal health should be checked")
            boolean fetalHealthCheckRequired,

            @Schema(description = "Fetal health notes or concerns")
            String fetalHealthNotes,

            @Schema(description = "Manual nurse-defined red flag")
            boolean manualRedFlag,

            @Schema(description = "Reason for the manual red flag")
            String manualRedFlagReason,

            // Triage classification
            @Schema(description = "System-calculated triage level")
            TriageLevel systemTriageLevel,

            @Schema(description = "Final triage level (may differ from system)")
            TriageLevel finalTriageLevel,

            @Schema(description = "Whether nurse overrode system recommendation")
            boolean triageOverridden,

            @Schema(description = "Reason for override if applicable")
            String overrideReason,

            // Patient info
            @Schema(description = "Patient age in years")
            int patientAgeYears,

            @Schema(description = "Whether patient is pediatric (<18)")
            boolean pediatric,

            @Schema(description = "Whether patient is elderly (65+)")
            boolean elderly,

            // Metadata
            @Schema(description = "Assessment timestamp")
            Instant assessedAt,

            @Schema(description = "Staff who performed assessment")
            String assessedByStaffName,

            @Schema(description = "Whether this is a reassessment")
            boolean isReassessment
    ) {
        /**
         * Creates an AssessmentResponse from a TriageAssessment entity.
         *
         * @param a the triage assessment
         * @return the response DTO
         */
        public static AssessmentResponse from(TriageAssessment a) {
            return new AssessmentResponse(
                    a.getId(),
                    a.getPatientId(),
                    a.getQueueTicketId(),
                    a.getChiefComplaint(),
                    a.getTemperatureCelsius(),
                    a.getHeartRateBpm(),
                    a.getBloodPressureSystolic(),
                    a.getBloodPressureDiastolic(),
                    a.getRespiratoryRate(),
                    a.getOxygenSaturation(),
                    a.getWeightKg(),
                    a.getHeightCm(),
                    a.getPainScore(),
                    a.getBloodGlucoseMmol(),
                    a.getConsciousnessLevel(),
                    a.getBmi(),
                    a.getMeanArterialPressure(),
                    a.hasRedFlags(),
                    a.isChestPain(),
                    a.isDifficultyBreathing(),
                    a.isStrokeSymptoms(),
                    a.isSeverebleeding(),
                    a.isAllergicReaction(),
                    a.isAlteredMentalStatus(),
                    a.isPregnancyConcern(),
                    a.isSevereAbdominalPain(),
                    a.getHistoryOfPresentIllness(),
                    a.getAllergies(),
                    a.getCurrentMedications(),
                    a.getPastMedicalHistory(),
                    a.getNursingNotes(),
                    a.getEmergencyContactName(),
                    a.getEmergencyContactPhone(),
                    a.getPregnancyStatus(),
                    a.isPregnant(),
                    a.isNewborn(),
                    a.getVulnerabilityIndicators(),
                    a.getVulnerabilityNotes(),
                    a.getLastMenstrualPeriodDate(),
                    a.getRemembersLastMenstrualPeriod(),
                    a.getPregnancyTestStatus(),
                    a.isFetalHealthCheckRequired(),
                    a.getFetalHealthNotes(),
                    a.isManualRedFlag(),
                    a.getManualRedFlagReason(),
                    a.getSystemTriageLevel(),
                    a.getFinalTriageLevel(),
                    a.isTriageOverridden(),
                    a.getOverrideReason(),
                    a.getPatientAgeYears(),
                    a.isPediatric(),
                    a.isElderly(),
                    a.getAssessedAt(),
                    a.getAssessedByStaffName(),
                    a.isReassessment()
            );
        }
    }
}


