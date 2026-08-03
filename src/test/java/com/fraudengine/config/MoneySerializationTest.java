package com.fraudengine.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import tools.jackson.databind.json.JsonMapper;

class MoneySerializationTest {

    private final JsonMapper mapper = buildMapper();

    private static JsonMapper buildMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        JsonMapperBuilderCustomizer customizer = new MoneySerializationConfig().amountsAsStrings();
        customizer.customize(builder);
        return builder.build();
    }

    record Payment(BigDecimal amount) {}

    @Test
    void amountSerialisesAsString() {
        assertThat(mapper.writeValueAsString(new Payment(new BigDecimal("1234.56"))))
                .isEqualTo("{\"amount\":\"1234.56\"}");
    }

    @Test
    void amountAvoidsScientificNotation() {
        // 2.5E+3 is what BigDecimal.toString() produces for this value; unhandled, the API would
        // publish "2.5E+3" as an amount.
        assertThat(mapper.writeValueAsString(new Payment(new BigDecimal("2.5E+3"))))
                .isEqualTo("{\"amount\":\"2500\"}");
    }

    @Test
    void amountPreservesTrailingZeroScale() {
        assertThat(mapper.writeValueAsString(new Payment(new BigDecimal("10.00"))))
                .isEqualTo("{\"amount\":\"10.00\"}");
    }

    @Test
    void amountRoundTripsWithoutFloatCoercion() {
        // 0.1 + 0.2 is the canonical binary floating point failure; BigDecimal must survive it.
        Payment parsed = mapper.readValue("{\"amount\":\"0.30\"}", Payment.class);

        assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("0.1").add(new BigDecimal("0.2")));
        assertThat(parsed.amount().scale()).isEqualTo(2);
    }
}
