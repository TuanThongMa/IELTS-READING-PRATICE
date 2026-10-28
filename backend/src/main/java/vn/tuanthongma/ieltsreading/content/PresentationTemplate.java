package vn.tuanthongma.ieltsreading.content;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

record PresentationPart(String text, Integer questionIndex) {}

record PresentationTemplate(int version, String kind, String caption, List<String> columns,
                            List<List<List<PresentationPart>>> rows,
                            List<List<PresentationPart>> steps) {}

final class PresentationTemplateCodec {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PresentationTemplateCodec() {}

    static String write(PresentationTemplate value) {
        if (value == null) return null;
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Không thể lưu bố cục nhóm câu hỏi.", exception);
        }
    }

    static PresentationTemplate read(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return JSON.readValue(value, PresentationTemplate.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Bố cục câu hỏi đã lưu không hợp lệ.", exception);
        }
    }
}
