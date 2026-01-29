package dalili.com.dalili.domain.user.model;

public enum Role {
    // Clinical staff
    PHYSICIAN,
    NURSE,
    PHARMACIST,
    LAB_TECHNICIAN,

    // Administrative
    ADMIN,
    RECEPTIONIST,

    // Patient
    PATIENT,

    // System/Device
    SYSTEM,
    KIOSK
}