package com.tw.docsagent.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Token prices per model, in USD per million tokens. Kept in config so a price change is not a code change. */
@Validated
@ConfigurationProperties("docsagent.pricing")
public record PricingProperties(@NotEmpty Map<String, @Valid ModelPrice> models) {

    public record ModelPrice(
            @NotNull @PositiveOrZero BigDecimal inputPerMtok,
            @NotNull @PositiveOrZero BigDecimal outputPerMtok,
            @NotNull @PositiveOrZero BigDecimal cacheWritePerMtok,
            @NotNull @PositiveOrZero BigDecimal cacheReadPerMtok) {
    }
}
