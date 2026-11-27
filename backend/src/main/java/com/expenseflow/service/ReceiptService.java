package com.expenseflow.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.expenseflow.api.ApiException;
import com.expenseflow.api.ErrorCodes;
import com.expenseflow.domain.Receipt;
import com.expenseflow.domain.Role;
import com.expenseflow.domain.User;
import com.expenseflow.repository.ApprovalStepRepository;
import com.expenseflow.repository.ReceiptRepository;
import com.expenseflow.repository.UserRepository;

/**
 * Receipt upload and download (BR-20, rbac.md). The file type is detected from the bytes (magic numbers), never
 * from the file name or the browser's Content-Type, so a renamed .exe is rejected. SHA-256 of the bytes feeds the
 * duplicate-receipt rule (BR-7).
 */
@Service
public class ReceiptService {

    private final ReceiptRepository receipts;
    private final UserRepository users;
    private final ApprovalStepRepository steps;

    public ReceiptService(ReceiptRepository receipts, UserRepository users, ApprovalStepRepository steps) {
        this.receipts = receipts;
        this.users = users;
        this.steps = steps;
    }

    @Transactional
    public Receipt upload(long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "A file is required", null);
        }
        if (file.getSize() > Receipt.MAX_SIZE_BYTES) {
            throw tooLarge();
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.MALFORMED_REQUEST, "File could not be read", null);
        }
        if (bytes.length > Receipt.MAX_SIZE_BYTES) {
            throw tooLarge();
        }
        String type = sniff(bytes);
        if (type == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCodes.UNSUPPORTED_FILE_TYPE, "Only PDF, JPG or PNG receipts are accepted", null);
        }
        User owner = users.findById(userId).orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "User not found"));
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank() ? "receipt" : file.getOriginalFilename();
        if (name.length() > 255) name = name.substring(name.length() - 255);
        return receipts.save(new Receipt(owner, name, type, bytes, sha256(bytes)));
    }

    /** Uploader, any approver of a claim using it (now or before), FINANCE_AUDITOR, FINANCE_HEAD; others 404. */
    @Transactional(readOnly = true)
    public Receipt visible(long userId, Set<String> roles, long receiptId) {
        Receipt r = receipts.findById(receiptId).orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Receipt not found"));
        if (r.getOwner().getId().equals(userId) || roles.contains(Role.FINANCE_AUDITOR) || roles.contains(Role.FINANCE_HEAD)
                || steps.isApproverOfReceipt(receiptId, userId)) {
            r.getContent();   // load inside the transaction
            return r;
        }
        throw ApiException.notFound(ErrorCodes.NOT_FOUND, "Receipt not found");
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCodes.RECEIPT_TOO_LARGE, "Receipts can be at most 5 MB", null);
    }

    /** PDF "%PDF", JPEG FF D8 FF, PNG 89 50 4E 47 0D 0A 1A 0A. */
    static String sniff(byte[] b) {
        if (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "application/pdf";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return "image/png";
        return null;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
