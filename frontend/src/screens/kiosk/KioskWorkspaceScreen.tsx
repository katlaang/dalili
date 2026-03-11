import React, {useEffect, useMemo, useState} from "react";
import {Image, Text, View} from "react-native";
import {kioskApi} from "../../api/services";
import {ActionButton, Card, ChoiceChips, InlineActions, InputField, MessageBanner} from "../../components/ui";
import {useSession} from "../../state/session";
import {toErrorMessage} from "../../utils/format";

type KioskVisitType = "EXISTING_APPOINTMENT" | "NO_APPOINTMENT";
type AppointmentLookupMethod = "APPOINTMENT_NUMBER" | "QR_TOKEN";

export function KioskWorkspaceScreen() {
    const {baseUrl} = useSession();
    const [visitType, setVisitType] = useState<KioskVisitType>("EXISTING_APPOINTMENT");
    const [lookupMethod, setLookupMethod] = useState<AppointmentLookupMethod>("APPOINTMENT_NUMBER");
    const [appointmentNumber, setAppointmentNumber] = useState("");
    const [givenName, setGivenName] = useState("");
    const [familyName, setFamilyName] = useState("");
    const [dateOfBirth, setDateOfBirth] = useState("");
    const [complaint, setComplaint] = useState("");
    const [latestTicketNumber, setLatestTicketNumber] = useState<string | null>(null);
    const [errorMessage, setErrorMessage] = useState<string | null>(null);
    const [mobileEntry, setMobileEntry] = useState(false);

    useEffect(() => {
        if (typeof window === "undefined") {
            return;
        }
        const params = new URLSearchParams(window.location.search);
        const flow = params.get("flow");
        const lookup = params.get("lookup");
        if (flow === "EXISTING_APPOINTMENT" || flow === "NO_APPOINTMENT") {
            setVisitType(flow);
        }
        if (lookup === "APPOINTMENT_NUMBER" || lookup === "QR_TOKEN") {
            setLookupMethod(lookup);
        }
        setMobileEntry(params.get("mobileEntry") === "1");
    }, []);

    const mobileCheckInLink = useMemo(() => {
        if (typeof window === "undefined") {
            return "";
        }
        const link = new URL(window.location.href);
        link.searchParams.set("kiosk", "1");
        link.searchParams.set("flow", "EXISTING_APPOINTMENT");
        link.searchParams.set("lookup", "APPOINTMENT_NUMBER");
        link.searchParams.set("mobileEntry", "1");
        link.searchParams.set("api", baseUrl);
        return link.toString();
    }, [baseUrl]);

    const qrImageUrl = useMemo(() => {
        if (!mobileCheckInLink) {
            return "";
        }
        return `https://api.qrserver.com/v1/create-qr-code/?size=260x260&data=${encodeURIComponent(mobileCheckInLink)}`;
    }, [mobileCheckInLink]);

    const formatKioskError = (error: unknown) => {
        const errorMessage = toErrorMessage(error);
        if (errorMessage.toLowerCase().includes("failed to fetch")) {
            return `Failed to reach API at ${baseUrl}. If kiosk is opened from another device, use this machine's LAN IP (for example http://192.168.x.x:8181).`;
        }
        return errorMessage;
    };

    const showQueueNumber = (ticketNumber: string) => {
        setLatestTicketNumber(ticketNumber);
        setErrorMessage(null);
    };

    const printQueueNumber = (ticketNumber: string) => {
        if (typeof window !== "undefined") {
            const printWindow = window.open("", "_blank", "width=360,height=480");
            if (!printWindow) {
                setErrorMessage("Unable to open print window. Please allow popups for this kiosk.");
                return;
            }
            printWindow.document.write(`<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <title>Queue Number</title>
    <style>
      html, body {
        margin: 0;
        padding: 0;
        width: 100%;
        height: 100%;
        font-family: Arial, sans-serif;
      }
      .ticket {
        width: 100%;
        height: 100%;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 72px;
        font-weight: 700;
      }
    </style>
  </head>
  <body>
    <div class="ticket">${ticketNumber}</div>
    <script>
      window.focus();
      window.print();
      window.close();
    </script>
  </body>
</html>`);
            printWindow.document.close();
            return;
        }
        setErrorMessage("Printing is only available in web kiosk mode.");
    };

    const confirmAppointment = async () => {
        try {
            const result = await kioskApi.publicConfirmAppointmentByNumber(baseUrl, {
                appointmentNumber: appointmentNumber.trim(),
                givenName: givenName.trim(),
                familyName: familyName.trim(),
                dateOfBirth: dateOfBirth.trim(),
                complaint: complaint.trim() || undefined
            });
            const ticketNumber = result.queueTicket.ticketNumber;
            showQueueNumber(ticketNumber);
            if (!mobileEntry) {
                printQueueNumber(ticketNumber);
            }
        } catch (error) {
            setErrorMessage(formatKioskError(error));
        }
    };

    const noAppointmentCheckIn = async () => {
        try {
            const result = await kioskApi.publicNoAppointmentCheckIn(baseUrl, {
                givenName: givenName.trim(),
                familyName: familyName.trim(),
                dateOfBirth: dateOfBirth.trim(),
                complaint: complaint.trim() || undefined
            });
            const ticketNumber = result.ticketNumber;
            showQueueNumber(ticketNumber);
            if (!mobileEntry) {
                printQueueNumber(ticketNumber);
            }
        } catch (error) {
            setErrorMessage(formatKioskError(error));
        }
    };

    return (
        <>
            <Card title="Kiosk Landing">
                <ChoiceChips
                    label="Select Flow"
                    options={["EXISTING_APPOINTMENT", "NO_APPOINTMENT"]}
                    value={visitType}
                    onChange={(value) => setVisitType(value as KioskVisitType)}
                />
                <MessageBanner message={errorMessage} tone="error"/>
            </Card>

            {visitType === "EXISTING_APPOINTMENT" ? (
                <Card title="Existing Appointment">
                    <ChoiceChips
                        label="Lookup Method"
                        options={["APPOINTMENT_NUMBER", "QR_TOKEN"]}
                        value={lookupMethod}
                        onChange={(value) => setLookupMethod(value as AppointmentLookupMethod)}
                    />
                    {lookupMethod === "QR_TOKEN" ? (
                        <View style={{alignItems: "center", gap: 10, paddingVertical: 8}}>
                            <Text style={{textAlign: "center"}}>
                                Scan this QR on patient phone to open the Existing Appointment form.
                            </Text>
                            {qrImageUrl ? (
                                <Image
                                    source={{uri: qrImageUrl}}
                                    style={{
                                        width: 230,
                                        height: 230,
                                        borderRadius: 8,
                                        borderWidth: 1,
                                        borderColor: "#d8d2c8"
                                    }}
                                />
                            ) : null}
                            <Text style={{fontSize: 12, textAlign: "center"}}>
                                After scan, patient enters reservation number, name, DOB, and complaint on their phone.
                            </Text>
                        </View>
                    ) : (
                        <>
                            <InputField
                                label="Appointment Number"
                                value={appointmentNumber}
                                onChangeText={setAppointmentNumber}
                                placeholder="PR-001"
                            />
                            <InputField label="Given Name" value={givenName} onChangeText={setGivenName}
                                        placeholder="Jane"/>
                            <InputField label="Family Name" value={familyName} onChangeText={setFamilyName}
                                        placeholder="Doe"/>
                            <InputField label="Date of Birth" value={dateOfBirth} onChangeText={setDateOfBirth}
                                        placeholder="YYYY-MM-DD"/>
                            <InputField label="Complaint" value={complaint} onChangeText={setComplaint} multiline/>
                            <InlineActions>
                                <ActionButton
                                    label={mobileEntry ? "Generate Queue Number" : "Generate & Print Queue Number"}
                                    onPress={confirmAppointment}
                                />
                            </InlineActions>
                        </>
                    )}
                </Card>
            ) : (
                <Card title="No Appointment">
                    <InputField label="Given Name" value={givenName} onChangeText={setGivenName} placeholder="Jane"/>
                    <InputField label="Family Name" value={familyName} onChangeText={setFamilyName} placeholder="Doe"/>
                    <InputField label="Date of Birth" value={dateOfBirth} onChangeText={setDateOfBirth}
                                placeholder="YYYY-MM-DD"/>
                    <InputField label="Complaint" value={complaint} onChangeText={setComplaint} multiline/>
                    <InlineActions>
                        <ActionButton
                            label={mobileEntry ? "Generate Queue Number" : "Generate & Print Queue Number"}
                            onPress={noAppointmentCheckIn}
                        />
                    </InlineActions>
                </Card>
            )}

            {latestTicketNumber ? (
                <Card title="Queue Number">
                    <View style={{alignItems: "center", paddingVertical: 18, gap: 8}}>
                        <Text style={{fontSize: 14}}>Please keep this number for tracking</Text>
                        <Text style={{fontSize: 44, fontWeight: "700"}}>{latestTicketNumber}</Text>
                    </View>
                </Card>
            ) : null}
        </>
    );
}
