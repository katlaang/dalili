package dalili.com.dalili.infra.audit.anchoring;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/anchor")
public class AnchorController {

    private final AnchorService anchorService;

    public AnchorController(AnchorService anchorService) {
        this.anchorService = anchorService;
    }

    @PostMapping("/create")
    public AnchorRecord create() {
        return anchorService.createAnchor();
    }

    @GetMapping("/verify")
    public VerifyResponse verify() {
        boolean valid = anchorService.verifyAnchors();
        return new VerifyResponse(valid, valid ? "All anchors verified" : "VERIFICATION FAILED");
    }

    record VerifyResponse(boolean valid, String message) {
    }
}
