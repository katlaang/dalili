import React, {useEffect, useMemo, useState} from "react";
import {Text, View} from "react-native";
import {patientAppointmentApi, patientPortalApi} from "../../api/services";
import type {
    AppointmentCheckInResponse,
    AppointmentView,
    AuthorizationView,
    EncounterNoteView,
    LabResultView,
    PatientPortalEncounter,
    PatientPortalProfile,
    ReferralView
} from "../../api/types";
import {
    ActionButton,
    Card,
    InlineActions,
    InputField,
    MessageBanner,
    SectionTabs,
    ToggleField
} from "../../components/ui";
import {useSession} from "../../state/session";
import {toErrorMessage} from "../../utils/format";
import type {CheckInDeepLinkPrefill} from "../../hooks/useCheckInDeepLink";

interface PatientWorkspaceScreenProps {
    deepLinkCheckInPrefill?: CheckInDeepLinkPrefill | null;
}

const portalTabs = ["Appointments", "Labs", "Diagnosis", "Access Log", "Visit Notes", "Imaging", "Physiotherapy"] as const;
type PortalTab = (typeof portalTabs)[number];

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

const toDateKey = (value?: string) => {
    if (!value) {
        return "Unknown date";
    }
    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) {
        return value;
    }
    return parsed.toLocaleDateString();
};

const summarize = (value?: string, max = 200) => {
    if (!value) {
        return "Not provided";
    }
    if (value.length <= max) {
        return value;
    }
    return `${value.slice(0, max)}...`;
};

export function PatientWorkspaceScreen({deepLinkCheckInPrefill}: PatientWorkspaceScreenProps) {
    const {apiContext} = useSession();
    const [activeTab, setActiveTab] = useState<PortalTab>("Appointments");

    const [labs, setLabs] = useState<LabResultView[]>([]);
    const [referrals, setReferrals] = useState<ReferralView[]>([]);
    const [notes, setNotes] = useState<EncounterNoteView[]>([]);
    const [encounters, setEncounters] = useState<PatientPortalEncounter[]>([]);
    const [authorizations, setAuthorizations] = useState<AuthorizationView[]>([]);
    const [profile, setProfile] = useState<PatientPortalProfile | null>(null);
    const [emergencyContactName, setEmergencyContactName] = useState("");
    const [emergencyContactPhone, setEmergencyContactPhone] = useState("");

    const [pendingAppointments, setPendingAppointments] = useState<AppointmentView[]>([]);
    const [selectedAppointmentId, setSelectedAppointmentId] = useState("");
    const [complaint, setComplaint] = useState("");
    const [consentForDataAccess, setConsentForDataAccess] = useState(true);
    const [checkInResponse, setCheckInResponse] = useState<AppointmentCheckInResponse | null>(null);

    const [selectedLabTest, setSelectedLabTest] = useState("");
    const [selectedLabDate, setSelectedLabDate] = useState("");

    const [message, setMessage] = useState<string | null>(null);
    const [tone, setTone] = useState<"success" | "error">("success");

    useEffect(() => {
        if (!deepLinkCheckInPrefill) {
            return;
        }
        if (deepLinkCheckInPrefill.complaint != null) {
            setComplaint(deepLinkCheckInPrefill.complaint);
        }
        if (deepLinkCheckInPrefill.consentForDataAccess != null) {
            setConsentForDataAccess(deepLinkCheckInPrefill.consentForDataAccess);
        }
    }, [deepLinkCheckInPrefill?.receivedAt]);

    if (!apiContext) {
        return (
            <Card title="Patient Portal">
                <MessageBanner message="No authenticated patient session." tone="error"/>
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

    const loadPortalData = async () => {
        try {
            const [profileData, labsData, referralsData, notesData, encountersData, authorizationData] = await Promise.all([
                patientPortalApi.getProfile(apiContext),
                patientPortalApi.getLabs(apiContext),
                patientPortalApi.getReferrals(apiContext),
                patientPortalApi.getNotes(apiContext),
                patientPortalApi.getEncounters(apiContext),
                patientPortalApi.getAuthorizations(apiContext)
            ]);
            setProfile(profileData);
            setEmergencyContactName(profileData.emergencyContactName || "");
            setEmergencyContactPhone(profileData.emergencyContactPhone || "");
            setLabs(labsData);
            setReferrals(referralsData);
            setNotes(notesData);
            setEncounters(encountersData);
            setAuthorizations(authorizationData);
            showSuccess("Portal data refreshed");
        } catch (error) {
            showError(error);
        }
    };

    const saveEmergencyContact = async () => {
        try {
            const updated = await patientPortalApi.updateEmergencyContact(apiContext, {
                name: emergencyContactName.trim() || undefined,
                phone: emergencyContactPhone.trim() || undefined
            });
            setProfile(updated);
            setEmergencyContactName(updated.emergencyContactName || "");
            setEmergencyContactPhone(updated.emergencyContactPhone || "");
            showSuccess("Emergency contact updated");
        } catch (error) {
            showError(error);
        }
    };

    const loadPendingAppointments = async () => {
        try {
            const pending = await patientAppointmentApi.getPending(apiContext);
            setPendingAppointments(pending);
            if (pending.length) {
                setSelectedAppointmentId(pending[0].id);
            }
            showSuccess(`Loaded ${pending.length} pending appointment(s)`);
        } catch (error) {
            showError(error);
        }
    };

    const confirmAppointment = async () => {
        try {
            const appointmentId = selectedAppointmentId.trim();
            if (!appointmentId) {
                throw new Error("Appointment ID is required");
            }
            const result = await patientAppointmentApi.checkIn(apiContext, appointmentId, {
                complaint: complaint.trim() || undefined,
                consentForDataAccess
            });
            setCheckInResponse(result);
            showSuccess(`Appointment confirmed. Queue number: ${result.queueTicket.trackingNumber || result.queueTicket.ticketNumber}`);
            await loadPendingAppointments();
        } catch (error) {
            showError(error);
        }
    };

    const labsByTest = useMemo(() => {
        const grouped = new Map<string, LabResultView[]>();
        labs.forEach((result) => {
            const key = result.testName || "Unknown Test";
            const existing = grouped.get(key) || [];
            existing.push(result);
            existing.sort((a, b) => {
                const aTime = a.recordedAt ? new Date(a.recordedAt).getTime() : 0;
                const bTime = b.recordedAt ? new Date(b.recordedAt).getTime() : 0;
                return bTime - aTime;
            });
            grouped.set(key, existing);
        });
        return grouped;
    }, [labs]);

    const labTestNames = useMemo(() => Array.from(labsByTest.keys()), [labsByTest]);
    const selectedLabSeries = labsByTest.get(selectedLabTest) || [];

    useEffect(() => {
        if (!labTestNames.length) {
            setSelectedLabTest("");
            return;
        }
        if (!selectedLabTest || !labsByTest.has(selectedLabTest)) {
            setSelectedLabTest(labTestNames[0]);
        }
    }, [labTestNames, labsByTest, selectedLabTest]);

    const labDateOptions = useMemo(
        () =>
            selectedLabSeries
                .map((result) => toDateKey(result.recordedAt))
                .filter((value, index, all) => all.indexOf(value) === index),
        [selectedLabSeries]
    );

    useEffect(() => {
        if (!labDateOptions.length) {
            setSelectedLabDate("");
            return;
        }
        if (!selectedLabDate || !labDateOptions.includes(selectedLabDate)) {
            setSelectedLabDate(labDateOptions[0]);
        }
    }, [labDateOptions, selectedLabDate]);

    const selectedLabRecord = selectedLabSeries.find((result) => toDateKey(result.recordedAt) === selectedLabDate) || null;
    const latestLabRecord = selectedLabSeries.length ? selectedLabSeries[0] : null;
    const historicLabRecords = selectedLabSeries.slice(1);

    const numericLabTrend = useMemo(() => {
        return [...selectedLabSeries]
            .reverse()
            .map((result) => ({
                label: toDateKey(result.recordedAt),
                value: Number(result.resultValue)
            }))
            .filter((point) => Number.isFinite(point.value));
    }, [selectedLabSeries]);

    const diagnosisTimeline = useMemo(
        () => encounters.filter((encounter) => (encounter.diagnosisCount || 0) > 0),
        [encounters]
    );

    const imagingReferrals = useMemo(
        () =>
            referrals.filter((referral) =>
                /imaging|radiology|x-ray|xray|ct|mri|ultrasound/i.test(
                    `${referral.specialty || ""} ${referral.reason || ""} ${referral.referredToFacility || ""}`
                )
            ),
        [referrals]
    );

    const physioReferrals = useMemo(
        () =>
            referrals.filter((referral) =>
                /physio|physiotherapy|physical therapy|rehab/i.test(
                    `${referral.specialty || ""} ${referral.reason || ""} ${referral.referredToFacility || ""}`
                )
            ),
        [referrals]
    );

    const renderLabTrend = () => {
        if (!numericLabTrend.length) {
            return <Text>No numeric values available for a trend chart.</Text>;
        }

        const maxValue = Math.max(...numericLabTrend.map((point) => point.value), 1);
        return (
            <View style={{gap: 8}}>
                {numericLabTrend.map((point) => {
                    const widthPercent = `${Math.max(4, Math.round((point.value / maxValue) * 100))}%` as `${number}%`;
                    return (
                        <View key={`${selectedLabTest}-${point.label}`} style={{gap: 4}}>
                            <Text>
                                {point.label}: {point.value}
                            </Text>
                            <View
                                style={{height: 8, borderRadius: 999, backgroundColor: "#e6eef2", overflow: "hidden"}}>
                                <View style={{width: widthPercent, height: 8, backgroundColor: "#157c94"}}/>
                            </View>
                        </View>
                    );
                })}
            </View>
        );
    };

    return (
        <>
            <Card title="Patient Portal">
                <MessageBanner
                    message="Use tabs to view labs trends, diagnosis timeline, access history, visit notes, imaging, and physiotherapy records."
                    tone="info"
                />
                <InlineActions>
                    <ActionButton label="Refresh Portal Data" onPress={loadPortalData}/>
                    <ActionButton label="Load Pending Appointments" onPress={loadPendingAppointments}
                                  variant="secondary"/>
                </InlineActions>
                <SectionTabs tabs={portalTabs} value={activeTab}
                             onChange={(value) => setActiveTab(value as PortalTab)}/>
                <MessageBanner message={message} tone={tone}/>
            </Card>

            <Card title="Emergency Contact">
                <Text>{profile ? `Patient: ${profile.fullName}` : "Load portal data to view profile details."}</Text>
                <InputField label="Contact Name" value={emergencyContactName} onChangeText={setEmergencyContactName}/>
                <InputField label="Contact Phone" value={emergencyContactPhone}
                            onChangeText={setEmergencyContactPhone}/>
                <InlineActions>
                    <ActionButton label="Save Emergency Contact" onPress={saveEmergencyContact}/>
                </InlineActions>
            </Card>

            {activeTab === "Appointments" ? (
                <Card title="Confirm Appointment">
                    <InputField label="Selected Appointment ID" value={selectedAppointmentId}
                                onChangeText={setSelectedAppointmentId}/>
                    <InputField label="Complaint (optional)" value={complaint} onChangeText={setComplaint} multiline/>
                    <ToggleField
                        label="Consent for same-hospital historical data access"
                        value={consentForDataAccess}
                        onChange={setConsentForDataAccess}
                    />
                    <InlineActions>
                        <ActionButton label="Load Pending Appointments" onPress={loadPendingAppointments}
                                      variant="secondary"/>
                        <ActionButton label="Confirm Appointment" onPress={confirmAppointment}/>
                    </InlineActions>
                    {pendingAppointments.length ? (
                        <View style={{gap: 10}}>
                            {pendingAppointments.map((appointment) => (
                                <View
                                    key={appointment.id}
                                    style={{
                                        borderWidth: 1,
                                        borderColor: "#d8d2c8",
                                        borderRadius: 10,
                                        padding: 10,
                                        gap: 4,
                                        backgroundColor: selectedAppointmentId === appointment.id ? "#eef7f4" : "#fff"
                                    }}
                                >
                                    <Text
                                        style={{fontWeight: "700"}}>{appointment.appointmentNumber || appointment.id}</Text>
                                    <Text>Date/Time: {formatDateTime(appointment.scheduledAt)}</Text>
                                    <Text>Hospital: {appointment.facilityName || "Current Facility"}</Text>
                                    <Text>Doctor: {appointment.clinicianName || "Unassigned"}</Text>
                                    <Text>Reason: {appointment.reason || "Not provided"}</Text>
                                    <Text>Status: {appointment.status}</Text>
                                    <InlineActions>
                                        <ActionButton
                                            label="Select"
                                            onPress={() => setSelectedAppointmentId(appointment.id)}
                                            variant={selectedAppointmentId === appointment.id ? "primary" : "secondary"}
                                        />
                                    </InlineActions>
                                </View>
                            ))}
                        </View>
                    ) : (
                        <Text>No pending appointments loaded.</Text>
                    )}
                </Card>
            ) : null}

            {activeTab === "Labs" ? (
                <>
                    <Card title="Current vs Historic Labs">
                        {!labTestNames.length ? (
                            <Text>No lab records available yet.</Text>
                        ) : (
                            <>
                                <Text style={{fontWeight: "700"}}>Lab Test</Text>
                                <InlineActions>
                                    {labTestNames.map((testName) => (
                                        <ActionButton
                                            key={testName}
                                            label={testName}
                                            onPress={() => setSelectedLabTest(testName)}
                                            variant={selectedLabTest === testName ? "primary" : "secondary"}
                                        />
                                    ))}
                                </InlineActions>
                                {latestLabRecord ? (
                                    <View style={{gap: 4}}>
                                        <Text style={{fontWeight: "700"}}>Current Result</Text>
                                        <Text>Date: {formatDateTime(latestLabRecord.recordedAt)}</Text>
                                        <Text>Value: {latestLabRecord.resultValue || "N/A"} {latestLabRecord.unit || ""}</Text>
                                        <Text>Reference: {latestLabRecord.referenceRange || "Not set"}</Text>
                                        <Text>Interpretation: {latestLabRecord.interpretation || "Not set"}</Text>
                                    </View>
                                ) : null}
                                <Text style={{fontWeight: "700"}}>Historic Results: {historicLabRecords.length}</Text>
                            </>
                        )}
                    </Card>

                    <Card title="Lab Date Drilldown">
                        {!selectedLabSeries.length ? (
                            <Text>Select a lab test to view results by date.</Text>
                        ) : (
                            <>
                                <Text>Click a date to view that record.</Text>
                                <InlineActions>
                                    {labDateOptions.map((dateKey) => (
                                        <ActionButton
                                            key={dateKey}
                                            label={dateKey}
                                            onPress={() => setSelectedLabDate(dateKey)}
                                            variant={selectedLabDate === dateKey ? "primary" : "secondary"}
                                        />
                                    ))}
                                </InlineActions>
                                {selectedLabRecord ? (
                                    <View style={{gap: 4}}>
                                        <Text>Date: {formatDateTime(selectedLabRecord.recordedAt)}</Text>
                                        <Text>Result: {selectedLabRecord.resultValue || "N/A"} {selectedLabRecord.unit || ""}</Text>
                                        <Text>Interpretation: {selectedLabRecord.interpretation || "Not set"}</Text>
                                        <Text>Recorded By: {selectedLabRecord.recordedByName || "Unknown"}</Text>
                                    </View>
                                ) : null}
                            </>
                        )}
                    </Card>

                    <Card title="Lab Trend Over Time">
                        {renderLabTrend()}
                    </Card>
                </>
            ) : null}

            {activeTab === "Diagnosis" ? (
                <Card title="Diagnosis Timeline">
                    {diagnosisTimeline.length ? (
                        <View style={{gap: 10}}>
                            {diagnosisTimeline.map((encounter) => (
                                <View
                                    key={encounter.id}
                                    style={{
                                        borderWidth: 1,
                                        borderColor: "#d8d2c8",
                                        borderRadius: 10,
                                        padding: 10,
                                        gap: 4
                                    }}
                                >
                                    <Text
                                        style={{fontWeight: "700"}}>{formatDateTime(encounter.completedAt || encounter.startedAt)}</Text>
                                    <Text>Diagnosis Count: {encounter.diagnosisCount}</Text>
                                    <Text>Clinician: {encounter.clinicianName || "Unknown"}</Text>
                                    <Text>Encounter Type: {encounter.encounterType || "Unknown"}</Text>
                                    <Text>Chief Complaint: {encounter.chiefComplaint || "Not documented"}</Text>
                                </View>
                            ))}
                        </View>
                    ) : (
                        <Text>No diagnosis entries found in completed encounters.</Text>
                    )}
                </Card>
            ) : null}

            {activeTab === "Access Log" ? (
                <Card title="Data Access Activity">
                    <Text>Shows access grants and consent events currently available in the patient portal.</Text>
                    {authorizations.length ? (
                        <View style={{gap: 10}}>
                            {authorizations.map((entry) => (
                                <View
                                    key={entry.id}
                                    style={{
                                        borderWidth: 1,
                                        borderColor: "#d8d2c8",
                                        borderRadius: 10,
                                        padding: 10,
                                        gap: 4
                                    }}
                                >
                                    <Text
                                        style={{fontWeight: "700"}}>{entry.authorizationType || "AUTHORIZATION"}</Text>
                                    <Text>Scope: {entry.dataAccessScope || "N/A"}</Text>
                                    <Text>Role: {entry.authorizerRole || "N/A"}</Text>
                                    <Text>By: {entry.authorizerName || "N/A"}</Text>
                                    <Text>Granted At: {formatDateTime(entry.grantedAt)}</Text>
                                    <Text>Expires At: {formatDateTime(entry.expiresAt)}</Text>
                                    <Text>Status: {entry.revoked ? "Revoked" : "Active"}</Text>
                                </View>
                            ))}
                        </View>
                    ) : (
                        <Text>No access records available.</Text>
                    )}
                </Card>
            ) : null}

            {activeTab === "Visit Notes" ? (
                <Card title="Visit Notes & Summaries">
                    {notes.length ? (
                        <View style={{gap: 10}}>
                            {notes.map((note) => (
                                <View
                                    key={note.encounterId}
                                    style={{
                                        borderWidth: 1,
                                        borderColor: "#d8d2c8",
                                        borderRadius: 10,
                                        padding: 10,
                                        gap: 4
                                    }}
                                >
                                    <Text
                                        style={{fontWeight: "700"}}>{formatDateTime(note.completedAt || note.startedAt)}</Text>
                                    <Text>Clinician: {note.clinicianName || "Unknown"}</Text>
                                    <Text>Encounter Type: {note.encounterType || "N/A"}</Text>
                                    <Text>Summary: {summarize(note.finalNote || note.physicianAuthoredNote)}</Text>
                                </View>
                            ))}
                        </View>
                    ) : (
                        <Text>No visit notes found.</Text>
                    )}
                </Card>
            ) : null}

            {activeTab === "Imaging" ? (
                <Card title="Imaging Records">
                    {imagingReferrals.length ? (
                        <View style={{gap: 10}}>
                            {imagingReferrals.map((record) => (
                                <View
                                    key={record.id}
                                    style={{
                                        borderWidth: 1,
                                        borderColor: "#d8d2c8",
                                        borderRadius: 10,
                                        padding: 10,
                                        gap: 4
                                    }}
                                >
                                    <Text style={{fontWeight: "700"}}>{record.specialty || "Imaging"}</Text>
                                    <Text>Date: {formatDateTime(record.referredAt)}</Text>
                                    <Text>Facility: {record.referredToFacility || "Unknown"}</Text>
                                    <Text>Reason: {record.reason || "Not provided"}</Text>
                                    <Text>Status: {record.status || "N/A"}</Text>
                                </View>
                            ))}
                        </View>
                    ) : (
                        <Text>No imaging records found.</Text>
                    )}
                </Card>
            ) : null}

            {activeTab === "Physiotherapy" ? (
                <Card title="Physiotherapy Records">
                    {physioReferrals.length ? (
                        <View style={{gap: 10}}>
                            {physioReferrals.map((record) => (
                                <View
                                    key={record.id}
                                    style={{
                                        borderWidth: 1,
                                        borderColor: "#d8d2c8",
                                        borderRadius: 10,
                                        padding: 10,
                                        gap: 4
                                    }}
                                >
                                    <Text style={{fontWeight: "700"}}>{record.specialty || "Physiotherapy"}</Text>
                                    <Text>Date: {formatDateTime(record.referredAt)}</Text>
                                    <Text>Facility: {record.referredToFacility || "Unknown"}</Text>
                                    <Text>Reason: {record.reason || "Not provided"}</Text>
                                    <Text>Status: {record.status || "N/A"}</Text>
                                </View>
                            ))}
                        </View>
                    ) : (
                        <Text>No physiotherapy records found.</Text>
                    )}
                </Card>
            ) : null}

            {checkInResponse ? (
                <Card title="Queue Number Issued">
                    <Text style={{fontWeight: "700", fontSize: 20}}>
                        {checkInResponse.queueTicket.trackingNumber || checkInResponse.queueTicket.ticketNumber}
                    </Text>
                    <Text>Appointment: {checkInResponse.appointment.appointmentNumber || checkInResponse.appointment.id}</Text>
                    <Text>Hospital: {checkInResponse.appointment.facilityName || "Current Facility"}</Text>
                </Card>
            ) : null}
        </>
    );
}
