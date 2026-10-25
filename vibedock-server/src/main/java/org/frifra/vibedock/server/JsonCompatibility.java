package org.frifra.vibedock.server;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Preserves omitted primitive defaults in existing settings, designs and device requests. */
final class JsonCompatibility {
    private JsonCompatibility() {}
    static void configure(JsonMapper.Builder builder) {
        builder.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }
    static JsonMapper mapper() {
        var builder=JsonMapper.builder();configure(builder);return builder.build();
    }
}
