package dalili.com.dalili.infra.audit.export;

import dalili.com.dalili.infra.audit.AuditEvent;
import dalili.com.dalili.infra.audit.AuditEventRepository;
import dalili.com.dalili.infra.audit.AuditVerificationResult;
import dalili.com.dalili.infra.audit.AuditVerificationService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.time.Instant;
import java.util.List;

@Service
public class AuditPdfExportService {

    private final AuditEventRepository repository;
    private final AuditVerificationService verificationService;

    public AuditPdfExportService(
            AuditEventRepository repository,
            AuditVerificationService verificationService
    ) {
        this.repository = repository;
        this.verificationService = verificationService;
    }

    public void export(OutputStream out) {

        AuditVerificationResult result =
                verificationService.verifyChain();

        if (!result.valid()) {
            throw new IllegalStateException(
                    "Audit chain invalid: " + result.message()
            );
        }

        List<AuditEvent> events =
                repository.findAllByOrderByTimestampAsc();

        try (PDDocument document = new PDDocument()) {

            PDPage page = new PDPage();
            document.addPage(page);

            PDPageContentStream content =
                    new PDPageContentStream(document, page);

            content.setFont(PDType1Font.HELVETICA, 9);
            content.beginText();
            content.setLeading(12f);
            content.newLineAtOffset(40, 750);

            content.showText("Audit Log Export");
            content.newLine();
            content.showText("Generated at: " + Instant.now());
            content.newLine();
            content.showText("Event count: " + events.size());
            content.newLine();
            content.showText("Verification: OK");
            content.newLine();
            content.newLine();

            for (AuditEvent e : events) {
                content.showText(
                        e.getTimestamp() + " | " +
                                e.getEventType() + " | " +
                                e.getPhysicianId() + " | " +
                                e.getPatientId()
                );
                content.newLine();
            }

            content.endText();
            content.close();

            document.save(out);

        } catch (Exception e) {
            throw new IllegalStateException(
                    "PDF export failed", e
            );
        }
    }
}
