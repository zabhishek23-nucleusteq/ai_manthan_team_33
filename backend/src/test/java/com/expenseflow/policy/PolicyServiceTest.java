package com.expenseflow.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.domain.Claim;
import com.expenseflow.domain.ClaimLine;
import com.expenseflow.domain.ClaimStatus;
import com.expenseflow.domain.ExpenseCategory;
import com.expenseflow.domain.PolicyOutcome;
import com.expenseflow.domain.PolicyRule;
import com.expenseflow.domain.Receipt;
import com.expenseflow.domain.User;
import com.expenseflow.policy.PolicyModel.PolicyResult;
import com.expenseflow.policy.PolicyModel.Violation;
import com.expenseflow.repository.ClaimRepository;
import com.expenseflow.repository.PolicyRuleRepository;
import com.expenseflow.repository.ReceiptRepository;
import com.expenseflow.repository.UserRepository;

import jakarta.persistence.EntityManager;

/** PolicyService against PostgreSQL: cross-claim facts (BR-1, BR-6, BR-7), stored snapshot, rule changes. Rolled back. */
@Transactional
class PolicyServiceTest extends AbstractIntegrationTest {

    static final LocalDate WED = LocalDate.of(2026, 11, 18);

    @Autowired PolicyService policy;
    @Autowired UserRepository users;
    @Autowired ClaimRepository claims;
    @Autowired ReceiptRepository receipts;
    @Autowired PolicyRuleRepository rules;
    @Autowired EntityManager em;

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User user() {
        return users.save(new User("U " + uid(), uid() + "@expenseflow.test", "x"));
    }

    /**
     * Every line over 500 INR gets its own unique receipt, so BR-5 (receipt required) does not interfere with
     * the rule under test - our first version of these tests forgot this and BR-5 correctly auto-rejected them.
     */
    private Claim claim(User owner, ClaimStatus status, ClaimLine... lines) {
        Claim c = new Claim("EXP-T-" + uid(), owner, "t");
        for (ClaimLine l : lines) {
            if (l.getReceipt() == null && l.getAmount().compareTo(new BigDecimal("500")) > 0) {
                l.setReceipt(receipts.save(new Receipt(owner, "r.pdf", "application/pdf", new byte[] {7}, uid() + uid())));
            }
            c.addLine(l);
        }
        c.setStatus(status);
        if (status != ClaimStatus.DRAFT) c.setSubmittedAt(Instant.now());
        return claims.saveAndFlush(c);
    }

    private static ClaimLine meal(String amount) {
        return new ClaimLine(ExpenseCategory.MEAL, WED, new BigDecimal(amount), "Cafe " + uid());
    }

    private static Violation[] violations(PolicyResult r) {
        return r.lineResults().stream().flatMap(l -> l.violations().stream()).toArray(Violation[]::new);
    }

    @Test
    void br1_mealsOfOtherSubmittedClaimsCount_draftsAndRejectedDoNot() {
        User priya = user();
        claim(priya, ClaimStatus.PENDING_MANAGER, meal("1000.00"));     // counts
        claim(priya, ClaimStatus.DRAFT, meal("900.00"));                 // draft: ignored
        claim(priya, ClaimStatus.AUTO_REJECTED, meal("900.00"));         // rejected: ignored
        claim(user(), ClaimStatus.APPROVED, meal("900.00"));             // other employee: ignored

        Claim atLimit = claim(priya, ClaimStatus.DRAFT, meal("500.00"));
        assertThat(violations(policy.evaluate(atLimit))).isEmpty();

        Claim over = claim(priya, ClaimStatus.DRAFT, meal("500.01"));
        assertThat(violations(policy.evaluate(over))).extracting(Violation::code).containsExactly("MEAL_DAILY_LIMIT");
    }

    @Test
    void br6_duplicateLineOfAnotherEmployeesSubmittedClaim_rejected() {
        claim(user(), ClaimStatus.PENDING_MANAGER, new ClaimLine(ExpenseCategory.CAB, WED, new BigDecimal("450.00"), "Uber-" + "X1"));
        Claim mine = claim(user(), ClaimStatus.DRAFT, new ClaimLine(ExpenseCategory.CAB, WED, new BigDecimal("450"), " uber-x1 "));
        PolicyResult r = policy.evaluate(mine);
        assertThat(violations(r)).extracting(Violation::code).containsExactly("DUPLICATE_LINE");
        assertThat(r.outcome()).isEqualTo(PolicyOutcome.AUTO_REJECTED);
    }

    @Test
    void br7_sameReceiptBytesUnderDifferentFileName_rejected() {
        User a = user();
        String sha = "f".repeat(64);
        Receipt original = receipts.save(new Receipt(a, "taxi.pdf", "application/pdf", new byte[] {1, 2, 3}, sha));
        ClaimLine first = new ClaimLine(ExpenseCategory.CAB, WED, new BigDecimal("300.00"), "Ola " + uid());
        first.setReceipt(original);
        claim(a, ClaimStatus.APPROVED, first);

        Receipt copy = receipts.save(new Receipt(a, "renamed-copy.pdf", "application/pdf", new byte[] {1, 2, 3}, sha));
        ClaimLine again = new ClaimLine(ExpenseCategory.MEAL, WED.minusDays(1), new BigDecimal("300.00"), "Cafe " + uid());
        again.setReceipt(copy);
        Claim second = claim(a, ClaimStatus.DRAFT, again);

        assertThat(violations(policy.evaluate(second))).extracting(Violation::code).containsExactly("DUPLICATE_RECEIPT");
    }

    @Test
    void evaluateAndApply_storesSnapshotOutcomeAndLineViolations() {
        Claim c = claim(user(), ClaimStatus.DRAFT,
                new ClaimLine(ExpenseCategory.CAB, WED, new BigDecimal("2000.01"), "Meru " + uid()));
        PolicyResult r = policy.evaluateAndApply(c);
        claims.saveAndFlush(c);
        em.clear();

        Claim loaded = claims.findById(c.getId()).orElseThrow();
        assertThat(loaded.getPolicyOutcome()).isEqualTo(PolicyOutcome.ROUTED);
        // jsonb re-formats the text ("outcome": "ROUTED"), so compare parsed values, not raw strings
        assertThat(policy.readSnapshot(loaded.getPolicyResult()).requiredLevels()).containsExactly(com.expenseflow.domain.ApprovalLevel.MANAGER);
        assertThat(loaded.getPolicyResult()).contains("CAB_TRIP_LIMIT");
        assertThat(loaded.getLines().get(0).getViolations()).contains("CAB_TRIP_LIMIT").contains("2,000");

        PolicyResult back = policy.readSnapshot(loaded.getPolicyResult());
        assertThat(back.outcome()).isEqualTo(r.outcome());
        assertThat(back.ruleSnapshot().get("CAB_TRIP_LIMIT").limitAmount()).isEqualByComparingTo("2000");
    }

    @Test
    void ruleChange_appliesToNextSubmitOnly() {
        User u = user();
        Claim first = claim(u, ClaimStatus.DRAFT, new ClaimLine(ExpenseCategory.CAB, WED, new BigDecimal("1800.00"), "Ola " + uid()));
        policy.evaluateAndApply(first);
        String firstSnapshot = first.getPolicyResult();
        assertThat(first.getPolicyOutcome()).isEqualTo(PolicyOutcome.AUTO_APPROVED);

        PolicyRule cab = rules.findById(PolicyRule.CAB_TRIP_LIMIT).orElseThrow();
        cab.setLimitAmount(new BigDecimal("1500"));
        rules.saveAndFlush(cab);

        Claim next = claim(u, ClaimStatus.DRAFT, new ClaimLine(ExpenseCategory.CAB, WED.minusDays(1), new BigDecimal("1800.00"), "Ola " + uid()));
        assertThat(policy.evaluateAndApply(next).outcome()).isEqualTo(PolicyOutcome.ROUTED);
        assertThat(first.getPolicyResult()).as("already decided claim keeps its snapshot").isEqualTo(firstSnapshot);
        assertThat(policy.readSnapshot(firstSnapshot).ruleSnapshot().get("CAB_TRIP_LIMIT").limitAmount()).isEqualByComparingTo("2000");
    }
}
