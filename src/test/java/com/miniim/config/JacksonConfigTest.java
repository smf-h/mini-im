package com.miniim.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

public class JacksonConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class);

    @Test
    void idLongFieldsSerializedAsStringButNonIdLongFieldsRemainNumber() {
        contextRunner.run(ctx -> {
            ObjectMapper objectMapper = ctx.getBean(ObjectMapper.class);

            String json = objectMapper.writeValueAsString(new Payload(123L, 100L, 2004874454540382209L, 9007199254740992L));
            JsonNode node = objectMapper.readTree(json);

            assertThat(node.get("id").isTextual()).isTrue();
            assertThat(node.get("total").isNumber()).isTrue();
            assertThat(node.get("toUserId").isTextual()).isTrue();
            assertThat(node.get("from").isTextual()).isTrue();
        });
    }

    record Payload(long id, long total, long toUserId, long from) {
    }
}
