package com.firstfood.plan.dto;

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
 * Full replacement of the plan's CURRENT terms (PUT). There is no consumptionType: a plan's
 * type is fixed at creation - a different type is a different plan. Applying this never
 * touches an existing subscription (rules.md Rule 7.5); only subscriptions created after it
 * see the new terms.
 */
public record UpdatePlanRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @Min(1) @Max(366) Integer durationDays,
        @Min(1) @Max(1000) Integer mealQuantity,
        @NotNull @DecimalMin(value = "0.01", message = "Price must be greater than zero")
                @Digits(integer = 8, fraction = 2, message = "Price allows up to 8 digits and 2 decimals") BigDecimal price,
        @NotNull @Valid PolicyRequest policy) {
}
