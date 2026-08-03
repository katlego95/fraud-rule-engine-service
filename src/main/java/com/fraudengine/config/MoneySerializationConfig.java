package com.fraudengine.config;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;

@Configuration
class MoneySerializationConfig {

    /**
     * Every BigDecimal crosses the API boundary as a JSON string, so clients cannot coerce an
     * amount into a float. Applied globally rather than per field: a money type missed by an
     * annotation fails silently, and silence is the wrong failure mode for money.
     *
     * <p>WRITE_BIGDECIMAL_AS_PLAIN is required alongside it. Jackson serialises BigDecimal via
     * toString(), which emits scientific notation above certain scales — an amount rendered as
     * "2.5E+3" is a defect in a financial API. Plain output instead throws on an out-of-range
     * scale, which is the failure we want.
     */
    @Bean
    JsonMapperBuilderCustomizer amountsAsStrings() {
        return builder -> builder
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .withConfigOverride(BigDecimal.class,
                        override -> override.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)));
    }
}
