package com.expenseflow.mockbank.payout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Bank-facing API: upload a payout CSV (raw text/csv body or multipart field "file"), look up a file. */
@RestController
@RequestMapping("/bank/payout-files")
public class PayoutFileController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final PayoutFileService service;

    public PayoutFileController(PayoutFileService service) {
        this.service = service;
    }

    @PostMapping(consumes = {"text/csv", MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<PayoutFileResult> uploadRaw(
            @RequestBody(required = false) String body,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key) {
        return respond(service.submit(body, key));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PayoutFileResult> uploadMultipart(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key) throws IOException {
        return respond(service.submit(new String(file.getBytes(), StandardCharsets.UTF_8), key));
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<PayoutFileResult> get(@PathVariable String fileId) {
        return service.find(fileId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new PayoutFileNotFoundException(fileId));
    }

    private static ResponseEntity<PayoutFileResult> respond(PayoutFileService.Submission s) {
        return ResponseEntity.status(s.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAYED, String.valueOf(s.replayed()))
                .body(s.result());
    }
}
