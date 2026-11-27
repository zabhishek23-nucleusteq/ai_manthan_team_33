package com.expenseflow.api;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.expenseflow.api.ClaimDtos.ReceiptDto;
import com.expenseflow.domain.Receipt;
import com.expenseflow.service.ReceiptService;

/** Receipt upload (BR-20) and bytes for the side-by-side viewer (rbac.md row rule). */
@RestController
@RequestMapping("/api/receipts")
@PreAuthorize("hasAnyRole('EMPLOYEE','MANAGER','FINANCE_AUDITOR','FINANCE_HEAD')")
public class ReceiptController {

    private final ReceiptService service;

    public ReceiptController(ReceiptService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptDto upload(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file) {
        return ClaimMapper.receipt(service.upload(CurrentUser.of(jwt).id(), file));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        CurrentUser u = CurrentUser.of(jwt);
        Receipt r = service.visible(u.id(), u.roles(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(r.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(r.getFileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(r.getContent());
    }
}
