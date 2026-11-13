package vn.tuanthongma.ieltsreading.content;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnswerTextPolicyTest {
    @Test
    void acceptsThousandsSeparatorOnlyWhenGroupingAndNumberLimitAreValid() {
        assertTrue(AnswerTextPolicy.isWithinLimit("1,000 kg", "TWO_WORDS_AND_ONE_NUMBER"));
        assertTrue(AnswerTextPolicy.isWithinLimit("1000 kg", "TWO_WORDS_AND_ONE_NUMBER"));
        assertFalse(AnswerTextPolicy.isWithinLimit("1,00 kg", "TWO_WORDS_AND_ONE_NUMBER"));
        assertFalse(AnswerTextPolicy.isWithinLimit("1,000 kg", "ONE_WORD"));
    }

    @Test
    void oneWordAndOneNumberAcceptsEitherFormButRejectsTwoWords() {
        assertTrue(AnswerTextPolicy.isWithinLimit("fifty", "ONE_WORD_AND_ONE_NUMBER"));
        assertTrue(AnswerTextPolicy.isWithinLimit("50", "ONE_WORD_AND_ONE_NUMBER"));
        assertTrue(AnswerTextPolicy.isWithinLimit("speed 50", "ONE_WORD_AND_ONE_NUMBER"));
        assertFalse(AnswerTextPolicy.isWithinLimit("maximum speed", "ONE_WORD_AND_ONE_NUMBER"));
        assertFalse(AnswerTextPolicy.isWithinLimit("50 100", "ONE_WORD_AND_ONE_NUMBER"));
    }
}
