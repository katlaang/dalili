package dalili.com.base.application.service;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.ai.*;
import dalili.com.base.domain.encounter.model.Encounter;
import dalili.com.base.domain.encounter.repository.EncounterRepository;
import dalili.com.base.domain.patient.model.Patient;
import dalili.com.base.domain.queue.QueueTicket;
import dalili.com.base.domain.triage.TriageAssessment;
import dalili.com.base.domain.triage.TriageCalculator;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import dalili.com.base.repository.queue.QueueTicketRepository;
import dalili.com.base.repository.triage.TriageAssessmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * ClinicalAiService orchestrates AI functions across triage and encounters.
 */
@Service
public class ClinicalAiService {

    private static final Logger log = LoggerFactory.getLogger(ClinicalAiService.class);

    private final EncounterRepository encounterRepository;
    private final TriageAssessmentRepository triageRepository;
    private final QueueTicketRepository queueRepository;
    private final PatientService patientService;
    private final DifferentialService differentialService;
    private final SoapExtractionService soapExtractionService;
    private final AmbientTranscriptionService ambientTranscriptionService;
    private final NoteComparisonService noteComparisonService;
    private final TriageCalculator triageCalculator;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;

    public ClinicalAiService(
            EncounterRepository encounterRepository,
            TriageAssessmentRepository triageRepository,
            QueueTicketRepository queueRepository,
            PatientService patientService,
            DifferentialService differentialService,
            SoapExtractionService soapExtractionService,
            AmbientTranscriptionService ambientTranscriptionService,
            NoteComparisonService noteComparisonService,
            TriageCalculator triageCalculator,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext
    ) {
        this.encounterRepository = encounterRepository;
        this.triageRepository = triageRepository;
        this.queueRepository = queueRepository;
        this.patientService = patientService;
        this.differentialService = differentialService;
        this.soapExtractionService = soapExtractionService;
        this.ambientTranscriptionService = ambientTranscriptionService;
        this.noteComparisonService = noteComparisonService;
        this.triageCalculator = triageCalculator;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
    }

    public AmbientTranscriptionService.TranscriptionResult transcribeEncounterAudio(
            UUID encounterId,
            byte[] audioBytes,
            String fileName,
            String mimeType,
            String language,
            String prompt
    ) {
        auditGuard.assertSessionActive();
        log.info("Transcribing ambient audio encounterId={} bytes={}", encounterId, audioBytes == null ? 0 : audioBytes.length);

        Encounter encounter = findEncounterOrThrow(encounterId);
        AmbientTranscriptionService.TranscriptionResult result = ambientTranscriptionService.transcribe(
                audioBytes, fileName, mimeType, language, prompt
        );

        auditService.record(
                "AMBIENT_TRANSCRIPTION",
                encounter.getPatientId(),
                result.available()
                        ? String.format("Ambient transcript generated (%d ms)", result.latencyMs())
                        : "Ambient transcription unavailable: " + result.errorMessage()
        );

        log.info("Ambient transcription completed encounterId={} available={} latencyMs={}",
                encounterId, result.available(), result.latencyMs());
        return result;
    }

    public BackgroundTranscriptionQueuedResult queueEncounterAudioTranscription(
            UUID encounterId,
            byte[] audioBytes,
            String fileName,
            String mimeType,
            String language,
            String prompt
    ) {
        auditGuard.assertSessionActive();

        if (audioBytes == null || audioBytes.length == 0) {
            throw new ClinicalAiException("Audio payload is empty");
        }

        Encounter encounter = findEncounterOrThrow(encounterId);
        if (encounter.hasTranscript()) {
            throw new ClinicalAiException("Transcript already exists for this encounter");
        }

        try {
            encounter.queueAmbientTranscription();
            encounterRepository.save(encounter);
        } catch (IllegalStateException e) {
            throw new ClinicalAiException(e.getMessage());
        }

        String requestedBy = sessionContext.username() != null ? sessionContext.username() : "unknown";
        auditService.record(
                "AMBIENT_TRANSCRIPTION_QUEUED",
                encounter.getPatientId(),
                "Background transcription queued by " + requestedBy
        );

        byte[] payloadCopy = Arrays.copyOf(audioBytes, audioBytes.length);
        java.util.concurrent.CompletableFuture.runAsync(() -> processBackgroundTranscription(
                encounterId,
                payloadCopy,
                fileName,
                mimeType,
                language,
                prompt,
                requestedBy
        ));

        return new BackgroundTranscriptionQueuedResult(
                encounterId,
                "QUEUED",
                true,
                Instant.now(),
                "Audio saved for background transcription and encounter can be completed."
        );
    }

    @Transactional
    public AiDraftGenerationResult generateSoapDraftFromEncounter(
            UUID encounterId,
            boolean persist,
            String promptVersion
    ) {
        auditGuard.assertSessionActive();
        log.info("Generating AI SOAP draft encounterId={} persist={}", encounterId, persist);

        Encounter encounter = findEncounterOrThrow(encounterId);
        String transcript = encounter.getTranscript();
        if (transcript == null || transcript.isBlank()) {
            throw new ClinicalAiException("Encounter transcript is required before generating AI draft");
        }

        SoapExtractionService.DraftResult draft = soapExtractionService.extractDraft(transcript);
        boolean persisted = false;

        if (persist && draft.available() && draft.draftNote() != null) {
            try {
                encounter.recordAiDraft(
                        draft.draftNote(),
                        draft.model() != null ? draft.model() : "unknown",
                        promptVersion != null && !promptVersion.isBlank() ? promptVersion : "ambient-soap-v1"
                );
                encounterRepository.save(encounter);
                persisted = true;
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw new ClinicalAiException(e.getMessage());
            }
        }

        auditService.record(
                "AI_SOAP_DRAFT_GENERATED",
                encounter.getPatientId(),
                draft.available()
                        ? String.format("SOAP draft generated (%d ms). Persisted=%s", draft.latencyMs(), persisted)
                        : "SOAP draft unavailable: " + draft.errorMessage()
        );

        AiDraftGenerationResult result = new AiDraftGenerationResult(
                encounterId,
                draft.available(),
                draft.draftNote(),
                draft.extractedSymptoms(),
                draft.flaggedItems(),
                draft.confidence(),
                draft.provider(),
                draft.model(),
                draft.latencyMs(),
                draft.generatedAt(),
                persisted,
                draft.errorMessage()
        );
        log.info("AI SOAP draft generation completed encounterId={} available={} persisted={}",
                encounterId, result.available(), result.persisted());
        return result;
    }

    public DifferentialResult generateEncounterDifferential(UUID encounterId, String physicalExam) {
        auditGuard.assertSessionActive();
        log.info("Generating encounter differential encounterId={}", encounterId);

        Encounter encounter = findEncounterOrThrow(encounterId);
        Patient patient = patientService.findById(encounter.getPatientId());
        Optional<TriageAssessment> triage = resolveTriage(encounter);

        DifferentialInput input = buildDifferentialInput(
                patient,
                triage.orElse(null),
                encounter.getChiefComplaint(),
                physicalExam
        );

        DifferentialResult result = differentialService.generate(input);
        auditService.record(
                "ENCOUNTER_DIFFERENTIAL_GENERATED",
                encounter.getPatientId(),
                result.available()
                        ? String.format("Generated %d differential suggestions", result.differentials().size())
                        : "Differential unavailable: " + result.errorMessage()
        );
        log.info("Encounter differential generated encounterId={} available={} suggestionCount={}",
                encounterId, result.available(), result.differentials() == null ? 0 : result.differentials().size());
        return result;
    }

    @Transactional
    public TriageOutcomeResult generateTriageOutcome(UUID assessmentId, String physicalExam) {
        auditGuard.assertSessionActive();
        log.info("Generating triage outcome assessmentId={}", assessmentId);

        TriageAssessment assessment = triageRepository.findById(assessmentId)
                .orElseThrow(() -> new ClinicalAiException("Assessment not found"));
        QueueTicket queueTicket = queueRepository.findById(assessment.getQueueTicketId())
                .orElseThrow(() -> new ClinicalAiException("Queue ticket not found"));
        Patient patient = patientService.findById(assessment.getPatientId());

        DifferentialInput input = buildDifferentialInput(
                patient,
                assessment,
                assessment.getChiefComplaint(),
                physicalExam
        );
        DifferentialResult differential = differentialService.generate(input);
        List<String> suggestedDiagnoses = extractSuggestedDiagnoses(differential);
        String suggestedPrimaryDiagnosis = suggestedDiagnoses.isEmpty() ? null : suggestedDiagnoses.get(0);
        String triageSummary = triageCalculator.generateTriageSummary(assessment);
        boolean queueUpdated = shouldSyncQueueTriage(assessment, queueTicket);
        boolean returnedToWaitingQueue = false;

        if (queueUpdated) {
            queueTicket.completeTriage(assessment.getFinalTriageLevel(), assessment.getId());
        }
        queueTicket.attachTriageOutcome(triageSummary, suggestedPrimaryDiagnosis, suggestedDiagnoses);
        if (queueTicket.isTriaged()
                && (queueTicket.getStatus() == QueueTicket.QueueStatus.CALLED
                || queueTicket.getStatus() == QueueTicket.QueueStatus.IN_PROGRESS)) {
            queueTicket.returnToWaitingAfterTriage();
            returnedToWaitingQueue = true;
        }
        queueTicket = queueRepository.save(queueTicket);
        int queuePosition = resolveQueuePosition(queueTicket);

        auditService.record(
                "TRIAGE_OUTCOME_GENERATED",
                assessment.getPatientId(),
                differential.available()
                        ? String.format(
                        "Triage outcome generated with %d differential suggestions. QueueUpdated=%s. ReturnedToWaiting=%s. Primary=%s",
                        differential.differentials().size(),
                        queueUpdated,
                        returnedToWaitingQueue,
                        suggestedPrimaryDiagnosis != null ? suggestedPrimaryDiagnosis : "none"
                )
                        : "Triage outcome generated without differential: " + differential.errorMessage()
        );

        TriageOutcomeResult result = new TriageOutcomeResult(
                assessment.getId(),
                assessment.getQueueTicketId(),
                queueTicket.getTicketNumber(),
                queueTicket.getStatus(),
                queuePosition,
                queueTicket.getEffectivePriority(),
                queueTicket.getWaitTimeMinutes(),
                queueTicket.getTargetWaitMinutes(),
                assessment.getSystemTriageLevel(),
                assessment.getFinalTriageLevel(),
                assessment.isTriageOverridden(),
                assessment.getOverrideReason(),
                triageSummary,
                queueUpdated,
                returnedToWaitingQueue,
                suggestedPrimaryDiagnosis,
                suggestedDiagnoses,
                differential
        );
        log.info("Triage outcome generated assessmentId={} queueTicketId={} queueUpdated={} returnedToWaiting={}",
                result.assessmentId(), assessment.getQueueTicketId(), result.queueUpdated(), result.returnedToWaitingQueue());
        return result;
    }

    @Transactional
    public TriageOutcomeResult generateTriageOutcomeForTicket(UUID queueTicketId, String physicalExam) {
        auditGuard.assertSessionActive();
        log.info("Generating triage outcome by queue ticket queueTicketId={}", queueTicketId);

        QueueTicket queueTicket = queueRepository.findById(queueTicketId)
                .orElseThrow(() -> new ClinicalAiException("Queue ticket not found"));

        TriageAssessment assessment = triageRepository.findTopByQueueTicketIdOrderByAssessedAtDesc(queueTicketId)
                .orElseThrow(() -> new ClinicalAiException("No triage assessment found for queue ticket"));

        return generateTriageOutcome(assessment.getId(), physicalExam);
    }

    private Encounter findEncounterOrThrow(UUID encounterId) {
        return encounterRepository.findById(encounterId)
                .orElseThrow(() -> new ClinicalAiException("Encounter not found"));
    }

    private void processBackgroundTranscription(
            UUID encounterId,
            byte[] audioBytes,
            String fileName,
            String mimeType,
            String language,
            String prompt,
            String requestedBy
    ) {
        Encounter encounter = null;
        try {
            encounter = findEncounterOrThrow(encounterId);
            encounter.markAmbientTranscriptionProcessing();
            encounterRepository.save(encounter);

            AmbientTranscriptionService.TranscriptionResult result = ambientTranscriptionService.transcribe(
                    audioBytes,
                    fileName,
                    mimeType,
                    language,
                    prompt
            );

            Encounter refreshed = findEncounterOrThrow(encounterId);
            if (!result.available() || result.transcript() == null || result.transcript().isBlank()) {
                String failure = result.errorMessage() != null && !result.errorMessage().isBlank()
                        ? result.errorMessage()
                        : "Ambient transcription returned no text";
                refreshed.markAmbientTranscriptionFailed(failure);
                encounterRepository.save(refreshed);
                auditService.record(
                        "AMBIENT_TRANSCRIPTION_BACKGROUND_FAILED",
                        refreshed.getPatientId(),
                        "Background transcription failed for encounter " + encounterId + ": " + failure
                );
                return;
            }

            boolean attachedTranscript = false;
            try {
                refreshed.recordBackgroundTranscript(result.transcript());
                attachedTranscript = true;
            } catch (IllegalStateException | IllegalArgumentException e) {
                if (refreshed.hasTranscript()) {
                    refreshed.markAmbientTranscriptionCompletedWithoutAttachment();
                } else {
                    refreshed.markAmbientTranscriptionFailed(e.getMessage());
                }
            }

            if (attachedTranscript
                    && refreshed.isNoteConfirmed()
                    && refreshed.getFinalNote() != null
                    && !refreshed.getFinalNote().isBlank()) {
                TranscriptAssessment assessment = assessTranscriptAgainstFinalNote(
                        refreshed.getTranscript(),
                        refreshed.getAiDraftNote(),
                        refreshed.getFinalNote()
                );
                refreshed.applySystemTranscriptAssessment(
                        assessment.rating(),
                        assessment.score(),
                        assessment.summary()
                );
            }

            encounterRepository.save(refreshed);
            auditService.record(
                    "AMBIENT_TRANSCRIPTION_BACKGROUND_COMPLETED",
                    refreshed.getPatientId(),
                    attachedTranscript
                            ? "Background transcript attached to encounter " + encounterId + " (requestedBy=" + requestedBy + ")"
                            : "Background transcript completed but could not attach (requestedBy=" + requestedBy + ")"
            );
        } catch (Exception e) {
            log.error("Background transcription processing failed encounterId={}", encounterId, e);
            if (encounter != null) {
                try {
                    encounter.markAmbientTranscriptionFailed(e.getMessage());
                    encounterRepository.save(encounter);
                    auditService.record(
                            "AMBIENT_TRANSCRIPTION_BACKGROUND_FAILED",
                            encounter.getPatientId(),
                            "Background transcription exception for encounter " + encounterId + ": " + e.getMessage()
                    );
                } catch (Exception inner) {
                    log.error("Failed to persist background transcription failure state encounterId={}", encounterId, inner);
                }
            }
        }
    }

    private TranscriptAssessment assessTranscriptAgainstFinalNote(
            String transcript,
            String aiDraftNote,
            String finalNote
    ) {
        NoteComparisonService.ComparisonResult aiResult = noteComparisonService.compare(transcript, aiDraftNote, finalNote);
        if (aiResult.available()) {
            return new TranscriptAssessment(
                    mapComparisonRating(aiResult.rating()),
                    aiResult.score(),
                    aiResult.summary()
            );
        }
        return heuristicAssessment(transcript, finalNote, aiResult.errorMessage());
    }

    private TranscriptAssessment heuristicAssessment(String transcript, String finalNote, String aiErrorMessage) {
        if (transcript == null || transcript.isBlank()) {
            return new TranscriptAssessment(
                    Encounter.NoteAccuracyRating.NOT_ASSESSED,
                    null,
                    "No transcript available for discrepancy analysis."
            );
        }

        Set<String> transcriptTokens = tokenizeClinicalTerms(transcript);
        Set<String> finalNoteTokens = tokenizeClinicalTerms(finalNote);
        if (transcriptTokens.isEmpty()) {
            return new TranscriptAssessment(
                    Encounter.NoteAccuracyRating.NOT_ASSESSED,
                    null,
                    "Transcript did not contain enough clinical terms for scoring."
            );
        }

        Set<String> intersection = new HashSet<>(transcriptTokens);
        intersection.retainAll(finalNoteTokens);
        int score = (int) Math.round((intersection.size() * 100.0) / transcriptTokens.size());
        Encounter.NoteAccuracyRating rating = mapScoreToRating(score);

        Set<String> missing = new HashSet<>(transcriptTokens);
        missing.removeAll(finalNoteTokens);
        List<String> topMissing = missing.stream().limit(12).toList();
        String summary = topMissing.isEmpty()
                ? "No major transcript term discrepancies detected."
                : "Potentially missing transcript terms in final note: " + String.join(", ", topMissing);
        if (aiErrorMessage != null && !aiErrorMessage.isBlank()) {
            summary = summary + " AI comparison unavailable; local fallback used. Reason: " + aiErrorMessage;
        }

        return new TranscriptAssessment(rating, score, summary);
    }

    private Set<String> tokenizeClinicalTerms(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        Set<String> stopWords = Set.of(
                "the", "and", "for", "with", "that", "this", "have", "from", "were", "been",
                "patient", "reports", "report", "today", "history", "present", "past", "normal",
                "note", "notes", "assessment", "plan", "review", "follow", "clinic", "hospital"
        );

        Set<String> tokens = new HashSet<>();
        Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .map(String::trim)
                .filter(token -> token.length() >= 3)
                .filter(token -> !stopWords.contains(token))
                .forEach(tokens::add);
        return tokens;
    }

    private Encounter.NoteAccuracyRating mapComparisonRating(NoteComparisonService.ComparisonRating rating) {
        return switch (rating) {
            case ACCURATE -> Encounter.NoteAccuracyRating.ACCURATE;
            case MINOR_EDITS -> Encounter.NoteAccuracyRating.MINOR_EDITS;
            case MAJOR_EDITS -> Encounter.NoteAccuracyRating.MAJOR_EDITS;
            case UNSAFE -> Encounter.NoteAccuracyRating.UNSAFE;
            case NOT_ASSESSED -> Encounter.NoteAccuracyRating.NOT_ASSESSED;
        };
    }

    private Encounter.NoteAccuracyRating mapScoreToRating(int score) {
        if (score >= 80) {
            return Encounter.NoteAccuracyRating.ACCURATE;
        }
        if (score >= 60) {
            return Encounter.NoteAccuracyRating.MINOR_EDITS;
        }
        if (score >= 35) {
            return Encounter.NoteAccuracyRating.MAJOR_EDITS;
        }
        return Encounter.NoteAccuracyRating.UNSAFE;
    }

    private Optional<TriageAssessment> resolveTriage(Encounter encounter) {
        if (encounter.getTriageAssessmentId() != null) {
            return triageRepository.findById(encounter.getTriageAssessmentId());
        }
        if (encounter.getQueueTicketId() != null) {
            return triageRepository.findTopByQueueTicketIdOrderByAssessedAtDesc(encounter.getQueueTicketId());
        }
        return Optional.empty();
    }

    private int resolveQueuePosition(QueueTicket ticket) {
        if (ticket.getStatus() != QueueTicket.QueueStatus.WAITING) {
            return -1;
        }

        List<QueueTicket> waitingQueue = queueRepository.findByQueueDateAndStatusOrderByEffectivePriorityAscCreatedAtAsc(
                ticket.getQueueDate(),
                QueueTicket.QueueStatus.WAITING
        );
        for (int i = 0; i < waitingQueue.size(); i++) {
            if (waitingQueue.get(i).getId().equals(ticket.getId())) {
                return i + 1;
            }
        }
        return -1;
    }

    private DifferentialInput buildDifferentialInput(
            Patient patient,
            TriageAssessment triage,
            String chiefComplaint,
            String physicalExam
    ) {
        return DifferentialInput.builder()
                .age(java.time.Period.between(patient.getDateOfBirth(), java.time.LocalDate.now()).getYears())
                .sex(patient.getSex().name())
                .chiefComplaint(chiefComplaint)
                .vitals(buildVitalsSummary(triage))
                .symptoms(buildSymptoms(triage, chiefComplaint))
                .history(buildHistory(triage))
                .physicalExam(physicalExam)
                .build();
    }

    private String buildVitalsSummary(TriageAssessment triage) {
        if (triage == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (triage.getTemperatureCelsius() != null) {
            parts.add("Temp " + triage.getTemperatureCelsius() + "C");
        }
        if (triage.getHeartRateBpm() != null) {
            parts.add("HR " + triage.getHeartRateBpm() + " bpm");
        }
        if (triage.getBloodPressureSystolic() != null && triage.getBloodPressureDiastolic() != null) {
            parts.add("BP " + triage.getBloodPressureSystolic() + "/" + triage.getBloodPressureDiastolic() + " mmHg");
        }
        if (triage.getRespiratoryRate() != null) {
            parts.add("RR " + triage.getRespiratoryRate() + "/min");
        }
        if (triage.getOxygenSaturation() != null) {
            parts.add("SpO2 " + triage.getOxygenSaturation() + "%");
        }
        if (triage.getPainScore() != null) {
            parts.add("Pain " + triage.getPainScore() + "/10");
        }
        if (parts.isEmpty()) {
            return null;
        }
        return String.join(", ", parts);
    }

    private List<String> buildSymptoms(TriageAssessment triage, String chiefComplaint) {
        List<String> symptoms = new ArrayList<>();
        if (chiefComplaint != null && !chiefComplaint.isBlank()) {
            symptoms.add(chiefComplaint);
        }
        if (triage == null) {
            return symptoms;
        }
        if (triage.isChestPain()) symptoms.add("Chest pain");
        if (triage.isDifficultyBreathing()) symptoms.add("Difficulty breathing");
        if (triage.isStrokeSymptoms()) symptoms.add("Stroke symptoms");
        if (triage.isSeverebleeding()) symptoms.add("Severe bleeding");
        if (triage.isAllergicReaction()) symptoms.add("Allergic reaction");
        if (triage.isAlteredMentalStatus()) symptoms.add("Altered mental status");
        if (triage.isPregnancyConcern()) symptoms.add("Pregnancy concern");
        if (triage.isSevereAbdominalPain()) symptoms.add("Severe abdominal pain");
        return symptoms;
    }

    private String buildHistory(TriageAssessment triage) {
        if (triage == null) {
            return null;
        }
        List<String> entries = new ArrayList<>();
        if (triage.getHistoryOfPresentIllness() != null && !triage.getHistoryOfPresentIllness().isBlank()) {
            entries.add("HPI: " + triage.getHistoryOfPresentIllness());
        }
        if (triage.getPastMedicalHistory() != null && !triage.getPastMedicalHistory().isBlank()) {
            entries.add("PMH: " + triage.getPastMedicalHistory());
        }
        if (triage.getCurrentMedications() != null && !triage.getCurrentMedications().isBlank()) {
            entries.add("Meds: " + triage.getCurrentMedications());
        }
        if (triage.getAllergies() != null && !triage.getAllergies().isBlank()) {
            entries.add("Allergies: " + triage.getAllergies());
        }
        return entries.isEmpty() ? null : String.join(" | ", entries);
    }

    private List<String> extractSuggestedDiagnoses(DifferentialResult differential) {
        if (differential == null || differential.differentials() == null || differential.differentials().isEmpty()) {
            return List.of();
        }

        Set<String> names = new LinkedHashSet<>();
        for (var diagnosis : differential.differentials()) {
            if (diagnosis != null && diagnosis.name() != null && !diagnosis.name().isBlank()) {
                names.add(diagnosis.name().trim());
            }
        }
        return List.copyOf(names);
    }

    private boolean shouldSyncQueueTriage(TriageAssessment assessment, QueueTicket queueTicket) {
        return !queueTicket.isTriaged()
                || !Objects.equals(queueTicket.getTriageAssessmentId(), assessment.getId())
                || queueTicket.getTriageLevel() != assessment.getFinalTriageLevel();
    }

    public record AiDraftGenerationResult(
            UUID encounterId,
            boolean available,
            String draftNote,
            List<String> extractedSymptoms,
            List<String> flaggedItems,
            String confidence,
            String provider,
            String model,
            long latencyMs,
            Instant generatedAt,
            boolean persisted,
            String errorMessage
    ) {
    }

    public record TriageOutcomeResult(
            UUID assessmentId,
            UUID queueTicketId,
            String ticketNumber,
            QueueTicket.QueueStatus queueStatus,
            int queuePosition,
            int effectivePriority,
            long waitTimeMinutes,
            int targetWaitMinutes,
            dalili.com.base.domain.triage.TriageLevel systemTriageLevel,
            dalili.com.base.domain.triage.TriageLevel finalTriageLevel,
            boolean triageOverridden,
            String overrideReason,
            String triageSummary,
            boolean queueUpdated,
            boolean returnedToWaitingQueue,
            String suggestedPrimaryDiagnosis,
            List<String> suggestedDiagnoses,
            DifferentialResult differential
    ) {
    }

    public record BackgroundTranscriptionQueuedResult(
            UUID encounterId,
            String status,
            boolean background,
            Instant queuedAt,
            String message
    ) {
    }

    private record TranscriptAssessment(
            Encounter.NoteAccuracyRating rating,
            Integer score,
            String summary
    ) {
    }

    public static class ClinicalAiException extends RuntimeException {
        public ClinicalAiException(String message) {
            super(message);
        }
    }
}
