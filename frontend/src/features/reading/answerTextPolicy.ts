export type WordLimitMode = "ONE_WORD" | "TWO_WORDS" | "TWO_WORDS_AND_ONE_NUMBER" | "THREE_WORDS_AND_ONE_NUMBER" | null;

export function answerWordLimitError(value: string, mode: WordLimitMode | string | null): string | null {
  if (!value.trim() || !mode) return null;

  let words = 0;
  let numbers = 0;
  const tokens = value.normalize("NFC").trim().replace(/\s+/gu, " ").toLocaleLowerCase().split(" ");
  for (const token of tokens) {
    if (/^[0-9]+$/u.test(token)) numbers++;
    else if (/^[\p{L}\p{M}]+(?:-[\p{L}\p{M}]+)*$/u.test(token)) words++;
    else return "Hiện chỉ hỗ trợ từ, từ nối bằng dấu gạch ngang và số nguyên viết bằng chữ số.";
  }

  const maxWords = mode === "ONE_WORD" ? 1 : mode === "THREE_WORDS_AND_ONE_NUMBER" ? 3 : 2;
  const maxNumbers = mode === "TWO_WORDS_AND_ONE_NUMBER" || mode === "THREE_WORDS_AND_ONE_NUMBER" ? 1 : 0;
  if (!["ONE_WORD", "TWO_WORDS", "TWO_WORDS_AND_ONE_NUMBER", "THREE_WORDS_AND_ONE_NUMBER"].includes(mode)) return "Giới hạn đáp án không hợp lệ.";
  if (words > maxWords || numbers > maxNumbers) {
    return mode === "ONE_WORD" ? "Đáp án chỉ được có tối đa một từ."
      : mode === "TWO_WORDS" ? "Đáp án chỉ được có tối đa hai từ."
      : mode === "THREE_WORDS_AND_ONE_NUMBER" ? "Đáp án chỉ được có tối đa ba từ và một số."
      : "Đáp án chỉ được có tối đa hai từ và một số.";
  }
  return null;
}

export function answerErrorsFor(groups: { wordLimitMode: WordLimitMode | string | null; questions: { id: number }[] }[], answers: Record<number, string>) {
  const errors: Record<number, string> = {};
  for (const group of groups) for (const question of group.questions) {
    const error = answerWordLimitError(answers[question.id] || "", group.wordLimitMode);
    if (error) errors[question.id] = error;
  }
  return errors;
}
