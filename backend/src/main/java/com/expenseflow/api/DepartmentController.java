package com.expenseflow.api;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.api.ClaimDtos.DepartmentDto;
import com.expenseflow.repository.DepartmentRepository;

/** GET /api/departments - every signed-in role (rbac.md). */
@RestController
public class DepartmentController {

    private final DepartmentRepository departments;

    public DepartmentController(DepartmentRepository departments) {
        this.departments = departments;
    }

    @GetMapping("/api/departments")
    @PreAuthorize("isAuthenticated()")
    public List<DepartmentDto> list() {
        return departments.findAll(Sort.by("name")).stream().map(ClaimMapper::dept).toList();
    }
}
