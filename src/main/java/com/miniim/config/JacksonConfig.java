package com.miniim.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer idLongJacksonCustomizer() {
        return builder -> {
            IdLongJsonSerializer serializer = new IdLongJsonSerializer();
            builder.serializerByType(Long.class, serializer);
            builder.serializerByType(Long.TYPE, serializer);
        };
    }
}
