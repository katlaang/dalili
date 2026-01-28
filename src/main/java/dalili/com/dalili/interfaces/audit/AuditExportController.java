package dalili.com.dalili.interfaces.audit;

import dalili.com.dalili.infra.audit.export.AuditExportService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;

@RestController
public class AuditExportController {

    private final AuditExportService exportService;

    public AuditExportController(AuditExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/api/audit/export")
    public void export(HttpServletResponse response) throws IOException {

        response.setContentType("text/csv");
        response.setHeader(
                "Content-Disposition",
                "attachment; filename=\"audit-log.csv\""
        );

        try (PrintWriter writer = response.getWriter()) {
            exportService.exportToCsv(writer);
        }
    }
}

