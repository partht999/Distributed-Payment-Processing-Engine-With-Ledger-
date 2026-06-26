package com.distributed.payment_engine.repository;

import com.distributed.payment_engine.model.entity.WebhookConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Webhook Config Repository.
 */
@Repository
public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, Long> {

    /**
     * Find the webhook config for a specific wallet.
     * Used when routing events — "does this wallet have a webhook registered?"
     */
    Optional<WebhookConfig> findByWalletIdAndActiveTrue(Long walletId);
}
