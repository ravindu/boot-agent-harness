package com.tw.docsagent.agent;

import com.tw.docsagent.config.AnthropicProperties;
import com.tw.docsagent.config.PricingProperties;
import com.tw.docsagent.config.PricingProperties.ModelPrice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** Turns token usage into USD using configured prices. Fails at startup if a configured model has no price. */
@Component
public class CostCalculator {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final PricingProperties pricing;

    public CostCalculator(PricingProperties pricing, AnthropicProperties anthropic) {
        this.pricing = pricing;
        price(anthropic.agentModel());
        price(anthropic.helperModel());
    }

    public BigDecimal cost(String model, TokenUsage usage) {
        ModelPrice price = price(model);
        BigDecimal perMillion = price.inputPerMtok().multiply(BigDecimal.valueOf(usage.input()))
                .add(price.outputPerMtok().multiply(BigDecimal.valueOf(usage.output())))
                .add(price.cacheWritePerMtok().multiply(BigDecimal.valueOf(usage.cacheWrite())))
                .add(price.cacheReadPerMtok().multiply(BigDecimal.valueOf(usage.cacheRead())));
        return perMillion.divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    private ModelPrice price(String model) {
        ModelPrice price = pricing.models().get(model);
        if (price == null) {
            throw new IllegalStateException("No price configured for model " + model
                    + " (docsagent.pricing.models)");
        }
        return price;
    }
}
