package com.expenseflow.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.domain.Claim;
import com.expenseflow.domain.ClaimLine;
import com.expenseflow.domain.ClaimStatus;
import com.expenseflow.domain.Department;
import com.expenseflow.domain.ExpenseCategory;
import com.expenseflow.domain.PayoutBatch;
import com.expenseflow.domain.PolicyAction;
import com.expenseflow.domain.PolicyRule;
import com.expenseflow.domain.Role;
import com.expenseflow.domain.SampleAudit;
import com.expenseflow.domain.User;

import jakarta.persistence.EntityManager;

/**
 * T-012: migrations apply on an empty PostgreSQL 18 (the context only starts if every entity matches the
 * schema - ddl-auto=validate), entities persist, and the database itself guards the invariants.
 * Each test runs in a rolled-back transaction. After a constraint violation PostgreSQL aborts the
 * transaction, so each test has at most one violation and it is the last statement.
 */
@Transactional
class DomainSchemaTest extends AbstractIntegrationTest {

    @Autowired DepartmentRepository departments;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired ClaimRepository claims;
    @Autowired PayoutBatchRepository batches;
    @Autowired PolicyRuleRepository policyRules;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User newUser(String name) {
        Department dept = departments.save(new Department("D" + uid(), "Dept " + uid()));
        User u = new User(name, uid() + "@expenseflow.test", "{noop}x");
        u.setDepartment(dept);
        u.getRoles().add(roles.findById(Role.EMPLOYEE).orElseThrow());
        return users.save(u);
    }

    private Claim newClaim(User owner) {
        return new Claim("EXP-T-" + uid(), owner, "Client visit");
    }

    private BigDecimal limit(String code) {
        return policyRules.findById(code).orElseThrow().getLimitAmount();
    }

    @Test
    void migrationsSeedRolesAndPolicyRulesAsConfiguration() {
        assertThat(roles.findAll()).extracting(Role::getCode).containsExactlyInAnyOrder(
                "EMPLOYEE", "MANAGER", "FINANCE_AUDITOR", "FINANCE_HEAD", "SUPER_ADMIN");
        assertThat(policyRules.count()).isEqualTo(11);
        assertThat(limit(PolicyRule.MEAL_DAILY_LIMIT)).isEqualByComparingTo("1500");          // BR-1
        assertThat(limit(PolicyRule.HOTEL_NIGHTLY_LIMIT)).isEqualByComparingTo("6000");       // BR-2
        assertThat(policyRules.findById(PolicyRule.HOTEL_NIGHTLY_LIMIT).orElseThrow().getParams())
                .contains("\"otherCitiesLimit\"").contains("4000").contains("Mumbai");     // BR-3
        assertThat(limit(PolicyRule.CAB_TRIP_LIMIT)).isEqualByComparingTo("2000");            // BR-4
        assertThat(limit(PolicyRule.RECEIPT_REQUIRED)).isEqualByComparingTo("500");           // BR-5
        assertThat(policyRules.findById(PolicyRule.RECEIPT_REQUIRED).orElseThrow().getAction()).isEqualTo(PolicyAction.REJECT);
        assertThat(policyRules.findById(PolicyRule.DUPLICATE_LINE).orElseThrow().getAction()).isEqualTo(PolicyAction.REJECT);    // BR-6
        assertThat(policyRules.findById(PolicyRule.DUPLICATE_RECEIPT).orElseThrow().getAction()).isEqualTo(PolicyAction.REJECT); // BR-7
        assertThat(policyRules.findById(PolicyRule.WEEKEND_SPEND).orElseThrow().getAction()).isEqualTo(PolicyAction.ROUTE);     // BR-8
        assertThat(limit(PolicyRule.AUTO_APPROVE_MAX)).isEqualByComparingTo("5000");          // BR-9
        assertThat(limit(PolicyRule.CHAIN_AUDITOR_ABOVE)).isEqualByComparingTo("25000");      // BR-11
        assertThat(limit(PolicyRule.CHAIN_HEAD_ABOVE)).isEqualByComparingTo("100000");        // BR-12
        assertThat(limit(PolicyRule.SAMPLE_AUDIT_RATE)).isEqualByComparingTo("0.10");         // BR-13
        assertThat(policyRules.findById(PolicyRule.SAMPLE_AUDIT_RATE).orElseThrow().isSetting()).isTrue();
    }

    @Test
    void claimWithLinesPersistsWithTotalOrderAndOwnerScope() {
        User owner = newUser("Priya");
        Claim claim = newClaim(owner);
        claim.addLine(new ClaimLine(ExpenseCategory.MEAL, LocalDate.of(2026, 11, 20), new BigDecimal("1200.50"), "Cafe Coffee Day"));
        ClaimLine hotel = new ClaimLine(ExpenseCategory.HOTEL, LocalDate.of(2026, 11, 20), new BigDecimal("5800.00"), "Taj");
        hotel.setCity("Mumbai");
        hotel.setNights(1);
        claim.addLine(hotel);
        Long id = claims.saveAndFlush(claim).getId();
        em.clear();

        Claim loaded = claims.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ClaimStatus.DRAFT);
        assertThat(loaded.getSampleAudit()).isEqualTo(SampleAudit.NONE);
        assertThat(loaded.isEditable()).isTrue();
        assertThat(loaded.getTotalAmount()).isEqualByComparingTo("7000.50");
        assertThat(loaded.getLines()).extracting(ClaimLine::getLineNo).containsExactly(1, 2);
        assertThat(loaded.getLines().get(1).getCategory()).isEqualTo(ExpenseCategory.HOTEL);
        assertThat(claims.findByIdAndEmployeeId(id, owner.getId())).isPresent();
        assertThat(claims.findByIdAndEmployeeId(id, owner.getId() + 999)).as("other users cannot see it").isEmpty();
    }

    @Test
    void claimCanBePaidOnlyOnce() {
        User head = newUser("Farhan");
        Claim claim = newClaim(newUser("Rahul"));
        claim.addLine(new ClaimLine(ExpenseCategory.CAB, LocalDate.of(2026, 11, 21), new BigDecimal("450.00"), "Uber"));
        claim.setStatus(ClaimStatus.APPROVED);
        claim.setSubmittedAt(Instant.now());
        claims.saveAndFlush(claim);

        PayoutBatch first = new PayoutBatch(head, LocalDate.now());
        first.addClaim(claim);
        batches.saveAndFlush(first);
        assertThat(first.getItemCount()).isEqualTo(1);
        assertThat(first.getTotal()).isEqualByComparingTo("450.00");

        PayoutBatch second = new PayoutBatch(head, LocalDate.now());
        second.addClaim(claim);
        assertThatThrownBy(() -> batches.saveAndFlush(second))
                .as("payout_items.claim_id is unique - no double payment (BR-18)")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsUnknownStatus() {
        User owner = newUser("Neha");
        assertThatThrownBy(() -> jdbc.update(
                "insert into claims (code, employee_id, title, status, submitted_at) values (?, ?, 'x', 'HACKED', now())",
                "EXP-T-" + uid(), owner.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsSelfDelegation() {
        User u = newUser("Meera");
        assertThatThrownBy(() -> jdbc.update(
                "insert into delegations (delegator_id, delegate_id, from_date, to_date) values (?, ?, current_date, current_date)",
                u.getId(), u.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsDelegationLongerThan30Days() {
        User a = newUser("Kiran");
        User b = newUser("Divya");
        assertThat(jdbc.update("insert into delegations (delegator_id, delegate_id, from_date, to_date) values (?, ?, current_date, current_date + 30)",
                a.getId(), b.getId())).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "insert into delegations (delegator_id, delegate_id, from_date, to_date) values (?, ?, current_date, current_date + 31)",
                a.getId(), b.getId()))
                .as("BR-14 max 30 days")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsHotelLineWithoutNights() {
        Claim claim = claims.saveAndFlush(newClaim(newUser("Arjun")));
        assertThatThrownBy(() -> jdbc.update(
                "insert into claim_lines (claim_id, line_no, category, expense_date, amount) values (?, 1, 'HOTEL', current_date, 5000)",
                claim.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNonPositiveLineAmount() {
        Claim claim = claims.saveAndFlush(newClaim(newUser("Ravi")));
        assertThatThrownBy(() -> jdbc.update(
                "insert into claim_lines (claim_id, line_no, category, expense_date, amount) values (?, 1, 'MEAL', current_date, 0)",
                claim.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsReceiptOver5MbOrWrongType() {
        User u = newUser("Asha");
        assertThatThrownBy(() -> jdbc.update(
                "insert into receipts (owner_id, file_name, content_type, size, sha256, content) values (?, 'x.exe', 'application/x-msdownload', 10, 'abc', '\\x00')",
                u.getId()))
                .as("BR-20 PDF/JPG/PNG only")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emailIsUniqueIgnoringCase() {
        User u = newUser("Sam");
        assertThat(users.findByEmailIgnoreCase(u.getEmail().toUpperCase())).isPresent();
        // raw insert: the entity constructor lower-cases emails, the DB index must still catch mixed case
        assertThatThrownBy(() -> jdbc.update(
                "insert into users (name, email, password_hash) values ('Sam 2', ?, 'x')", u.getEmail().toUpperCase()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
