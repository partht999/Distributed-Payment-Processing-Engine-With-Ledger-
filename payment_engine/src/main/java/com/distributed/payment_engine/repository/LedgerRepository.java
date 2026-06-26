package com.distributed.payment_engine.repository;

import com.distributed.payment_engine.model.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByWalletId(Long walletId);
    List<LedgerEntry> findByPaymentId(Long paymentId);
}
