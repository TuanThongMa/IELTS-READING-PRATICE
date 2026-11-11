const MAX_SENTENCE_WORDS = 80;
const CONTEXT_WORDS = 10;
const ABBREVIATION_END = /(?:\b(?:Mr|Mrs|Ms|Dr|Prof|Sr|Jr|St|vs|etc)|\b(?:e\.g|i\.e|U\.S|U\.K))\.\s*$/i;

type Word = { index: number; segment: string };

export function readingContext(body: string, start: number, end: number): string {
  if (start < 0 || end <= start || end > body.length) return "";
  const paragraphStart = body.lastIndexOf("\n", start - 1) + 1;
  const nextBreak = body.indexOf("\n", end);
  const paragraph = body.slice(paragraphStart, nextBreak < 0 ? body.length : nextBreak);
  const localStart = start - paragraphStart;
  const localEnd = end - paragraphStart;
  const words = wordSegments(paragraph);

  if (typeof Intl.Segmenter === "function") {
    const sentences = [...new Intl.Segmenter("en", { granularity: "sentence" }).segment(paragraph)];
    const containing = (position: number) => sentences.findIndex(part => part.index <= position && position < part.index + part.segment.length);
    let first = containing(localStart);
    let last = containing(localEnd - 1);
    if (first >= 0 && last >= first) {
      while (first > 0 && ABBREVIATION_END.test(sentences[first - 1].segment)) first--;
      while (last + 1 < sentences.length && ABBREVIATION_END.test(sentences[last].segment)) last++;
      const sentence = paragraph.slice(sentences[first].index, sentences[last].index + sentences[last].segment.length).trim();
      if (/[.!?]["'’”)]*$/u.test(sentence) && wordSegments(sentence).length <= MAX_SENTENCE_WORDS && sentence.length <= 4000) return sentence;
    }
  }

  const before = words.filter(word => word.index + word.segment.length <= localStart).slice(-CONTEXT_WORDS);
  const after = words.filter(word => word.index >= localEnd).slice(0, CONTEXT_WORDS);
  const from = before[0]?.index ?? localStart;
  const lastAfter = after.at(-1);
  const to = lastAfter ? lastAfter.index + lastAfter.segment.length : localEnd;
  const trailing = paragraph.slice(to).match(/^[.!?,;:)\]”’"]+/u)?.[0].length ?? 0;
  return paragraph.slice(from, to + trailing).trim();
}

function wordSegments(text: string): Word[] {
  if (typeof Intl.Segmenter === "function") {
    return [...new Intl.Segmenter("en", { granularity: "word" }).segment(text)]
      .filter(part => part.isWordLike).map(part => ({ index: part.index, segment: part.segment }));
  }
  return [...text.matchAll(/[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*/gu)]
    .map(match => ({ index: match.index, segment: match[0] }));
}
