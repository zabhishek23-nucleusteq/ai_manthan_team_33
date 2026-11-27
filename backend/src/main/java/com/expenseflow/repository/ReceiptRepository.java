package com.expenseflow.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

import com.expenseflow.domain.Receipt;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

    /** Ids of earlier receipts with the same file hash (duplicate detection), excluding the given one. */
    @Query("select r.id from Receipt r where r.sha256 = :sha256 and r.id <> :excludeId")
    List<Long> findOtherIdsBySha256(String sha256, Long excludeId);
}
