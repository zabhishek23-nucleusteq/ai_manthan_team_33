package com.expenseflow.api;

import java.util.List;
import java.util.Set;

import org.springframework.security.oauth2.jwt.Jwt;

/** The signed-in user from the JWT: id = subject, roles = "roles" claim (without the ROLE_ prefix). */
public record CurrentUser(long id, Set<String> roles) {

    public static CurrentUser of(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new CurrentUser(Long.parseLong(jwt.getSubject()), roles == null ? Set.of() : Set.copyOf(roles));
    }
}
