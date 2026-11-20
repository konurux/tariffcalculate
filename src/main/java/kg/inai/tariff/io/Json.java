package kg.inai.tariff.io;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.math.BigDecimal;

/** Общий ObjectMapper и помощники для терпимого чтения полей. */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private Json() {
    }

    /** Первое непустое поле из списка синонимов. */
    public static JsonNode pick(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        return null;
    }

    public static String text(JsonNode node, String def, String... keys) {
        JsonNode v = pick(node, keys);
        return v == null ? def : v.asText();
    }

    public static BigDecimal decimal(JsonNode node, BigDecimal def, String... keys) {
        JsonNode v = pick(node, keys);
        return v == null ? def : toDecimal(v);
    }

    public static BigDecimal toDecimal(JsonNode v) {
        if (v.isNumber()) {
            return v.decimalValue();
        }
        return Numbers.parse(v.asText());
    }

    public static boolean bool(JsonNode node, boolean def, String... keys) {
        JsonNode v = pick(node, keys);
        if (v == null) {
            return def;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        return Numbers.isTruthy(v.asText());
    }
}
