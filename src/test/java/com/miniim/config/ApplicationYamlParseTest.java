package com.miniim.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApplicationYamlParseTest {

    @Test
    void applicationYaml_ShouldBeParsable() throws Exception {
        var loader = new YamlPropertySourceLoader();
        var sources = loader.load("application", new ClassPathResource("application.yml"));
        assertNotNull(sources);
        assertFalse(sources.isEmpty());
    }
}

