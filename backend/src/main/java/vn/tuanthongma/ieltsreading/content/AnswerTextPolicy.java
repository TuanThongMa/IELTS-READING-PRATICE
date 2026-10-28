package vn.tuanthongma.ieltsreading.content;

import java.text.Normalizer;
import java.util.Locale;

final class AnswerTextPolicy {
    private AnswerTextPolicy() {}

    static boolean isWithinLimit(String answer, String mode) {
        return validationError(answer, mode) == null;
    }

    static String validationError(String answer, String mode) {
        if (answer == null || answer.isBlank() || mode == null) return null;

        int words = 0;
        int numbers = 0;
        String normalized = Normalizer.normalize(answer, Normalizer.Form.NFC)
                .strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
        for (String token : normalized.split(" ")) {
            if (token.matches("[0-9]+")) numbers++;
            else if (token.matches("[\\p{L}\\p{M}]+(?:-[\\p{L}\\p{M}]+)*")) words++;
            else return "Định dạng chưa được hỗ trợ. Hiện chỉ hỗ trợ từ, từ nối bằng dấu gạch ngang và số nguyên viết bằng chữ số.";
        }

        if (!"ONE_WORD".equals(mode) && !"TWO_WORDS".equals(mode) && !"TWO_WORDS_AND_ONE_NUMBER".equals(mode)
                && !"THREE_WORDS_AND_ONE_NUMBER".equals(mode))
            return "Giới hạn đáp án không hợp lệ.";
        int maxWords = switch (mode) {
            case "ONE_WORD" -> 1;
            case "TWO_WORDS", "TWO_WORDS_AND_ONE_NUMBER" -> 2;
            case "THREE_WORDS_AND_ONE_NUMBER" -> 3;
            default -> -1;
        };
        int maxNumbers = "TWO_WORDS_AND_ONE_NUMBER".equals(mode) ? 1 : 0;
        if ("THREE_WORDS_AND_ONE_NUMBER".equals(mode)) maxNumbers = 1;
        if (maxWords >= 0 && words <= maxWords && numbers <= maxNumbers) return null;
        return switch (mode) {
            case "ONE_WORD" -> "Đáp án chỉ được có tối đa một từ.";
            case "TWO_WORDS" -> "Đáp án chỉ được có tối đa hai từ.";
            case "TWO_WORDS_AND_ONE_NUMBER" -> "Đáp án chỉ được có tối đa hai từ và một số.";
            case "THREE_WORDS_AND_ONE_NUMBER" -> "Đáp án chỉ được có tối đa ba từ và một số.";
            default -> "Giới hạn đáp án không hợp lệ.";
        };
    }
}
