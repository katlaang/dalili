package dalili.com.base.domain.user.model;

public enum Role {
    // Clinical staff
    PHYSICIAN,
    NURSE,
    PHARMACIST,
    LAB_TECHNICIAN,

    // Administrative
    SUPER_ADMIN,
    ADMIN,
    RECEPTIONIST,

    // Patient
    PATIENT,

    // System/Device
    SYSTEM,
    KIOSK
}
