package dalili.com.base.application.service;

import dalili.com.base.domain.encounter.model.Diagnosis;
import dalili.com.base.domain.encounter.model.Encounter;
import dalili.com.base.domain.encounter.model.MedicationOrder;
import dalili.com.base.domain.encounter.repository.EncounterRepository;
import dalili.com.base.domain.encounter.repository.MedicationOrderRepository;
import dalili.com.base.domain.triage.TriageAssessment;
import dalili.com.base.repository.triage.TriageAssessmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds emergency-scope patient data (diagnoses + vitals + current medications/allergies).
 */
@Service
public class PatientEmergencyDataService {

    private static final Logger log = LoggerFactory.getLogger(PatientEmergencyDataService.class);

    private final EncounterRepository encounterRepository;
    private final TriageAssessmentRepository triageRepository;
    private final MedicationOrderRepository medicationOrderRepository;

    public PatientEmergencyDataService(
            EncounterRepository encounterRepository,
            TriageAssessmentRepository triageRepository,
            MedicationOrderRepository medicationOrderRepository
    ) {
        this.encounterRepository = encounterRepository;
        this.triageRepository = triageRepository;
        this.medicationOrderRepository = medicationOrderRepository;
    }

    public EmergencyDataView buildEmergencyData(UUID patientId) {
        log.info("Building emergency data view patientId={}", patientId);
        List<Encounter> completedEncounters = encounterRepository.findCompletedByPatientId(patientId);
        List<TriageAssessment> triageHistory = triageRepository.findByPatientIdOrderByAssessedAtDesc(patientId);
        List<MedicationOrder> medicationOrders = medicationOrderRepository.findByPatientIdOrderByOrderedAtDesc(patientId);

        List<DiagnosisSummary> diagnoses = new ArrayList<>();
        completedEncounters.stream()
                .limit(12)
                .forEach(encounter -> encounter.getDiagnoses().forEach(diagnosis -> diagnoses.add(new DiagnosisSummary(
                        diagnosis.getIcdCode(),
                        diagnosis.getDescription(),
                        encounter.getStartedAt().atZone(ZoneId.systemDefault()).toLocalDate(),
                        inferChronicCondition(diagnosis),
                        diagnosis.isPrimary() ? "PRIMARY" : diagnosis.getType().name()
                ))));

        List<VitalsSummary> vitals = triageHistory.stream()
                .limit(10)
                .map(assessment -> new VitalsSummary(
                        assessment.getAssessedAt().atZone(ZoneId.systemDefault()).toLocalDate(),
                        assessment.getBloodPressureSystolic(),
                        assessment.getBloodPressureDiastolic(),
                        assessment.getHeartRateBpm(),
                        assessment.getRespiratoryRate(),
                        assessment.getOxygenSaturation(),
                        assessment.getTemperatureCelsius() != null ? assessment.getTemperatureCelsius().toPlainString() : null,
                        assessment.getPainScore()
                ))
                .toList();

        List<MedicationSummary> currentMeds = medicationOrders.stream()
                .filter(order -> order.getStatus() != MedicationOrder.OrderStatus.CANCELLED)
                .limit(20)
                .map(order -> new MedicationSummary(
                        order.getId(),
                        order.getMedicationName(),
                        order.getDosage(),
                        order.getFrequency(),
                        order.getIndication(),
                        order.getOrderedAt() != null ? order.getOrderedAt().atZone(ZoneId.systemDefault()).toLocalDate() : null
                ))
                .toList();

        String allergies = triageHistory.stream()
                .map(TriageAssessment::getAllergies)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(null);

        EmergencyDataView view = new EmergencyDataView(
                patientId,
                diagnoses,
                vitals,
                currentMeds,
                allergies
        );
        log.info(
                "Emergency data built patientId={} diagnosisCount={} vitalsCount={} medicationCount={}",
                patientId,
                diagnoses.size(),
                vitals.size(),
                currentMeds.size()
        );
        return view;
    }

    private boolean inferChronicCondition(Diagnosis diagnosis) {
        if (diagnosis == null) {
            return false;
        }

        if (diagnosis.getType() != Diagnosis.DiagnosisType.CONFIRMED) {
            return false;
        }

        String text = diagnosis.getDescription() != null
                ? diagnosis.getDescription().toLowerCase()
                : "";
        return text.contains("chronic")
                || text.contains("hypertension")
                || text.contains("diabetes")
                || text.contains("asthma")
                || text.contains("epilepsy")
                || text.contains("hiv")
                || text.contains("renal");
    }

    public record EmergencyDataView(
            UUID patientId,
            List<DiagnosisSummary> diagnoses,
            List<VitalsSummary> previousVitals,
            List<MedicationSummary> currentMedications,
            String knownAllergies
    ) {
    }

    public record DiagnosisSummary(
            String icdCode,
            String diagnosisName,
            LocalDate diagnosedDate,
            boolean chronicCondition,
            String status
    ) {
    }

    public record VitalsSummary(
            LocalDate assessedDate,
            Integer bloodPressureSystolic,
            Integer bloodPressureDiastolic,
            Integer heartRateBpm,
            Integer respiratoryRate,
            Integer oxygenSaturation,
            String temperatureCelsius,
            Integer painScore
    ) {
    }

    public record MedicationSummary(
            UUID medicationOrderId,
            String medicationName,
            String dosage,
            String frequency,
            String prescribedFor,
            LocalDate startedDate
    ) {
    }
}


