package dalili.com.base.domain.encounter.model;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a medication order (prescription) within an encounter.
 *
 * <p>For the demo phase, medication orders are printed and taken to the
 * hospital pharmacy. Future phases will integrate electronic dispensing.</p>
 *
 * <p>Medication data is based on WHO Essential Medicines List and
 * national MOH formularies.</p>
 */
@Entity
@Table(name = "medication_orders", indexes = {
        @Index(name = "idx_medorder_encounter", columnList = "encounter_id"),
        @Index(name = "idx_medorder_patient", columnList = "patientId")
})
@Getter
@Setter
public class MedicationOrder {

    @Id
    @GeneratedValue
    private UUID id;

    /**
     * The encounter this order belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id", nullable = false)
    @Setter
    private Encounter encounter;

    /**
     * Patient ID (denormalized for pharmacy lookup).
     */
    @Column(nullable = false)
    private UUID patientId;

    /**
     * Medication generic name.
     */
    @Column(nullable = false)
    private String medicationName;

    /**
     * Medication brand name (optional).
     */
    @Column
    private String brandName;

    /**
     * Dosage strength (e.g., "500mg", "10mg/5ml").
     */
    @Column(nullable = false)
    private String dosage;

    /**
     * Dosage form (tablet, capsule, syrup, injection, etc.).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DosageForm dosageForm;

    /**
     * Frequency of administration (e.g., "TDS", "BD", "OD").
     */
    @Column(nullable = false)
    private String frequency;

    /**
     * Route of administration.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RouteOfAdministration route;

    /**
     * Duration of treatment in days.
     */
    @Column(nullable = false)
    private int durationDays;

    /**
     * Total quantity to dispense.
     */
    @Column(nullable = false)
    private int quantity;

    /**
     * Special instructions for patient or pharmacist.
     */
    @Column(length = 500)
    @Setter
    private String instructions;

    /**
     * Clinical indication for prescribing.
     */
    @Column(length = 500)
    @Setter
    private String indication;

    /**
     * Current status of the order.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    /**
     * When this order was created.
     */
    @Column(nullable = false)
    private Instant orderedAt;

    /**
     * Staff ID who ordered this medication.
     */
    @Column(nullable = false)
    private String orderedBy;

    /**
     * Staff name who ordered (for printing).
     */
    @Column(nullable = false)
    private String orderedByName;

    /**
     * When prescription was printed.
     */
    @Column
    private Instant printedAt;

    /**
     * When medication was dispensed by pharmacy.
     */
    @Column
    private Instant dispensedAt;

    /**
     * Pharmacist who dispensed (future use).
     */
    @Column
    private String dispensedBy;

    protected MedicationOrder() {
    }

    /**
     * Creates a new medication order.
     *
     * @param patientId      the patient UUID
     * @param medicationName the generic medication name
     * @param dosage         the dosage strength
     * @param dosageForm     the dosage form
     * @param frequency      the administration frequency
     * @param route          the route of administration
     * @param durationDays   the treatment duration
     * @param quantity       the quantity to dispense
     * @param orderedBy      the ordering clinician's staff ID
     * @param orderedByName  the ordering clinician's name
     * @return a new MedicationOrder in ORDERED status
     */
    public static MedicationOrder create(
            UUID patientId,
            String medicationName,
            String dosage,
            DosageForm dosageForm,
            String frequency,
            RouteOfAdministration route,
            int durationDays,
            int quantity,
            String orderedBy,
            String orderedByName
    ) {
        MedicationOrder order = new MedicationOrder();
        order.patientId = patientId;
        order.medicationName = medicationName;
        order.dosage = dosage;
        order.dosageForm = dosageForm;
        order.frequency = frequency;
        order.route = route;
        order.durationDays = durationDays;
        order.quantity = quantity;
        order.orderedBy = orderedBy;
        order.orderedByName = orderedByName;
        order.status = OrderStatus.ORDERED;
        order.orderedAt = Instant.now();
        return order;
    }


    /**
     * Marks the prescription as printed.
     */
    public void markPrinted() {
        this.printedAt = Instant.now();
        this.status = OrderStatus.PRINTED;
    }

    /**
     * Marks the medication as dispensed by pharmacy.
     *
     * @param pharmacistId the dispensing pharmacist's ID
     */
    public void markDispensed(String pharmacistId) {
        this.dispensedAt = Instant.now();
        this.dispensedBy = pharmacistId;
        this.status = OrderStatus.DISPENSED;
    }

    /**
     * Cancels the order.
     */
    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }

    /**
     * Gets the full prescription text for printing.
     *
     * @return formatted prescription line
     */
    public String getPrescriptionText() {
        StringBuilder sb = new StringBuilder();
        sb.append(medicationName);
        if (brandName != null && !brandName.isBlank()) {
            sb.append(" (").append(brandName).append(")");
        }
        sb.append(" ").append(dosage);
        sb.append(" ").append(dosageForm.getDisplayName());
        sb.append("\n");
        sb.append("Take ").append(frequency);
        sb.append(" ").append(route.getInstruction());
        sb.append(" for ").append(durationDays).append(" days");
        sb.append("\n");
        sb.append("Qty: ").append(quantity);
        if (instructions != null && !instructions.isBlank()) {
            sb.append("\n").append("Note: ").append(instructions);
        }
        return sb.toString();
    }

    // ==================== ENUMS ====================

    /**
     * Dosage form of medication.
     */
    public enum DosageForm {
        TABLET("Tablet"),
        CAPSULE("Capsule"),
        SYRUP("Syrup"),
        SUSPENSION("Suspension"),
        INJECTION("Injection"),
        CREAM("Cream"),
        OINTMENT("Ointment"),
        DROPS("Drops"),
        INHALER("Inhaler"),
        SUPPOSITORY("Suppository"),
        PATCH("Patch"),
        POWDER("Powder");

        private final String displayName;

        DosageForm(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Route of administration.
     */
    public enum RouteOfAdministration {
        ORAL("by mouth"),
        SUBLINGUAL("under the tongue"),
        TOPICAL("to affected area"),
        INTRAMUSCULAR("IM injection"),
        INTRAVENOUS("IV"),
        SUBCUTANEOUS("SC injection"),
        RECTAL("rectally"),
        VAGINAL("vaginally"),
        INHALATION("inhaled"),
        OPHTHALMIC("in eye(s)"),
        OTIC("in ear(s)"),
        NASAL("in nose");

        private final String instruction;

        RouteOfAdministration(String instruction) {
            this.instruction = instruction;
        }

        public String getInstruction() {
            return instruction;
        }
    }

    /**
     * Status of the medication order.
     */
    public enum OrderStatus {
        /**
         * Order created, not yet printed
         */
        ORDERED,
        /**
         * Prescription printed for pharmacy
         */
        PRINTED,
        /**
         * Medication dispensed by pharmacy
         */
        DISPENSED,
        /**
         * Order cancelled
         */
        CANCELLED
    }
}
