package pt.isep.bmc.issue.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Request bodies are read as written. By default Jackson turns a JSON number
 * or boolean into text for a string field, so {@code "reason": 42} would be
 * stored as the reason "42". Here a string field accepts a JSON string only:
 * anything else is a malformed request ({@code 400}) and stores nothing.
 */
@Configuration(proxyBeanMethods = false)
class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer stringFieldsAcceptOnlyJsonStrings() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, text -> text
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
