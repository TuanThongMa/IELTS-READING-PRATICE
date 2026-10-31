package vn.tuanthongma.ieltsreading.content;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

record PresentationPart(String text, Integer questionIndex, String sourceText) {}

record PresentationTemplate(int version, String kind, String caption, List<String> columns,
                            List<List<List<PresentationPart>>> rows,
                            List<List<PresentationPart>> steps,
                            List<List<PresentationPart>> paragraphs) {}

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

    static PresentationTemplate forLearner(PresentationTemplate value) {
        if (value == null) return null;
        List<List<List<PresentationPart>>> rows = value.rows() == null ? null : value.rows().stream()
                .map(row -> row == null ? null : row.stream().map(PresentationTemplateCodec::withoutSourceText).toList()).toList();
        List<List<PresentationPart>> steps = value.steps() == null ? null : value.steps().stream().map(PresentationTemplateCodec::withoutSourceText).toList();
        List<List<PresentationPart>> paragraphs = value.paragraphs() == null ? null : value.paragraphs().stream().map(PresentationTemplateCodec::withoutSourceText).toList();
        return new PresentationTemplate(value.version(), value.kind(), value.caption(), value.columns(), rows, steps, paragraphs);
    }

    private static List<PresentationPart> withoutSourceText(List<PresentationPart> parts) {
        return parts == null ? null : parts.stream().map(part -> part == null ? null : new PresentationPart(part.text(), part.questionIndex(), null)).toList();
    }
}
