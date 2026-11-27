package com.expenseflow.repository;


import org.springframework.data.jpa.repository.JpaRepository;

import com.expenseflow.domain.Role;

public interface RoleRepository extends JpaRepository<Role, String> {
}
