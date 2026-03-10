package dalili.com.base.domain.policy;

import java.time.LocalDate;
import java.util.List;

/**
 * PolicyMetadata tracks provenance and review status of policy packages.
 */
public record PolicyMetadata(
        String createdBy,
        String alignment,       // Changed from reviewedBy - "Aligned with X guidelines"
        LocalDate lastUpdated,
        LocalDate nextReview,
        List<String> versionNotes,
        List<String> references
) {
    public PolicyMetadata {
        versionNotes = versionNotes != null ? List.copyOf(versionNotes) : List.of();
        references = references != null ? List.copyOf(references) : List.of();
    }

    public boolean isReviewOverdue() {
        return nextReview != null && LocalDate.now().isAfter(nextReview);
    }
}
