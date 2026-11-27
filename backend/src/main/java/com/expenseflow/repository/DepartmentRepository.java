package com.expenseflow.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

import com.expenseflow.domain.Department;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    Optional<Department> findByCode(String code);
}
