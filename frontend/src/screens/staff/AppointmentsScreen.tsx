import React, {useState} from "react";
import {Text, View} from "react-native";
import {appointmentApi} from "../../api/services";
import type {AppointmentCheckInResponse, AppointmentView} from "../../api/types";
import {
  ActionButton,
  Card,
  InlineActions,
  InputField,
  JsonPanel,
  MessageBanner,
  ToggleField
} from "../../components/ui";
import {useSession} from "../../state/session";
import {toErrorMessage} from "../../utils/format";

interface AppointmentsScreenProps {
    onQueueTicketLinked?: (queueTicketId: string) => void;
}

const toNullableNumber = (value: string): number | undefined => {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : undefined;
};

export function AppointmentsScreen({onQueueTicketLinked}: AppointmentsScreenProps) {
    const {apiContext, role} = useSession();
    const [patientId, setPatientId] = useState("");
    const [scheduledAt, setScheduledAt] = useState("");
    const [durationMinutes, setDurationMinutes] = useState("20");
    const [clinicianId, setClinicianId] = useState("");
    const [clinicianName, setClinicianName] = useState("");
    const [clinicianEmployeeId, setClinicianEmployeeId] = useState("");
    const [departmentCode, setDepartmentCode] = useState("");
    const [departmentName, setDepartmentName] = useState("");
    const [reason, setReason] = useState("");

    const [lookupPatientId, setLookupPatientId] = useState("");
    const [checkInAppointmentId, setCheckInAppointmentId] = useState("");
    const [checkInPatientId, setCheckInPatientId] = useState("");
    const [checkInComplaint, setCheckInComplaint] = useState("");
    const [checkInConsent, setCheckInConsent] = useState(true);
    const [cancelAppointmentId, setCancelAppointmentId] = useState("");
    const [cancelReason, setCancelReason] = useState("");

    const [todayAppointments, setTodayAppointments] = useState<AppointmentView[]>([]);
    const [assignedAppointments, setAssignedAppointments] = useState<AppointmentView[]>([]);
    const [pendingByPatient, setPendingByPatient] = useState<AppointmentView[]>([]);
    const [latestCheckIn, setLatestCheckIn] = useState<AppointmentCheckInResponse | null>(null);
    const [lastResponse, setLastResponse] = useState<unknown>(null);
    const [message, setMessage] = useState<string | null>(null);
    const [tone, setTone] = useState<"success" | "error">("success");

    if (!apiContext) {
        return (
            <Card title="Appointments">
                <MessageBanner message="No authenticated session." tone="error"/>
            </Card>
        );
    }

    const showError = (error: unknown) => {
        setMessage(toErrorMessage(error));
        setTone("error");
    };

    const showSuccess = (text: string) => {
        setMessage(text);
        setTone("success");
    };

    const formatDateTime = (value?: string) => {
        if (!value) {
            return "Not set";
        }
        const parsed = new Date(value);
        if (Number.isNaN(parsed.getTime())) {
            return value;
        }
        return parsed.toLocaleString();
    };

    const selectForCheckIn = (appointment: AppointmentView) => {
        setCheckInAppointmentId(appointment.id);
        if (appointment.patientId) {
            setCheckInPatientId(appointment.patientId);
        }
    };

    const scheduleAppointment = async () => {
        try {
            const created = await appointmentApi.schedule(apiContext, {
                patientId: patientId.trim(),
                scheduledAt: scheduledAt.trim(),
                durationMinutes: toNullableNumber(durationMinutes),
                clinicianId: clinicianId.trim() || undefined,
                clinicianName: clinicianName || undefined,
                clinicianEmployeeId: clinicianEmployeeId || undefined,
                departmentCode: departmentCode || undefined,
                departmentName: departmentName || undefined,
                reason: reason.trim() || undefined
            });
            setLastResponse(created);
            setCancelAppointmentId(created.id);
            showSuccess("Appointment scheduled");
        } catch (error) {
            showError(error);
        }
    };

    const loadToday = async () => {
        try {
            const appointments = await appointmentApi.getToday(apiContext);
            setTodayAppointments(appointments);
            showSuccess(`Loaded ${appointments.length} appointment(s) for today`);
        } catch (error) {
            showError(error);
        }
    };

    const loadAssignedPending = async () => {
        try {
            const assigned = await appointmentApi.getAssignedPending(apiContext);
            setAssignedAppointments(assigned);
            if (assigned.length) {
                selectForCheckIn(assigned[0]);
            }
            showSuccess(`Loaded ${assigned.length} assigned appointment(s)`);
        } catch (error) {
            showError(error);
        }
    };

    const loadPendingForPatient = async () => {
        try {
            const pending = await appointmentApi.getPendingForPatient(apiContext, lookupPatientId.trim());
            setPendingByPatient(pending);
            if (pending.length) {
                selectForCheckIn(pending[0]);
            }
            showSuccess(`Loaded ${pending.length} pending appointment(s)`);
        } catch (error) {
            showError(error);
        }
    };

    const staffCheckIn = async () => {
        try {
            const response = await appointmentApi.checkInByStaff(apiContext, checkInAppointmentId.trim(), {
                patientId: checkInPatientId.trim(),
                complaint: checkInComplaint || undefined,
                consentForDataAccess: checkInConsent
            });
            setLatestCheckIn(response);
            setLastResponse(response);
            if (response.queueTicket?.id) {
                onQueueTicketLinked?.(response.queueTicket.id);
            }
            showSuccess(`Appointment checked in. Queue ticket: ${response.queueTicket.ticketNumber}`);
        } catch (error) {
            showError(error);
        }
    };

    const cancelAppointment = async () => {
        try {
            const response = await appointmentApi.cancel(apiContext, cancelAppointmentId.trim(), cancelReason || undefined);
            setLastResponse(response);
            showSuccess("Appointment cancelled");
        } catch (error) {
            showError(error);
        }
    };

    const renderAppointmentList = (title: string, appointments: AppointmentView[]) => {
        if (!appointments.length) {
            return null;
        }
        return (
            <Card title={title}>
                <View style={{gap: 10}}>
                    {appointments.map((appointment) => (
                        <View
                            key={appointment.id}
                            style={{borderWidth: 1, borderColor: "#d8d2c8", borderRadius: 10, padding: 10, gap: 4}}
                        >
                            <Text style={{fontWeight: "700"}}>{appointment.appointmentNumber || appointment.id}</Text>
                            <Text>Patient: {appointment.patientName || appointment.patientId || "Unknown"}</Text>
                            <Text>Date/Time: {formatDateTime(appointment.scheduledAt)}</Text>
                            <Text>Hospital: {appointment.facilityName || "Current Facility"}</Text>
                            <Text>Doctor: {appointment.clinicianName || "Unassigned"}</Text>
                            <Text>Reason: {appointment.reason || "Not provided"}</Text>
                            <Text>Status: {appointment.status}</Text>
                            <InlineActions>
                                <ActionButton label="Use for Check-In" onPress={() => selectForCheckIn(appointment)}
                                              variant="secondary"/>
                            </InlineActions>
                        </View>
                    ))}
                </View>
            </Card>
        );
    };

    return (
        <>
            <Card title="Schedule Appointment">
                <InputField label="Patient UUID" value={patientId} onChangeText={setPatientId}/>
                <InputField
                    label="Scheduled At (ISO-8601)"
                    value={scheduledAt}
                    onChangeText={setScheduledAt}
                    placeholder="2026-03-11T14:30:00Z"
                />
                <InputField label="Duration Minutes" value={durationMinutes} onChangeText={setDurationMinutes}/>
                <InputField label="Clinician User UUID (optional)" value={clinicianId} onChangeText={setClinicianId}/>
                <InputField label="Clinician Name (optional)" value={clinicianName} onChangeText={setClinicianName}/>
                <InputField
                    label="Clinician Employee ID (optional)"
                    value={clinicianEmployeeId}
                    onChangeText={setClinicianEmployeeId}
                />
                <InputField label="Department Code (optional)" value={departmentCode} onChangeText={setDepartmentCode}/>
                <InputField label="Department Name (optional)" value={departmentName} onChangeText={setDepartmentName}/>
                <InputField label="Reason" value={reason} onChangeText={setReason} multiline/>
                <InlineActions>
                    <ActionButton label="Schedule Appointment" onPress={scheduleAppointment}/>
                    <ActionButton label="Load Today's Appointments" onPress={loadToday} variant="secondary"/>
                    <ActionButton label="Load Assigned Pending" onPress={loadAssignedPending} variant="secondary"/>
                </InlineActions>
                <MessageBanner message={message} tone={tone}/>
            </Card>

            <Card title="Pending / Check-In">
                <InputField
                    label="Patient UUID for Pending Lookup"
                    value={lookupPatientId}
                    onChangeText={setLookupPatientId}
                    placeholder="Load pending appointments for one patient"
                />
                <InlineActions>
                    <ActionButton label="Load Pending For Patient" onPress={loadPendingForPatient}/>
                </InlineActions>
                <InputField label="Appointment UUID" value={checkInAppointmentId}
                            onChangeText={setCheckInAppointmentId}/>
                <InputField label="Check-In Patient UUID" value={checkInPatientId} onChangeText={setCheckInPatientId}/>
                <InputField label="Complaint (optional)" value={checkInComplaint} onChangeText={setCheckInComplaint}
                            multiline/>
                <ToggleField
                    label="Consent for same-hospital historical data access"
                    value={checkInConsent}
                    onChange={setCheckInConsent}
                />
                <InlineActions>
                    <ActionButton label="Check In Appointment" onPress={staffCheckIn}/>
                </InlineActions>
            </Card>

            <Card title="Cancel Appointment">
                <InputField label="Appointment UUID" value={cancelAppointmentId} onChangeText={setCancelAppointmentId}/>
                <InputField label="Cancel Reason (optional)" value={cancelReason} onChangeText={setCancelReason}
                            multiline/>
                <InlineActions>
                    <ActionButton label="Cancel Appointment" onPress={cancelAppointment} variant="danger"/>
                </InlineActions>
            </Card>

            {renderAppointmentList(role === "PHYSICIAN" ? "My Assigned Appointments" : "Assigned Appointments", assignedAppointments)}
            {renderAppointmentList("Today's Appointments", todayAppointments)}
            {renderAppointmentList("Pending Appointments (Patient)", pendingByPatient)}

            {latestCheckIn ? (
                <Card title="Appointment Check-In Result">
                    <JsonPanel value={latestCheckIn}/>
                </Card>
            ) : null}

            {lastResponse ? (
                <Card title="Last Action Response">
                    <JsonPanel value={lastResponse}/>
                </Card>
            ) : null}
        </>
    );
}
