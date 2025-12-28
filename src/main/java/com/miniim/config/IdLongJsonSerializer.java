package com.miniim.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;

import java.io.IOException;
import java.util.Locale;

/**
 * 将“语义为 ID 的 long/Long 字段”序列化为字符串，避免 JS Number 精度丢失。
 *
 * <p>原则：</p>
 * <ul>
 *   <li>ID 字段：输出 JSON string（例如 {@code "toUserId":"123"}）</li>
 *   <li>非 ID 字段：保持 JSON number（例如分页 {@code "total": 100}）</li>
 * </ul>
 *
 * <p>当前通过字段名启发式判定：</p>
 * <ul>
 *   <li>{@code id} / 以 {@code Id} 结尾的字段</li>
 *   <li>WebSocket envelope 的 {@code from}/{@code to}</li>
 *   <li>以 {@code By} 结尾的字段（例如 {@code createdBy}）</li>
 * </ul>
 */
public class IdLongJsonSerializer extends JsonSerializer<Long> implements ContextualSerializer {

    private final boolean asString;

    public IdLongJsonSerializer() {
        this(false);
    }

    private IdLongJsonSerializer(boolean asString) {
        this.asString = asString;
    }

    @Override
    public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        if (asString) {
            gen.writeString(Long.toString(value));
            return;
        }
        gen.writeNumber(value);
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) throws JsonMappingException {
        if (property == null) {
            return this;
        }
        return new IdLongJsonSerializer(isIdFieldName(property.getName()));
    }

    static boolean isIdFieldName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if ("id".equals(lower)) {
            return true;
        }
        if (lower.endsWith("id")) {
            return true;
        }
        if ("from".equals(lower) || "to".equals(lower)) {
            return true;
        }
        return lower.endsWith("by");
    }
}

