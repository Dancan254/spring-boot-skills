import java.util.Base64;
import java.util.Map;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class PageCursor {

    // the one place opaque-cursor <-> keyset-position encoding lives; keeps controllers clean
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> KEYS = new TypeReference<>() {};

    private PageCursor() {}

    public static ScrollPosition decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return ScrollPosition.keyset();
        try {
            return ScrollPosition.forward(MAPPER.readValue(Base64.getUrlDecoder().decode(cursor), KEYS));
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new InvalidCursorException(cursor);
        }
    }

    public static String encode(ScrollPosition position) {
        if (!(position instanceof KeysetScrollPosition keyset)) {
            throw new IllegalArgumentException("Only keyset positions can be encoded as a cursor");
        }
        try {
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MAPPER.writeValueAsBytes(keyset.getKeys()));
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to encode cursor", ex);
        }
    }
}
