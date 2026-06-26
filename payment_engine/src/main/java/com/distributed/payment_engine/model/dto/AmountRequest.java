package com.distributed.payment_engine.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class AmountRequest {
    @NotNull(message = "amount is required")
    @Min(value = 1, message = "amount must be greater than 0")
    private Long amount;

    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
}
