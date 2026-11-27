package com.expenseflow.api;

import java.net.URI;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.api.ClaimDtos.ClaimDto;
import com.expenseflow.api.ClaimDtos.ClaimInput;
import com.expenseflow.api.ClaimDtos.ClaimSummaryPage;
import com.expenseflow.api.ClaimDtos.CommentDto;
import com.expenseflow.api.ClaimDtos.CommentInput;
import com.expenseflow.domain.Claim;
import com.expenseflow.domain.ClaimStatus;
import com.expenseflow.idempotency.Idempotent;
import com.expenseflow.service.ClaimService;

import jakarta.validation.Valid;

/**
 * Claims API (FR-2, FR-3, FR-4, FR-14). Roles per rbac.md section 3: every staff role files claims
 * (SUPER_ADMIN-only users do not); row rules live in ClaimService (others' claims -> 404).
 */
@RestController
@RequestMapping("/api/claims")
@Validated
@PreAuthorize("hasAnyRole('EMPLOYEE','MANAGER','FINANCE_AUDITOR','FINANCE_HEAD')")
public class ClaimController {

    private final ClaimService service;
    private final ClaimMapper mapper;

    public ClaimController(ClaimService service, ClaimMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ClaimSummaryPage myClaims(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) ClaimStatus status,
                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Page<Claim> p = service.myClaims(CurrentUser.of(jwt).id(), status, page, size);
        return new ClaimSummaryPage(p.getContent().stream().map(mapper::toSummary).toList(), p.getNumber(), p.getSize(), p.getTotalElements());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ClaimDto> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ClaimInput input) {
        Claim c = service.create(CurrentUser.of(jwt).id(), input);
        return ResponseEntity.created(URI.create("/api/claims/" + c.getId())).body(mapper.toDto(c));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ClaimDto get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        CurrentUser u = CurrentUser.of(jwt);
        return mapper.toDto(service.visible(u.id(), u.roles(), id));
    }

    @PutMapping("/{id}")
    @Transactional
    public ClaimDto update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody ClaimInput input) {
        return mapper.toDto(service.update(CurrentUser.of(jwt).id(), id, input));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.delete(CurrentUser.of(jwt).id(), id);
    }

    @PostMapping("/{id}/submit")
    @Idempotent
    @Transactional
    public ClaimDto submit(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return mapper.toDto(service.submit(CurrentUser.of(jwt).id(), id));
    }

    @GetMapping("/{id}/comments")
    @Transactional(readOnly = true)
    public List<CommentDto> comments(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        CurrentUser u = CurrentUser.of(jwt);
        return service.comments(u.id(), u.roles(), id).stream().map(ClaimMapper::comment).toList();
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CommentDto addComment(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody CommentInput input) {
        CurrentUser u = CurrentUser.of(jwt);
        return ClaimMapper.comment(service.addComment(u.id(), u.roles(), id, input.text()));
    }
}
