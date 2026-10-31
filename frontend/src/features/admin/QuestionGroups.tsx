import { useEffect, useRef, useState, type FormEvent } from "react";
import { api } from "../../api";
import styles from "./AdminPage.module.css";

type Variant = "TRUE_FALSE_NOT_GIVEN" | "YES_NO_NOT_GIVEN" | "MULTIPLE_CHOICE_SINGLE" | "MULTIPLE_CHOICE_MULTIPLE" | "SENTENCE_COMPLETION" | "NOTE_COMPLETION" | "SUMMARY_COMPLETION" | "TABLE_COMPLETION" | "FLOW_CHART_COMPLETION" | "SHORT_ANSWER" | "MATCHING_INFORMATION" | "MATCHING_HEADINGS" | "MATCHING_FEATURES" | "MATCHING_SENTENCE_ENDINGS";
type WordLimit = "ONE_WORD" | "TWO_WORDS" | "TWO_WORDS_AND_ONE_NUMBER" | "THREE_WORDS_AND_ONE_NUMBER";
type InputMode = "TEXT_ENTRY" | "SHARED_CHOICE_POOL";
type Candidate = { id?: number; label: string; text: string };
type Question = { id?: number; questionText: string; answer: string; explanation: string; options: string[]; blankText: string; alternatives: string[] };
type SavedQuestion = Question & { id: number; questionNumber: number; explanation: string | null };
type Part = { text: string | null; questionIndex: number | null; sourceText?: string | null };
type Template = { version: number; kind: "TABLE" | "FLOW_CHART" | "PARAGRAPH"; caption: string | null; columns: string[] | null; rows: Part[][][] | null; steps: Part[][] | null; paragraphs: Part[][] | null };
type Group = { id: number; taskVariant: Variant; instructions: string; questions: SavedQuestion[]; wordLimitMode: WordLimit | null; candidates: Candidate[]; candidateReuse: boolean; answerInputMode: InputMode | "PER_QUESTION_CHOICE"; correctAnswerLabels: string[]; presentationTemplate: Template | null };

const names: Record<Variant, string> = {
  TRUE_FALSE_NOT_GIVEN: "True / False / Not Given", YES_NO_NOT_GIVEN: "Yes / No / Not Given", MULTIPLE_CHOICE_SINGLE: "Multiple Choice – một đáp án", MULTIPLE_CHOICE_MULTIPLE: "Multiple Choice – nhiều đáp án",
  SENTENCE_COMPLETION: "Sentence Completion", NOTE_COMPLETION: "Note Completion", SUMMARY_COMPLETION: "Summary Completion", TABLE_COMPLETION: "Table Completion", FLOW_CHART_COMPLETION: "Flow-chart Completion", SHORT_ANSWER: "Short Answer",
  MATCHING_INFORMATION: "Matching Information", MATCHING_HEADINGS: "Matching Headings", MATCHING_FEATURES: "Matching Features", MATCHING_SENTENCE_ENDINGS: "Matching Sentence Endings",
};
const fixedAnswers: Partial<Record<Variant, string[]>> = { TRUE_FALSE_NOT_GIVEN: ["TRUE", "FALSE", "NOT GIVEN"], YES_NO_NOT_GIVEN: ["YES", "NO", "NOT GIVEN"] };
const limits: Record<WordLimit, string> = { ONE_WORD: "ONE WORD ONLY", TWO_WORDS: "NO MORE THAN TWO WORDS", TWO_WORDS_AND_ONE_NUMBER: "NO MORE THAN TWO WORDS AND/OR A NUMBER", THREE_WORDS_AND_ONE_NUMBER: "NO MORE THAN THREE WORDS AND/OR A NUMBER" };
const variants = Object.keys(names) as Variant[];
const emptyQuestion = (): Question => ({ questionText: "", answer: "", explanation: "", options: ["", ""], blankText: "", alternatives: [] });
const emptyTextPart = (): Part => ({ text: "", questionIndex: null, sourceText: null });
const questionPart = (questionIndex: number, sourceText: string | null = null): Part => ({ text: null, questionIndex, sourceText });
const optionLabel = (index: number) => String.fromCharCode(65 + index);
const blankToken = (questionNumber: number) => `⟦Câu ${questionNumber}⟧`;
const isStructured = (variant: Variant) => variant === "TABLE_COMPLETION" || variant === "FLOW_CHART_COMPLETION";
const supportsParagraphLayout = (variant: Variant, mode: InputMode) => variant === "SENTENCE_COMPLETION" || variant === "NOTE_COMPLETION" || (variant === "SUMMARY_COMPLETION" && mode === "TEXT_ENTRY");
const isTextEntry = (variant: Variant, mode: InputMode) => ["SENTENCE_COMPLETION", "NOTE_COMPLETION", "TABLE_COMPLETION", "FLOW_CHART_COMPLETION", "SHORT_ANSWER"].includes(variant) || (variant === "SUMMARY_COMPLETION" && mode === "TEXT_ENTRY");
const isSharedChoice = (variant: Variant, mode: InputMode) => variant.startsWith("MATCHING_") || (variant === "SUMMARY_COMPLETION" && mode === "SHARED_CHOICE_POOL");
const isMultiple = (variant: Variant) => variant === "MULTIPLE_CHOICE_MULTIPLE";
const defaultTemplate = (variant: Variant): Template | null => variant === "TABLE_COMPLETION"
  ? { version: 1, kind: "TABLE", caption: "", columns: ["Cột 1", "Cột 2"], rows: [[[emptyTextPart()], [emptyTextPart()]]], steps: null, paragraphs: null }
  : variant === "FLOW_CHART_COMPLETION" ? { version: 1, kind: "FLOW_CHART", caption: "", columns: null, rows: null, steps: [[emptyTextPart()]], paragraphs: null } : null;

function sourceTextByQuestion(template: Template | null) {
  const sourceTexts = new Map<number, string>();
  template?.paragraphs?.flat().forEach(part => {
    if (part.questionIndex !== null && part.sourceText) sourceTexts.set(part.questionIndex, part.sourceText);
  });
  return sourceTexts;
}

function paragraphDraftFromTemplate(template: Template | null, firstNumber: number) {
  return template?.paragraphs?.map(paragraph => paragraph.map(part => part.questionIndex === null
    ? part.text ?? "" : blankToken(firstNumber + part.questionIndex)).join("")).join("\n\n") ?? "";
}

function paragraphTemplateFromDraft(value: string, firstNumber: number, sourceTexts: Map<number, string>): Part[][] {
  const paragraphs = value.split(/\n{2,}/u).map(text => {
    const parts: Part[] = [];
    const pattern = /⟦Câu (\d+)⟧/gu;
    let cursor = 0;
    for (const match of text.matchAll(pattern)) {
      const start = match.index ?? 0;
      if (start > cursor) parts.push({ text: text.slice(cursor, start), questionIndex: null, sourceText: null });
      const questionIndex = Number(match[1]) - firstNumber;
      parts.push(questionPart(questionIndex, sourceTexts.get(questionIndex) ?? null));
      cursor = start + match[0].length;
    }
    if (cursor < text.length) parts.push({ text: text.slice(cursor), questionIndex: null, sourceText: null });
    return parts;
  }).filter(paragraph => paragraph.length > 0);
  return paragraphs.length ? paragraphs : [[]];
}

function wordError(value: string, limit: WordLimit) {
  if (!value.trim()) return "";
  let words = 0, numbers = 0;
  for (const token of value.normalize("NFC").trim().split(/\s+/u)) {
    if (/^[0-9]+$/u.test(token)) numbers++;
    else if (/^[\p{L}\p{M}]+(?:-[\p{L}\p{M}]+)*$/u.test(token)) words++;
    else return "Chỉ hỗ trợ từ, từ có gạch nối hoặc số nguyên.";
  }
  const maxWords = limit === "ONE_WORD" ? 1 : limit === "THREE_WORDS_AND_ONE_NUMBER" ? 3 : 2;
  const maxNumbers = limit === "TWO_WORDS_AND_ONE_NUMBER" || limit === "THREE_WORDS_AND_ONE_NUMBER" ? 1 : 0;
  return words > maxWords || numbers > maxNumbers ? `Đáp án vượt giới hạn ${limits[limit]}.` : "";
}

export function QuestionGroups({ passageId, canCreate, onDirtyChange }: { passageId: number; canCreate: boolean; onDirtyChange: (dirty: boolean) => void }) {
  const [groups, setGroups] = useState<Group[]>([]), [loading, setLoading] = useState(true), [adding, setAdding] = useState(false), [editing, setEditing] = useState<Group | null>(null);
  const [variant, setVariant] = useState<Variant>("TRUE_FALSE_NOT_GIVEN"), [inputMode, setInputMode] = useState<InputMode>("TEXT_ENTRY"), [wordLimit, setWordLimit] = useState<WordLimit>("ONE_WORD");
  const [instructions, setInstructions] = useState(""), [questions, setQuestions] = useState<Question[]>([emptyQuestion()]), [candidates, setCandidates] = useState<Candidate[]>([]), [reuse, setReuse] = useState(false), [correctLabels, setCorrectLabels] = useState<string[]>([]), [template, setTemplate] = useState<Template | null>(null);
  const [paragraphDraft, setParagraphDraft] = useState(""), [selectedRange, setSelectedRange] = useState({ start: 0, end: 0 });
  const paragraphTextarea = useRef<HTMLTextAreaElement>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState(""), [notice, setNotice] = useState("");
  const textEntry = isTextEntry(variant, inputMode), sharedChoice = isSharedChoice(variant, inputMode), multiple = isMultiple(variant), tableFlow = isStructured(variant), structured = tableFlow || template?.kind === "PARAGRAPH";
  const firstNumber = groups.slice(0, editing ? Math.max(0, groups.findIndex(group => group.id === editing.id)) : groups.length).reduce((count, group) => count + group.questions.length, 1);
  const dirty = adding && (!!editing || variant !== "TRUE_FALSE_NOT_GIVEN" || !!instructions || !!paragraphDraft.trim() || questions.some(q => q.questionText || q.answer || q.blankText || q.explanation || q.options.some(Boolean)) || candidates.some(c => c.label || c.text) || !!template?.paragraphs?.some(paragraph => paragraph.some(part => part.text?.trim())));

  useEffect(() => { onDirtyChange(dirty); return () => onDirtyChange(false); }, [dirty, onDirtyChange]);
  useEffect(() => { void load(); }, [passageId]);

  async function load() {
    setLoading(true);
    try { const response = await api(`/api/admin/passages/${passageId}/question-groups`); if (!response.ok) throw new Error(); setGroups(await response.json() as Group[]); }
    catch { setError("Không thể tải nhóm câu hỏi."); }
    finally { setLoading(false); }
  }

  function reset() {
    setAdding(false); setEditing(null); setVariant("TRUE_FALSE_NOT_GIVEN"); setInputMode("TEXT_ENTRY"); setWordLimit("ONE_WORD"); setInstructions(""); setQuestions([emptyQuestion()]); setCandidates([]); setReuse(false); setCorrectLabels([]); setTemplate(null); setParagraphDraft(""); setSelectedRange({ start: 0, end: 0 }); setError("");
  }
  function updateQuestion(index: number, change: Partial<Question>) { setQuestions(current => current.map((question, i) => i === index ? { ...question, ...change } : question)); }
  function changeVariant(next: Variant) { setVariant(next); setInputMode("TEXT_ENTRY"); setQuestions(isStructured(next) ? [] : [emptyQuestion()]); setCandidates([]); setCorrectLabels([]); setReuse(false); setTemplate(defaultTemplate(next)); setParagraphDraft(""); setSelectedRange({ start: 0, end: 0 }); }
  function edit(group: Group) {
    setEditing(group); setAdding(true); setVariant(group.taskVariant); setInputMode(group.answerInputMode === "SHARED_CHOICE_POOL" ? "SHARED_CHOICE_POOL" : "TEXT_ENTRY"); setWordLimit(group.wordLimitMode ?? "ONE_WORD"); setInstructions(group.instructions);
    setQuestions(group.questions.map(question => ({ ...question, explanation: question.explanation ?? "", alternatives: question.alternatives ?? [], options: question.options ?? [] })));
    const savedTemplate = group.presentationTemplate ?? defaultTemplate(group.taskVariant);
    const groupIndex = groups.findIndex(item => item.id === group.id);
    const groupFirstNumber = groups.slice(0, groupIndex).reduce((count, item) => count + item.questions.length, 1);
    setCandidates(group.candidates); setReuse(group.candidateReuse); setCorrectLabels(group.correctAnswerLabels ?? []); setTemplate(savedTemplate);
    setParagraphDraft(savedTemplate?.kind === "PARAGRAPH" ? paragraphDraftFromTemplate(savedTemplate, groupFirstNumber) : ""); setSelectedRange({ start: 0, end: 0 }); setError(""); setNotice("");
  }

  function updatePart(target: { row?: number; cell?: number; step?: number; paragraph?: number; part: number }, change: Partial<Part>) {
    setTemplate(current => {
      if (!current) return current;
      if (current.kind === "TABLE" && current.rows && target.row !== undefined && target.cell !== undefined) {
        const rows = current.rows.map((row, r) => r !== target.row ? row : row.map((cell, c) => c !== target.cell ? cell : cell.map((part, p) => p === target.part ? { ...part, ...change } : part)));
        return { ...current, rows };
      }
      if (current.kind === "FLOW_CHART" && current.steps && target.step !== undefined) {
        const steps = current.steps.map((step, s) => s !== target.step ? step : step.map((part, p) => p === target.part ? { ...part, ...change } : part));
        return { ...current, steps };
      }
      if (current.kind === "PARAGRAPH" && current.paragraphs && target.paragraph !== undefined) {
        const paragraphs = current.paragraphs.map((paragraph, p) => p !== target.paragraph ? paragraph : paragraph.map((part, i) => i === target.part ? { ...part, ...change } : part));
        return { ...current, paragraphs };
      }
      return current;
    });
  }
  function mutateCell(row: number, cell: number, action: (parts: Part[]) => Part[]) {
    setTemplate(current => current?.kind === "TABLE" && current.rows ? { ...current, rows: current.rows.map((items, r) => r !== row ? items : items.map((parts, c) => c !== cell ? parts : action(parts))) } : current);
  }
  function mutateStep(step: number, action: (parts: Part[]) => Part[]) {
    setTemplate(current => current?.kind === "FLOW_CHART" && current.steps ? { ...current, steps: current.steps.map((parts, i) => i === step ? action(parts) : parts) } : current);
  }
  function addQuestionBlank(target: { row?: number; cell?: number; step?: number }) {
    const questionIndex = questions.length;
    setQuestions(current => [...current, emptyQuestion()]);
    const blank: Part = { text: null, questionIndex };
    if (target.row !== undefined && target.cell !== undefined) mutateCell(target.row, target.cell, parts => [...parts, blank]);
    else if (target.step !== undefined) mutateStep(target.step, parts => [...parts, blank]);
  }
  function removeQuestion(index: number) {
    setQuestions(current => current.filter((_, i) => i !== index));
    if (multiple) setCorrectLabels(current => current.filter((_, i) => i !== index));
    if (template?.kind === "PARAGRAPH" && template.paragraphs) {
      const sourcePart = template.paragraphs.flat().find(part => part.questionIndex === index);
      const restoredText = sourcePart?.sourceText ?? questions[index]?.answer ?? "";
      const nextDraft = paragraphDraft.replace(/⟦Câu (\d+)⟧/gu, (token, rawNumber: string) => {
        const questionIndex = Number(rawNumber) - firstNumber;
        if (questionIndex === index) return restoredText;
        return questionIndex > index ? blankToken(Number(rawNumber) - 1) : token;
      });
      const nextSources = new Map<number, string>();
      sourceTextByQuestion(template).forEach((sourceText, questionIndex) => {
        if (questionIndex < index) nextSources.set(questionIndex, sourceText);
        if (questionIndex > index) nextSources.set(questionIndex - 1, sourceText);
      });
      setParagraphDraft(nextDraft);
      setTemplate({ ...template, paragraphs: paragraphTemplateFromDraft(nextDraft, firstNumber, nextSources) });
      return;
    }
    setTemplate(current => {
      if (!current) return current;
      const remap = (parts: Part[]) => parts.flatMap(part => part.questionIndex === index ? [] : part.questionIndex !== null && part.questionIndex > index ? [{ ...part, questionIndex: part.questionIndex - 1 }] : [part]);
      if (current.kind === "TABLE" && current.rows) return { ...current, rows: current.rows.map(row => row.map(remap)) };
      if (current.kind === "FLOW_CHART" && current.steps) return { ...current, steps: current.steps.map(remap) };
      return current;
    });
  }

  function updateParagraphDraft(value: string) {
    setParagraphDraft(value);
    setTemplate(current => current?.kind === "PARAGRAPH"
      ? { ...current, paragraphs: paragraphTemplateFromDraft(value, firstNumber, sourceTextByQuestion(current)) }
      : current);
  }

  function createParagraphBlank() {
    const field = paragraphTextarea.current;
    const start = field?.selectionStart ?? selectedRange.start;
    const end = field?.selectionEnd ?? selectedRange.end;
    const selection = paragraphDraft.slice(start, end);
    const answer = selection.trim();
    if (!answer) { setError("Chọn từ hoặc cụm từ trong đoạn văn để tạo ô trống."); return; }
    const actualStart = start + selection.indexOf(answer);
    const actualEnd = actualStart + answer.length;
    const tokenPattern = /⟦Câu (\d+)⟧/gu;
    const existingTokens = [...paragraphDraft.matchAll(tokenPattern)];
    if (existingTokens.some(match => {
      const tokenStart = match.index ?? 0;
      return tokenStart < actualEnd && tokenStart + match[0].length > actualStart;
    })) { setError("Không thể tạo ô trống trên một câu hỏi đã có."); return; }
    const questionIndex = existingTokens.filter(match => (match.index ?? 0) + match[0].length <= actualStart).length;
    const insertedDraft = paragraphDraft.slice(0, actualStart) + "⟦Ô MỚI⟧" + paragraphDraft.slice(actualEnd);
    const shiftedDraft = insertedDraft.replace(tokenPattern, (token, rawNumber: string) => {
      const currentIndex = Number(rawNumber) - firstNumber;
      return currentIndex >= questionIndex ? blankToken(Number(rawNumber) + 1) : token;
    }).replace("⟦Ô MỚI⟧", blankToken(firstNumber + questionIndex));
    const nextSources = new Map<number, string>();
    sourceTextByQuestion(template).forEach((sourceText, currentIndex) => nextSources.set(currentIndex >= questionIndex ? currentIndex + 1 : currentIndex, sourceText));
    nextSources.set(questionIndex, answer);
    setQuestions(current => [...current.slice(0, questionIndex), { ...emptyQuestion(), answer }, ...current.slice(questionIndex)]);
    setParagraphDraft(shiftedDraft);
    setTemplate(current => current?.kind === "PARAGRAPH"
      ? { ...current, paragraphs: paragraphTemplateFromDraft(shiftedDraft, firstNumber, nextSources) }
      : current);
    setError("");
    const marker = blankToken(firstNumber + questionIndex);
    const markerEnd = shiftedDraft.indexOf(marker) + marker.length;
    setSelectedRange({ start: markerEnd, end: markerEnd });
    window.requestAnimationFrame(() => {
      const textarea = paragraphTextarea.current;
      if (!textarea) return;
      textarea.focus();
      textarea.setSelectionRange(markerEnd, markerEnd);
    });
  }

  function selectParagraphLayout(enabled: boolean) {
    if (!enabled) {
      if (paragraphDraft.trim() && !window.confirm("Chuyển sang câu hỏi riêng sẽ xóa đoạn văn đã dán. Đáp án và giải thích từng câu được giữ lại. Tiếp tục?")) return;
      setTemplate(null);
      setParagraphDraft("");
      if (!questions.length) setQuestions([emptyQuestion()]);
      return;
    }
    const hasQuestionContent = questions.some(question => question.questionText.trim() || question.answer.trim() || question.blankText.trim() || question.alternatives.some(Boolean));
    if (hasQuestionContent && !window.confirm("Chuyển sang biên soạn đoạn văn sẽ xóa các câu và đáp án riêng đang nhập trong biểu mẫu. Tiếp tục?")) return;
    setQuestions([]);
    setParagraphDraft("");
    setSelectedRange({ start: 0, end: 0 });
    setTemplate({ version: 1, kind: "PARAGRAPH", caption: null, columns: null, rows: null, steps: null, paragraphs: [[]] });
  }

  function valid() {
    const instructionsRequired = tableFlow || variant === "SHORT_ANSWER" || multiple || (!textEntry && !sharedChoice && !fixedAnswers[variant] && variant !== "MULTIPLE_CHOICE_SINGLE");
    if ((instructionsRequired && !instructions.trim()) || !questions.length) return false;
    if (textEntry && questions.some(q => !q.answer.trim() || q.alternatives.some(item => !item.trim()) || (variant === "SHORT_ANSWER" ? !q.questionText.trim() : !structured && !q.questionText.trim() && !q.blankText.trim()) || [q.answer, ...q.alternatives].some(answer => wordError(answer, wordLimit)))) return false;
    if (!textEntry && !multiple && !structured && questions.some(q => !q.questionText.trim())) return false;
    if (multiple) {
      const options = questions[0]?.options ?? [];
      if (options.length < 2 || options.length > 26 || options.some(option => !option.trim()) || questions.some(q => JSON.stringify(q.options) !== JSON.stringify(options)) || correctLabels.length !== questions.length || new Set(correctLabels).size !== correctLabels.length || correctLabels.some(item => !options.some((_, index) => optionLabel(index) === item))) return false;
    }
    if (variant === "MULTIPLE_CHOICE_SINGLE" && questions.some(q => q.options.length < 2 || q.options.length > 26 || q.options.some(o => !o.trim()) || !q.answer || optionLabel(q.options.indexOf(q.answer)) !== q.answer)) return false;
    if (sharedChoice) {
      const labels = candidates.map(candidate => candidate.label.trim());
      if (!labels.length || candidates.some(candidate => !candidate.label.trim() || !candidate.text.trim()) || new Set(labels).size !== labels.length || questions.some(q => !labels.includes(q.answer)) || (!reuse && new Set(questions.map(q => q.answer)).size !== questions.length)) return false;
    }
    if (fixedAnswers[variant] && questions.some(q => !fixedAnswers[variant]!.includes(q.answer))) return false;
    if (structured) {
      if (!template) return false;
      const referenced: number[] = [];
      const checkParts = (parts: Part[]) => parts.every(part => {
        if (part.questionIndex !== null) {
          if (part.sourceText != null && (!part.sourceText.trim() || part.sourceText.length > 2000)) return false;
          if (part.questionIndex < 0 || part.questionIndex >= questions.length) return false;
          referenced.push(part.questionIndex); return true;
        }
        return part.sourceText == null && !!part.text?.trim() && part.text.length <= 2000;
      });
      if (template.kind === "TABLE") {
        if (!template.columns || template.columns.length < 2 || template.columns.length > 8 || template.columns.some(column => !column.trim()) || !template.rows?.length || template.rows.length > 40 || template.rows.some(row => row.length !== template.columns!.length || row.some(cell => !checkParts(cell)))) return false;
      } else if (template.kind === "FLOW_CHART") {
        if (!template.steps?.length || template.steps.length > 40 || template.steps.some(step => !checkParts(step))) return false;
      } else if (!template.paragraphs?.length || template.paragraphs.length > 40 || template.paragraphs.some(paragraph => !checkParts(paragraph))) return false;
      if (referenced.length !== questions.length || new Set(referenced).size !== questions.length) return false;
      if (template.kind === "PARAGRAPH" && referenced.some((questionIndex, index) => questionIndex !== index)) return false;
    }
    return true;
  }

  async function remove(group: Group) {
    if (!window.confirm(`Xóa nhóm ${names[group.taskVariant]} và ${group.questions.length} câu hỏi? Các câu phía sau sẽ được đánh số lại.`)) return;
    setBusy(true);
    try { const response = await api(`/api/admin/passages/${passageId}/question-groups/${group.id}`, "DELETE"); if (!response.ok) throw new Error(); setNotice("Đã xóa nhóm và đánh số lại các câu còn lại."); await load(); }
    catch { setError("Không thể xóa nhóm câu hỏi."); }
    finally { setBusy(false); }
  }

  async function save(event: FormEvent) {
    event.preventDefault(); setError(""); setNotice("");
    if (!valid()) { setError("Kiểm tra lại hướng dẫn, bố cục, lựa chọn và đáp án trước khi lưu."); return; }
    setBusy(true);
    const payloadQuestions = multiple ? questions.map(question => ({ ...question, questionText: "", blankText: "", answer: "", alternatives: [], options: questions[0].options })) : questions;
    try {
      const response = await api(`/api/admin/passages/${passageId}/question-groups${editing ? `/${editing.id}` : ""}`, editing ? "PUT" : "POST", {
        taskVariant: variant, instructions, questions: payloadQuestions, wordLimitMode: textEntry ? wordLimit : null,
        candidates: sharedChoice ? candidates : null, candidateReuse: sharedChoice ? reuse : null,
        answerInputMode: variant === "SUMMARY_COMPLETION" ? inputMode : textEntry ? "TEXT_ENTRY" : sharedChoice ? "SHARED_CHOICE_POOL" : null,
        correctAnswerLabels: multiple ? correctLabels : null, presentationTemplate: structured ? template : null,
      });
      if (!response.ok) { const body = await response.json().catch(() => null) as { message?: string } | null; setError(body?.message || "Không thể lưu nhóm câu hỏi."); return; }
      reset(); setNotice("Đã lưu nhóm câu hỏi."); await load();
    } catch { setError("Không thể kết nối tới máy chủ."); }
    finally { setBusy(false); }
  }

  function renderParts(parts: Part[], target: { row?: number; cell?: number; step?: number }, update: (index: number, change: Partial<Part>) => void, remove: (index: number) => void) {
    return <div className={styles.partEditor}>{parts.map((part, index) => <div className={styles.partRow} key={index}>
      <span className={styles.partKind}>{part.questionIndex === null ? "Văn bản" : "Ô trả lời"}</span>
      {part.questionIndex === null ? <input aria-label="Nội dung tĩnh" value={part.text ?? ""} onChange={event => update(index, { text: event.target.value })} placeholder="Nhập nội dung hiển thị trong ô" /> : <select aria-label="Ô câu hỏi" value={part.questionIndex} onChange={event => update(index, { questionIndex: Number(event.target.value), text: null })}><option value={part.questionIndex}>Câu {firstNumber + part.questionIndex}</option>{questions.map((_, i) => <option key={i} value={i}>Câu {firstNumber + i}</option>)}</select>}
      <button type="button" className={styles.open} onClick={() => remove(index)}>Xóa</button>
    </div>)}
    <div className={styles.actions}><button type="button" className={styles.open} onClick={() => target.row !== undefined && target.cell !== undefined ? mutateCell(target.row, target.cell, current => [...current, emptyTextPart()]) : target.step !== undefined ? mutateStep(target.step, current => [...current, emptyTextPart()]) : undefined}>+ Văn bản</button><button type="button" className={styles.open} onClick={() => addQuestionBlank(target)}>+ Ô trả lời</button></div></div>;
  }

  const layoutEditor = structured && template ? <fieldset className={styles.question}><legend>{template.kind === "TABLE" ? "Bố cục bảng" : template.kind === "FLOW_CHART" ? "Bố cục flow-chart" : "Đoạn văn có ô điền từ"}</legend>
    {variant === "TABLE_COMPLETION" && template.kind === "TABLE" && template.columns && template.rows && <>
      <label>Tiêu đề bảng (không bắt buộc)<input value={template.caption ?? ""} onChange={event => setTemplate(current => current ? { ...current, caption: event.target.value } : current)} /></label>
      <section className={styles.tableEditorSection} aria-label="Tiêu đề cột"><div className={styles.tableEditorTitle}><div><h4>Tiêu đề cột</h4><p className={styles.help}>Đặt tên cho từng cột như trong đề bài.</p></div><button type="button" className={styles.secondary} onClick={() => setTemplate(current => current?.columns && current.rows ? { ...current, columns: [...current.columns, `Cột ${current.columns.length + 1}`], rows: current.rows.map(row => [...row, [emptyTextPart()]]) } : current)}>+ Thêm cột</button></div>
        <div className={styles.tableHeaders}>{template.columns.map((column, index) => <label className={styles.tableHeaderField} key={index}><span>Cột {index + 1}</span><input value={column} onChange={event => setTemplate(current => current?.columns ? { ...current, columns: current.columns.map((item, i) => i === index ? event.target.value : item) } : current)} aria-label={`Tiêu đề cột ${index + 1}`} />{template.columns!.length > 2 && <button type="button" className={`${styles.open} ${styles.dangerAction}`} onClick={() => setTemplate(current => current?.columns && current.rows ? { ...current, columns: current.columns.filter((_, i) => i !== index), rows: current.rows.map(row => row.filter((_, i) => i !== index)) } : current)}>Xóa cột</button>}</label>)}</div>
      </section>
      <section className={styles.tableEditorSection} aria-label="Nội dung hàng trong bảng"><div className={styles.tableEditorTitle}><div><h4>Nội dung từng hàng</h4><p className={styles.help}>Mỗi thẻ là một hàng. Bổ sung văn bản hoặc ô trả lời cho từng cột.</p></div><button type="button" className={styles.secondary} onClick={() => setTemplate(current => current?.rows && current.columns ? { ...current, rows: [...current.rows, current.columns.map(() => [emptyTextPart()])] } : current)}>+ Thêm hàng</button></div>
        {template.rows.map((row, rowIndex) => <article className={styles.tableRowCard} key={rowIndex}><div className={styles.tableRowTitle}><strong>Hàng {rowIndex + 1}</strong>{template.rows!.length > 1 && <button type="button" className={`${styles.open} ${styles.dangerAction}`} onClick={() => setTemplate(current => current?.rows ? { ...current, rows: current.rows.filter((_, i) => i !== rowIndex) } : current)}>Xóa hàng</button>}</div>
          <div className={styles.tableCells} style={{ gridTemplateColumns: `repeat(${template.columns!.length}, minmax(220px, 1fr))` }}>{row.map((cell, cellIndex) => <section className={styles.tableCellCard} key={cellIndex}><h5>{template.columns![cellIndex]}</h5>{renderParts(cell, { row: rowIndex, cell: cellIndex }, (partIndex, change) => updatePart({ row: rowIndex, cell: cellIndex, part: partIndex }, change), partIndex => mutateCell(rowIndex, cellIndex, current => current.filter((_, i) => i !== partIndex)))}</section>)}</div>
        </article>)}
      </section>
    </>}
    {variant === "FLOW_CHART_COMPLETION" && template.kind === "FLOW_CHART" && template.steps && <>
      <label>Tiêu đề sơ đồ (không bắt buộc)<input value={template.caption ?? ""} onChange={event => setTemplate(current => current ? { ...current, caption: event.target.value } : current)} /></label>
      {template.steps.map((step, index) => <fieldset className={styles.choiceEditor} key={index}><legend>Bước {index + 1}</legend>{renderParts(step, { step: index }, (partIndex, change) => updatePart({ step: index, part: partIndex }, change), partIndex => mutateStep(index, current => current.filter((_, i) => i !== partIndex)))}{template.steps!.length > 1 && <button type="button" className={`${styles.open} ${styles.dangerAction}`} onClick={() => setTemplate(current => current?.steps ? { ...current, steps: current.steps.filter((_, i) => i !== index) } : current)}>Xóa bước</button>}</fieldset>)}
      <button type="button" className={styles.secondary} onClick={() => setTemplate(current => current?.steps ? { ...current, steps: [...current.steps, [emptyTextPart()]] } : current)}>+ Thêm bước</button>
    </>}
    {template.kind === "PARAGRAPH" && <div className={styles.paragraphComposer}>
      <p className={styles.help}>Dán đoạn văn, chọn từ hoặc cụm từ cần bỏ trống rồi tạo ô. Mỗi ô sẽ thành một câu hỏi riêng; đoạn văn gốc của passage không thay đổi.</p>
      <label className={styles.paragraphText}><span>Nội dung đoạn văn và vị trí ô trống</span>
        <textarea ref={paragraphTextarea} value={paragraphDraft} rows={8} placeholder={`Dán đoạn văn vào đây. Ô trống sẽ xuất hiện dưới dạng ${blankToken(firstNumber)} trong phần soạn thảo.`} onChange={event => { updateParagraphDraft(event.target.value); setSelectedRange({ start: event.currentTarget.selectionStart, end: event.currentTarget.selectionEnd }); setError(""); }} onSelect={event => setSelectedRange({ start: event.currentTarget.selectionStart, end: event.currentTarget.selectionEnd })} />
      </label>
      <div className={styles.paragraphComposerActions}>
        <button type="button" className={styles.secondary} disabled={!paragraphDraft.slice(selectedRange.start, selectedRange.end).trim()} onMouseDown={event => event.preventDefault()} onClick={createParagraphBlank}>Tạo ô trống từ phần đã chọn</button>
        <span className={styles.help}>Chọn cụm từ trong ô văn bản trước khi bấm.</span>
      </div>
    </div>}
  </fieldset> : null;

  const layoutPreview = structured && template ? <article className={styles.savedGroup} aria-label="Xem trước bố cục câu hỏi">
    <h4>Xem trước cho người học</h4>
    {template.kind === "TABLE" && template.columns && template.rows && <div className={styles.tablePreview}><table><caption>{template.caption}</caption><thead><tr>{template.columns.map((column, index) => <th key={index}>{column || `Cột ${index + 1}`}</th>)}</tr></thead><tbody>{template.rows.map((row, rowIndex) => <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex}>{cell.map((part, partIndex) => part.questionIndex === null ? <span key={partIndex}>{part.text || "[văn bản]"}</span> : <strong key={partIndex}>[Câu {firstNumber + part.questionIndex}]</strong>)}</td>)}</tr>)}</tbody></table></div>}
    {template.kind === "FLOW_CHART" && template.steps && <ol>{template.steps.map((step, index) => <li key={index}>{step.map((part, partIndex) => part.questionIndex === null ? <span key={partIndex}>{part.text || "[văn bản]"}</span> : <strong key={partIndex}>[Câu {firstNumber + part.questionIndex}]</strong>)}</li>)}</ol>}
    {template.kind === "PARAGRAPH" && template.paragraphs?.map((paragraph, index) => <p className={styles.paragraphPreview} key={index}>{paragraph.map((part, partIndex) => part.questionIndex === null ? <span key={partIndex}>{part.text}</span> : <strong className={styles.paragraphPreviewBlank} key={partIndex}>Câu {firstNumber + part.questionIndex}</strong>)}</p>)}
  </article> : null;

  const form = <form className={styles.questionForm} onSubmit={save} noValidate>
    <h3>{editing ? "Chỉnh sửa nhóm" : "Thêm nhóm câu hỏi"}</h3><p className={styles.help}>Số câu được đánh tự động theo thứ tự trong passage.</p>
    <label>Dạng câu hỏi<select value={variant} disabled={!!editing} onChange={event => changeVariant(event.target.value as Variant)}>{variants.map(key => <option key={key} value={key}>{names[key]}</option>)}</select></label>
    {variant === "SUMMARY_COMPLETION" && <label>Cách trả lời<select value={inputMode} disabled={!!editing} onChange={event => { setInputMode(event.target.value as InputMode); setCandidates([]); setQuestions([emptyQuestion()]); setTemplate(null); setParagraphDraft(""); setSelectedRange({ start: 0, end: 0 }); }}><option value="TEXT_ENTRY">Tự nhập đáp án</option><option value="SHARED_CHOICE_POOL">Chọn từ danh sách chung</option></select></label>}
    {supportsParagraphLayout(variant, inputMode) && <label>Cách trình bày<select value={template?.kind === "PARAGRAPH" ? "PARAGRAPH" : "INDIVIDUAL"} onChange={event => selectParagraphLayout(event.target.value === "PARAGRAPH")}><option value="INDIVIDUAL">Mỗi câu hỏi có prompt riêng</option><option value="PARAGRAPH">Một đoạn văn có nhiều ô điền từ</option></select></label>}
    {textEntry && <><label>Giới hạn đáp án<select value={wordLimit} onChange={event => setWordLimit(event.target.value as WordLimit)}>{Object.entries(limits).map(([key, value]) => <option key={key} value={key}>{value}</option>)}</select></label><p className={styles.help}>Hỗ trợ từ, từ có gạch nối và số nguyên.</p></>}
    {(!textEntry || tableFlow || variant === "SHORT_ANSWER") && <label>Hướng dẫn chung<textarea value={instructions} onChange={event => setInstructions(event.target.value)} rows={3} /></label>}
    {sharedChoice && <fieldset className={styles.choiceEditor}><legend>Danh sách lựa chọn dùng chung</legend>{candidates.map((candidate, index) => <div className={styles.choiceRow} key={candidate.id ?? index}><input value={candidate.label} disabled={!!candidate.id} aria-label="Mã lựa chọn" onChange={event => setCandidates(current => current.map((item, i) => i === index ? { ...item, label: event.target.value } : item))} /><input value={candidate.text} aria-label="Nội dung lựa chọn" onChange={event => setCandidates(current => current.map((item, i) => i === index ? { ...item, text: event.target.value } : item))} /><button type="button" className={styles.open} onClick={() => setCandidates(current => current.filter((_, i) => i !== index))}>Xóa</button></div>)}<button type="button" className={styles.open} onClick={() => setCandidates(current => [...current, { label: "", text: "" }])}>+ Thêm lựa chọn</button>{["MATCHING_INFORMATION", "MATCHING_FEATURES"].includes(variant) && <label><input type="checkbox" checked={reuse} onChange={event => setReuse(event.target.checked)} /> Cho phép sử dụng lại</label>}</fieldset>}
    {multiple && <fieldset className={styles.choiceEditor}><legend>Danh sách lựa chọn và tập đáp án đúng</legend>{questions[0].options.map((option, index) => <div className={styles.choiceRow} key={index}><strong>{optionLabel(index)}</strong><input value={option} aria-label={`Lựa chọn ${optionLabel(index)}`} onChange={event => setQuestions(current => current.map(question => ({ ...question, options: question.options.map((item, i) => i === index ? event.target.value : item) })))} />{questions[0].options.length > 2 && <button type="button" className={styles.open} onClick={() => { const removed = optionLabel(index); setQuestions(current => current.map(question => ({ ...question, options: question.options.filter((_, i) => i !== index) }))); setCorrectLabels(current => current.filter(item => item !== removed).map(item => item > removed ? String.fromCharCode(item.charCodeAt(0) - 1) : item)); }}>Xóa</button>}</div>)}<button type="button" className={styles.open} onClick={() => setQuestions(current => current.map(question => ({ ...question, options: [...question.options, ""] })))}>+ Thêm lựa chọn</button><p className={styles.help}>Chọn đúng một đáp án cho mỗi ô câu hỏi; thí sinh có thể điền các đáp án theo thứ tự bất kỳ.</p></fieldset>}
    {layoutEditor}{layoutPreview}
    {template?.kind === "PARAGRAPH" && questions.length > 0 && <h4 className={styles.answerSectionTitle}>Đáp án và giải thích cho từng ô trống</h4>}
    {questions.map((question, index) => <fieldset className={styles.question} key={question.id ?? index}>
      <legend>Câu {firstNumber + index}</legend>
      {variant === "SHORT_ANSWER" && <label>Nội dung câu hỏi<textarea value={question.questionText} onChange={event => updateQuestion(index, { questionText: event.target.value })} rows={2} /></label>}
      {textEntry && !structured && variant !== "SHORT_ANSWER" && <><label>Nội dung trước ô trống<textarea value={question.questionText} onChange={event => updateQuestion(index, { questionText: event.target.value })} rows={2} /></label><label>Nội dung sau ô trống<textarea value={question.blankText} onChange={event => updateQuestion(index, { blankText: event.target.value })} rows={2} /></label><p className={styles.help}>Xem trước: {question.questionText} [ô đáp án] {question.blankText}</p></>}
      {textEntry && <fieldset className={styles.choiceEditor}><legend>Đáp án và giải thích</legend><label>{variant === "SHORT_ANSWER" ? "Đáp án chính" : "Đáp án chính của ô trống"}<input value={question.answer} onChange={event => updateQuestion(index, { answer: event.target.value })} /></label>{wordError(question.answer, wordLimit) && <p className={styles.fieldError}>{wordError(question.answer, wordLimit)}</p>}{question.alternatives.map((alternative, altIndex) => <div className={styles.choiceRow} key={altIndex}><input aria-label="Đáp án thay thế" value={alternative} onChange={event => updateQuestion(index, { alternatives: question.alternatives.map((item, i) => i === altIndex ? event.target.value : item) })} /><button type="button" className={styles.open} onClick={() => updateQuestion(index, { alternatives: question.alternatives.filter((_, i) => i !== altIndex) })}>Xóa</button></div>)}<button type="button" className={styles.open} onClick={() => updateQuestion(index, { alternatives: [...question.alternatives, ""] })}>+ Thêm đáp án thay thế</button></fieldset>}
      {sharedChoice && <label>Đáp án đúng<select value={question.answer} onChange={event => updateQuestion(index, { answer: event.target.value })}><option value="">Chọn đáp án</option>{candidates.map(candidate => <option key={candidate.id ?? candidate.label} value={candidate.label}>{candidate.label} — {candidate.text}</option>)}</select></label>}
      {variant === "MULTIPLE_CHOICE_SINGLE" && <fieldset className={styles.choiceEditor}><legend>Lựa chọn và đáp án đúng</legend>{question.options.map((option, optionIndex) => <div className={styles.optionRow} key={optionIndex}><label className={styles.optionMarker}><input type="radio" name={`correct-${index}`} checked={question.answer === optionLabel(optionIndex)} onChange={() => updateQuestion(index, { answer: optionLabel(optionIndex) })} /><span>{optionLabel(optionIndex)}</span></label><input aria-label={`Nội dung lựa chọn ${optionLabel(optionIndex)}`} value={option} onChange={event => updateQuestion(index, { options: question.options.map((item, i) => i === optionIndex ? event.target.value : item) })} />{question.options.length > 2 && <button type="button" className={styles.open} onClick={() => updateQuestion(index, { options: question.options.filter((_, i) => i !== optionIndex), answer: question.answer === optionLabel(optionIndex) ? "" : question.answer && question.answer > optionLabel(optionIndex) ? String.fromCharCode(question.answer.charCodeAt(0) - 1) : question.answer })}>Xóa</button>}</div>)}<button type="button" className={styles.open} onClick={() => updateQuestion(index, { options: [...question.options, ""] })}>+ Thêm lựa chọn</button></fieldset>}
      {multiple && <fieldset className={styles.answerChoices}><legend>Đáp án đúng cho ô này</legend>{questions[0].options.map((_, optionIndex) => <label key={optionIndex}><input type="radio" name={`multiple-correct-${index}`} checked={correctLabels[index] === optionLabel(optionIndex)} onChange={() => setCorrectLabels(current => questions.map((__, i) => i === index ? optionLabel(optionIndex) : current[i] ?? ""))} />{optionLabel(optionIndex)}</label>)}</fieldset>}
      {fixedAnswers[variant] && <fieldset className={styles.answerChoices}><legend>Đáp án đúng</legend>{fixedAnswers[variant]!.map(answer => <label key={answer}><input type="radio" checked={question.answer === answer} onChange={() => updateQuestion(index, { answer })} />{answer}</label>)}</fieldset>}
      {!textEntry && !multiple && !structured && <label>Nội dung câu hỏi<textarea value={question.questionText} onChange={event => updateQuestion(index, { questionText: event.target.value })} rows={2} /></label>}
      <label>Giải thích (không bắt buộc)<textarea value={question.explanation} onChange={event => updateQuestion(index, { explanation: event.target.value })} rows={2} /></label>
      {questions.length > 1 || template?.kind === "PARAGRAPH" ? <button type="button" className={`${styles.open} ${styles.dangerAction}`} onClick={() => removeQuestion(index)}>Xóa câu hỏi này</button> : <p className={styles.help}>Muốn xóa câu cuối cùng, hãy xóa cả nhóm.</p>}
    </fieldset>)}
    {!structured && <div className={styles.actions}><button type="button" className={styles.secondary} onClick={() => setQuestions(current => [...current, { ...emptyQuestion(), options: multiple ? [...(current[0]?.options ?? ["", ""])] : ["", ""] }])}>+ Thêm câu hỏi / ô đáp án</button></div>}
    {error && <p className={styles.error} role="alert">{error}</p>}
    <div className={styles.actions}><button className={styles.primary} disabled={busy}>{busy ? "Đang lưu..." : editing ? "Lưu thay đổi" : "Lưu nhóm câu hỏi"}</button><button type="button" className={styles.secondary} onClick={() => { if (!dirty || window.confirm("Nhóm câu hỏi chưa lưu sẽ bị mất. Bạn có muốn hủy?")) reset(); }}>Hủy</button></div>
  </form>;

  return <section className={styles.questions}><div className={styles.sectionHead}><h2>Nhóm câu hỏi</h2>{canCreate && !adding && <button type="button" className={styles.secondary} onClick={() => { setAdding(true); setError(""); setNotice(""); }}>+ Thêm nhóm</button>}</div>
    {notice && <p className={styles.notice}>{notice}</p>}{!adding && error && <p className={styles.error}>{error}</p>}
    {loading ? <p className={styles.empty}>Đang tải câu hỏi...</p> : groups.map(group => editing?.id === group.id && adding ? <div key={group.id}>{form}</div> : <article className={styles.savedGroup} key={group.id}><div className={styles.groupHeading}><h3>{names[group.taskVariant]}</h3>{canCreate && !adding && <div className={styles.actions}><button type="button" className={styles.open} onClick={() => edit(group)}>Chỉnh sửa</button><button type="button" disabled={busy} className={`${styles.open} ${styles.dangerAction}`} onClick={() => void remove(group)}>Xóa nhóm</button></div>}</div><p className={styles.instructions}>{group.instructions}</p>{group.candidates.length > 0 && <ul>{group.candidates.map(candidate => <li key={candidate.id}>{candidate.label}. {candidate.text}</li>)}</ul>}{group.questions.map(question => <p key={question.id}><strong>Câu {question.questionNumber}.</strong> {question.questionText || (isStructured(group.taskVariant) || group.presentationTemplate?.kind === "PARAGRAPH" ? "[nằm trong bố cục]" : "[ô đáp án]")} · Đáp án: <strong>{question.answer || (group.taskVariant === "MULTIPLE_CHOICE_MULTIPLE" ? (group.correctAnswerLabels ?? []).join(", ") : "—")}</strong></p>)}</article>)}
    {adding && !editing && form}
  </section>;
}
