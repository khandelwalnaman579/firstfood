package com.firstfood.plan.dto;

import com.firstfood.plan.ConsumptionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Deliberately has no provider, status, currency or actor fields: the provider comes from
 * the path (and is authorized server-side), a new plan starts ACTIVE, the currency is INR,
 * and the actor is the JWT principal. {@code durationDays} applies to DAY plans and
 * {@code mealQuantity} to MEAL plans; sending the wrong one is rejected (PlanRules).
 */
public record CreatePlanRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @NotNull ConsumptionType consumptionType,
        @Min(1) @Max(366) Integer durationDays,
        @Min(1) @Max(1000) Integer mealQuantity,
        @NotNull @DecimalMin(value = "0.01", message = "Price must be greater than zero")
                @Digits(integer = 8, fraction = 2, message = "Price allows up to 8 digits and 2 decimals") BigDecimal price,
        @NotNull @Valid PolicyRequest policy) {
}
