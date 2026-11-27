package com.expenseflow.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.expenseflow.domain.ClaimLine;
import com.expenseflow.domain.ClaimStatus;

/**
 * Policy engine context queries look only at OTHER claims whose status is not in {@code excluded}
 * (the policy service passes DRAFT, REJECTED, AUTO_REJECTED: only submitted, non-rejected claims count).
 */
public interface ClaimLineRepository extends JpaRepository<ClaimLine, Long> {

    /** BR-1: sum of the employee's MEAL lines per expense date in other claims. Rows: [LocalDate, BigDecimal]. */
    @Query("""
            select l.expenseDate, sum(l.amount) from ClaimLine l
            where l.claim.employee.id = :employeeId and l.claim.id <> :claimId
              and l.category = com.expenseflow.domain.ExpenseCategory.MEAL
              and l.claim.status not in :excluded and l.expenseDate in :dates
            group by l.expenseDate""")
    List<Object[]> sumMealsByDate(Long employeeId, Long claimId, Collection<ClaimStatus> excluded, Collection<LocalDate> dates);

    /** BR-6: vendor, amount, date of lines in other claims on the given dates (any employee). Rows: [String, BigDecimal, LocalDate]. */
    @Query("""
            select l.vendor, l.amount, l.expenseDate from ClaimLine l
            where l.claim.id <> :claimId and l.claim.status not in :excluded
              and l.expenseDate in :dates and l.vendor is not null""")
    List<Object[]> findVendorAmountDate(Long claimId, Collection<ClaimStatus> excluded, Collection<LocalDate> dates);

    /** BR-7: which of the given receipt hashes are already used on lines of other claims (any employee). */
    @Query("""
            select distinct r.sha256 from ClaimLine l join l.receipt r
            where l.claim.id <> :claimId and l.claim.status not in :excluded and r.sha256 in :hashes""")
    List<String> findUsedReceiptHashes(Long claimId, Collection<ClaimStatus> excluded, Collection<String> hashes);
}
