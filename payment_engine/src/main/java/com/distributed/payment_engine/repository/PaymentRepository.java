package com.distributed.payment_engine.repository;

import com.distributed.payment_engine.model.entity.Payment;
import com.distributed.payment_engine.model.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Payment findByIdempotencyKey(String idempotencyKey);
    Payment findByMerchantOrderId(String merchantOrderId);
    List<Payment> findByStatus(PaymentStatus status);
}
