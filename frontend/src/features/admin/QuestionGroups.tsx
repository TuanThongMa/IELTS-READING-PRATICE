import { useEffect, useState, type FormEvent } from "react";
import { api } from "../../api";
import styles from "./AdminPage.module.css";

type Question = { id?: number; questionText: string; answer: string; explanation: string; options: string[]; blankText: string; alternatives: string[] };
type SavedQuestion = Omit<Question, "explanation"> & { id: number; questionNumber: number; explanation: string | null };
type Variant = "TRUE_FALSE_NOT_GIVEN" | "YES_NO_NOT_GIVEN" | "MULTIPLE_CHOICE_SINGLE" | "SENTENCE_COMPLETION" | "MATCHING_INFORMATION" | "MATCHING_HEADINGS" | "MATCHING_FEATURES" | "MATCHING_SENTENCE_ENDINGS";
type WordLimit = "ONE_WORD" | "TWO_WORDS" | "TWO_WORDS_AND_ONE_NUMBER";
type Candidate = { id?: number; label: string; text: string };
type Group = { id: number; taskVariant: Variant; instructions: string; questions: SavedQuestion[]; wordLimitMode: WordLimit | null; candidates: Candidate[]; candidateReuse: boolean };
const isMatching = (variant: Variant) => variant.startsWith("MATCHING_");
const emptyQuestion = (): Question => ({ questionText: "", answer: "", explanation: "", options: ["", ""], blankText: "", alternatives: [] });
const toEditable = (q: SavedQuestion): Question => ({ id: q.id, questionText: q.questionText, answer: q.answer, explanation: q.explanation ?? "", options: q.options, blankText: q.blankText ?? "", alternatives: q.alternatives });
const optionLabel = (index: number) => String.fromCharCode(65 + index);
const variants: Record<Variant, { label: string; answers: string[] }> = {
  TRUE_FALSE_NOT_GIVEN: { label: "True / False / Not Given", answers: ["TRUE", "FALSE", "NOT GIVEN"] },
  YES_NO_NOT_GIVEN: { label: "Yes / No / Not Given", answers: ["YES", "NO", "NOT GIVEN"] },
  MULTIPLE_CHOICE_SINGLE: { label: "Multiple Choice – một đáp án", answers: [] },
  SENTENCE_COMPLETION: { label: "Sentence Completion – điền từ vào câu", answers: [] },
  MATCHING_INFORMATION: { label: "Matching Information", answers: [] },
  MATCHING_HEADINGS: { label: "Matching Headings", answers: [] },
  MATCHING_FEATURES: { label: "Matching Features", answers: [] },
  MATCHING_SENTENCE_ENDINGS: { label: "Matching Sentence Endings", answers: [] },
};
const wordLimits: Record<WordLimit, string> = { ONE_WORD: "ONE WORD ONLY", TWO_WORDS: "NO MORE THAN TWO WORDS", TWO_WORDS_AND_ONE_NUMBER: "NO MORE THAN TWO WORDS AND/OR A NUMBER" };
function blankLimitError(value: string, mode: WordLimit): string {
  const normalized = value.normalize("NFC").trim();
  if (!normalized) return "";
  let words = 0, numbers = 0;
  for (const token of normalized.split(/\s+/u)) {
    if (/^[0-9]+$/.test(token)) numbers++;
    else if (/^[\p{L}\p{M}]+(?:-[\p{L}\p{M}]+)*$/u.test(token)) words++;
    else return "Định dạng này chưa được hỗ trợ. Hãy dùng từ, từ có gạch nối hoặc số nguyên.";
  }
  if (words > (mode === "ONE_WORD" ? 1 : 2) || numbers > (mode === "TWO_WORDS_AND_ONE_NUMBER" ? 1 : 0))
    return `Đáp án vượt giới hạn ${wordLimits[mode]}.`;
  return "";
}

export function QuestionGroups({ passageId, canCreate, onDirtyChange }: { passageId: number; canCreate: boolean; onDirtyChange: (dirty: boolean) => void }) {
  const [groups, setGroups] = useState<Group[]>([]);
  const [loading, setLoading] = useState(true);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<Group | null>(null);
  const [variant, setVariant] = useState<Variant>("TRUE_FALSE_NOT_GIVEN");
  const [wordLimitMode, setWordLimitMode] = useState<WordLimit>("ONE_WORD");
  const [busy, setBusy] = useState(false);
  const [instructions, setInstructions] = useState("");
  const [questions, setQuestions] = useState<Question[]>([emptyQuestion()]);
  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [candidateReuse, setCandidateReuse] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const originalQuestions = editing?.questions.map(toEditable);
  const dirty = adding && (editing ? (variant === "SENTENCE_COMPLETION" ? wordLimitMode !== editing.wordLimitMode : instructions !== editing.instructions) || JSON.stringify(questions) !== JSON.stringify(originalQuestions) || JSON.stringify(candidates) !== JSON.stringify(editing.candidates) || candidateReuse !== editing.candidateReuse : variant !== "TRUE_FALSE_NOT_GIVEN" || instructions !== "" || questions.some(q => q.questionText !== "" || q.answer !== "" || q.explanation !== "" || q.options.some(option => option !== "")));
  const answers = variants[variant].answers;
  const groupIndex = editing ? groups.findIndex(g => g.id === editing.id) : groups.length;
  const previousCount = groups.slice(0, groupIndex < 0 ? 0 : groupIndex).reduce((total, group) => total + group.questions.length, 0);
  useEffect(() => { onDirtyChange(dirty); return () => onDirtyChange(false); }, [dirty, onDirtyChange]);

  async function load() {
    setLoading(true);
    try {
      const response = await api(`/api/admin/passages/${passageId}/question-groups`);
      if (!response.ok) throw new Error();
      setGroups(await response.json() as Group[]);
    } catch { setError("Không thể tải nhóm câu hỏi. Vui lòng mở lại passage."); }
    finally { setLoading(false); }
  }
  useEffect(() => { void load(); }, [passageId]);

  function updateQuestion(index: number, change: Partial<Question>) {
    setError("");
    setQuestions(current => current.map((question, i) => i === index ? { ...question, ...change } : question));
  }
  function cancel() {
    if (dirty && !window.confirm("Nhóm câu hỏi chưa lưu sẽ bị mất. Bạn có muốn hủy?")) return;
    setAdding(false); setEditing(null); setVariant("TRUE_FALSE_NOT_GIVEN"); setInstructions(""); setQuestions([emptyQuestion()]); setCandidates([]); setCandidateReuse(false); setError("");
  }
  function edit(group: Group) {
    setEditing(group); setVariant(group.taskVariant); setInstructions(group.instructions); setWordLimitMode(group.wordLimitMode ?? "ONE_WORD");
    setQuestions(group.questions.map(toEditable));
    setCandidates(group.candidates); setCandidateReuse(group.candidateReuse);
    setAdding(true); setNotice(""); setError("");
  }
  async function deleteGroup(group: Group) {
    if (!window.confirm(`Xóa nhóm ${variants[group.taskVariant].label} và ${group.questions.length} câu hỏi? Không thể hoàn tác. Các câu phía sau sẽ được đánh số lại.`)) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await api(`/api/admin/passages/${passageId}/question-groups/${group.id}`, "DELETE");
      if (!response.ok) {
        setError(response.status === 409 ? "Chỉ có thể xóa nhóm câu hỏi của passage nháp." : response.status === 404 ? "Passage hoặc nhóm câu hỏi không còn tồn tại." : "Không thể xóa nhóm câu hỏi. Vui lòng thử lại.");
        return;
      }
      setNotice("Đã xóa nhóm câu hỏi và đánh số lại các câu còn lại."); await load();
    } catch { setError("Không thể kết nối tới máy chủ. Vui lòng kiểm tra danh sách trước khi thử lại."); }
    finally { setBusy(false); }
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(""); setNotice("");
    if ((variant !== "SENTENCE_COMPLETION" && !instructions.trim()) || questions.some(q => variant === "SENTENCE_COMPLETION" ? (!q.questionText.trim() && !q.blankText.trim()) || !q.answer.trim() || q.alternatives.some(a => !a.trim()) : !q.questionText.trim() || (variant === "MULTIPLE_CHOICE_SINGLE" ? q.options.length < 2 || q.options.some(option => !option.trim()) || !q.options.some((_, i) => optionLabel(i) === q.answer) : isMatching(variant) ? !candidates.some(c => c.label.trim() === q.answer) : !answers.includes(q.answer)))) {
      setError("Vui lòng nhập hướng dẫn, nội dung, các lựa chọn và đáp án đúng cho từng câu."); return;
    }
    if (isMatching(variant)) {
      const labels = candidates.map(c => c.label.trim());
      if (!candidates.length || candidates.some(c => !c.label.trim() || !c.text.trim()) || new Set(labels).size !== labels.length || (!candidateReuse && new Set(questions.map(q => q.answer)).size !== questions.length)) {
        setError("Kiểm tra mã và nội dung các lựa chọn; mã không được trùng. Đáp án chỉ được lặp lại khi bật cho phép sử dụng lại."); return;
      }
      if (editing && candidates.some(c => c.id && editing.candidates.some(old => old.id === c.id && old.text !== c.text && editing.questions.some(q => q.answer === old.label))) && !window.confirm("Bạn đã sửa nội dung một lựa chọn từng là đáp án đúng. Thay đổi này ảnh hưởng các câu liên quan. Tiếp tục lưu?")) return;
    }
    if (variant === "SENTENCE_COMPLETION" && questions.some(q => [q.answer, ...q.alternatives].some(answer => blankLimitError(answer, wordLimitMode)))) {
      setError("Một số đáp án chưa phù hợp với giới hạn đã chọn. Hãy kiểm tra thông báo dưới các ô đáp án."); return;
    }
    setBusy(true);
    try {
      const response = await api(`/api/admin/passages/${passageId}/question-groups${editing ? `/${editing.id}` : ""}`, editing ? "PUT" : "POST", {
        taskVariant: variant, instructions, questions, wordLimitMode: variant === "SENTENCE_COMPLETION" ? wordLimitMode : null, candidates: isMatching(variant) ? candidates : null, candidateReuse: isMatching(variant) ? candidateReuse : null,
      });
      if (!response.ok) {
        let validationMessage = "";
        if (response.status === 400) {
          const data = await response.json().catch(() => null) as { message?: unknown } | null;
          if (typeof data?.message === "string") validationMessage = data.message;
        }
        setError(response.status === 409 ? "Passage không còn là bản nháp hoặc tổng số câu vượt quá 40. Vui lòng kiểm tra lại." : response.status === 400 ? validationMessage || "Thông tin nhóm câu hỏi chưa hợp lệ." : response.status === 404 ? "Passage hoặc nhóm câu hỏi không còn tồn tại." : "Không thể lưu nhóm câu hỏi. Vui lòng thử lại.");
        return;
      }
      setAdding(false); setEditing(null); setVariant("TRUE_FALSE_NOT_GIVEN"); setInstructions(""); setQuestions([emptyQuestion()]); setCandidates([]); setCandidateReuse(false);
      setNotice("Đã lưu nhóm câu hỏi."); await load();
    } catch { setError("Không thể kết nối tới máy chủ. Vui lòng kiểm tra danh sách trước khi thử lưu lại."); }
    finally { setBusy(false); }
  }

  const form = <form className={styles.questionForm} onSubmit={submit} noValidate><h3>{editing ? "Chỉnh sửa nhóm" : "Thêm nhóm câu hỏi"}</h3><p className={styles.help}>Số câu được đánh tự động theo thứ tự trong passage. Nếu hướng dẫn có nhắc khoảng số câu, hãy tự kiểm tra sau khi thêm hoặc bỏ câu.{editing && " Xóa câu chỉ được lưu khi bạn chọn Lưu thay đổi."}</p>
      <label>Dạng câu hỏi<select value={variant} disabled={!!editing} onChange={e => { setVariant(e.target.value as Variant); setCandidates([]); setCandidateReuse(false); setQuestions(current => current.map(q => ({ ...q, answer: "", options: q.options.length >= 2 ? q.options : ["", ""] }))); }}>{Object.entries(variants).map(([value, item]) => <option key={value} value={value}>{item.label}</option>)}</select></label>
      {!editing && <p className={styles.help}>Khi đổi dạng, các đáp án đã chọn sẽ được bỏ chọn.</p>}
      {variant === "SENTENCE_COMPLETION" ? <><label>Giới hạn đáp án<select value={wordLimitMode} onChange={e => setWordLimitMode(e.target.value as WordLimit)}>{Object.entries(wordLimits).map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></label><p className={styles.help}>Hiện hỗ trợ từ, từ có dấu gạch nối và số nguyên. Dạng số thập phân, ngày tháng và ký hiệu tiền tệ chưa được hỗ trợ.</p></> : <label>Hướng dẫn chung<textarea autoFocus={!!editing} value={instructions} onChange={e => setInstructions(e.target.value)} maxLength={10000} rows={3} /></label>}
      {isMatching(variant) && <fieldset className={styles.choiceEditor}><legend>Danh sách lựa chọn dùng chung</legend><p className={styles.help}>Nhập mã (ví dụ A, B hoặc i, ii) và nội dung. Mã đã lưu không thể sửa để tránh đổi nhầm đáp án.</p>{candidates.map((candidate, i) => <div className={styles.choiceRow} key={candidate.id ?? `new-${i}`}><input aria-label={`Mã lựa chọn ${i + 1}`} value={candidate.label} disabled={!!candidate.id} maxLength={30} onChange={e => setCandidates(current => current.map((c, j) => i === j ? { ...c, label: e.target.value } : c))} /><input aria-label={`Nội dung lựa chọn ${i + 1}`} value={candidate.text} maxLength={10000} onChange={e => setCandidates(current => current.map((c, j) => i === j ? { ...c, text: e.target.value } : c))} /><button type="button" className={styles.open} onClick={() => { if (questions.some(q => q.answer === candidate.label && q.answer)) { setError("Lựa chọn đang là đáp án đúng. Hãy đổi đáp án các câu liên quan trước khi xóa."); return; } setCandidates(current => current.filter((_, j) => i !== j)); }}>Xóa</button></div>)}<button type="button" className={styles.open} disabled={candidates.length >= 50} onClick={() => setCandidates(current => [...current, { label: "", text: "" }])}>+ Thêm lựa chọn</button>{(variant === "MATCHING_INFORMATION" || variant === "MATCHING_FEATURES") && <label><input type="checkbox" checked={candidateReuse} onChange={e => setCandidateReuse(e.target.checked)} /> Cho phép sử dụng một lựa chọn nhiều lần</label>}<p className={styles.help}>Nếu không cho phép sử dụng lại, mỗi lựa chọn chỉ là đáp án đúng của một câu.</p></fieldset>}
      {questions.map((q, index) => <fieldset className={styles.question} key={q.id ?? `new-${index}`}><legend>Câu {previousCount + index + 1}</legend>
        <label>{variant === "SENTENCE_COMPLETION" ? "Nội dung trước ô trống" : "Nội dung câu hỏi"}<textarea value={q.questionText} onChange={e => updateQuestion(index, { questionText: e.target.value })} maxLength={10000} rows={2} /></label>
        {variant === "SENTENCE_COMPLETION" && <><label>Nội dung sau ô trống<textarea value={q.blankText} onChange={e => updateQuestion(index, { blankText: e.target.value })} maxLength={10000} rows={2} /></label><p className={styles.help}>Xem trước: {q.questionText} [ô đáp án] {q.blankText}</p></>}
        {isMatching(variant) ? <label>Đáp án đúng<select value={q.answer} onChange={e => updateQuestion(index, { answer: e.target.value })}><option value="">Chọn đáp án</option>{candidates.filter(c => c.label.trim()).map((c, i) => <option key={c.id ?? i} value={c.label.trim()}>{c.label} — {c.text}</option>)}</select></label> : variant === "SENTENCE_COMPLETION" ? <fieldset className={styles.choiceEditor}><legend>Đáp án</legend><label>Đáp án chính<input value={q.answer} aria-invalid={!!blankLimitError(q.answer, wordLimitMode)} aria-describedby={blankLimitError(q.answer, wordLimitMode) ? `answer-error-${index}` : undefined} maxLength={1000} onChange={e => updateQuestion(index, { answer: e.target.value })} /></label>{blankLimitError(q.answer, wordLimitMode) && <p id={`answer-error-${index}`} className={styles.fieldError}>{blankLimitError(q.answer, wordLimitMode)}</p>}{q.alternatives.map((alternative, i) => <div className={styles.choiceRow} key={i}><input aria-label={`Đáp án thay thế ${i + 1}`} aria-invalid={!!blankLimitError(alternative, wordLimitMode)} aria-describedby={blankLimitError(alternative, wordLimitMode) ? `alternative-error-${index}-${i}` : undefined} value={alternative} maxLength={1000} onChange={e => updateQuestion(index, { alternatives: q.alternatives.map((a, j) => i === j ? e.target.value : a) })} /><button type="button" className={styles.open} onClick={() => updateQuestion(index, { alternatives: q.alternatives.filter((_, j) => i !== j) })}>Xóa</button>{blankLimitError(alternative, wordLimitMode) && <span id={`alternative-error-${index}-${i}`} className={styles.fieldError}>{blankLimitError(alternative, wordLimitMode)}</span>}</div>)}<button type="button" className={styles.open} disabled={q.alternatives.length >= 20} onClick={() => updateQuestion(index, { alternatives: [...q.alternatives, ""] })}>+ Thêm đáp án thay thế</button></fieldset> : variant === "MULTIPLE_CHOICE_SINGLE" ? <fieldset className={styles.choiceEditor}><legend>Các lựa chọn và đáp án đúng</legend><p className={styles.help}>Nhập ít nhất hai lựa chọn và chọn một đáp án đúng.</p>{q.options.map((option, optionIndex) => <div className={styles.choiceRow} key={optionIndex}><label className={styles.choiceSelect}><input type="radio" name={`answer-${index}`} checked={q.answer === optionLabel(optionIndex)} onChange={() => updateQuestion(index, { answer: optionLabel(optionIndex) })} aria-label={`Chọn ${optionLabel(optionIndex)} là đáp án đúng`} />{optionLabel(optionIndex)}</label><input aria-label={`Nội dung lựa chọn ${optionLabel(optionIndex)}`} value={option} maxLength={10000} onChange={e => updateQuestion(index, { options: q.options.map((item, i) => i === optionIndex ? e.target.value : item) })} />{q.options.length > 2 && <button type="button" className={styles.open} onClick={() => { const selected = q.answer ? q.answer.charCodeAt(0) - 65 : -1; updateQuestion(index, { options: q.options.filter((_, i) => i !== optionIndex), answer: selected === optionIndex ? "" : selected > optionIndex ? optionLabel(selected - 1) : q.answer }); }}>Xóa</button>}</div>)}<button type="button" className={styles.open} disabled={q.options.length >= 26} onClick={() => updateQuestion(index, { options: [...q.options, ""] })}>+ Thêm lựa chọn</button></fieldset> : <fieldset className={styles.answerChoices}><legend>Đáp án đúng</legend>{answers.map(answer => <label key={answer}><input type="radio" name={`answer-${index}`} value={answer} checked={q.answer === answer} onChange={() => updateQuestion(index, { answer })} />{answer}</label>)}</fieldset>}
        <label>Giải thích (không bắt buộc)<textarea value={q.explanation} onChange={e => updateQuestion(index, { explanation: e.target.value })} maxLength={10000} rows={2} /></label>
        {questions.length > 1 ? <button type="button" className={`${styles.open} ${styles.dangerAction}`} disabled={busy} onClick={() => setQuestions(current => current.filter((_, i) => i !== index))}>Xóa câu hỏi này</button> : <p className={styles.help}>Nhóm cần ít nhất một câu hỏi. Để xóa câu cuối cùng, hãy xóa cả nhóm.</p>}
      </fieldset>)}
      <div className={styles.actions}><button type="button" className={styles.secondary} disabled={busy || questions.length >= 40} onClick={() => setQuestions(current => [...current, emptyQuestion()])}>+ Thêm câu hỏi</button></div>
      {error && <p className={styles.error} role="alert">{error}</p>}
      <div className={styles.actions}><button className={styles.primary} disabled={busy}>{busy ? "Đang lưu..." : editing ? "Lưu thay đổi" : "Lưu nhóm câu hỏi"}</button><button type="button" className={styles.secondary} disabled={busy} onClick={cancel}>Hủy</button></div>
    </form>;

  return <section className={styles.questions} aria-labelledby="questions-title">
    <div className={styles.sectionHead}><h2 id="questions-title">Nhóm câu hỏi</h2>{canCreate && !adding && <button type="button" className={styles.secondary} onClick={() => { setAdding(true); setError(""); setNotice(""); }}>+ Thêm nhóm</button>}</div>
    {!adding && error && <p className={styles.error} role="alert">{error}</p>}{notice && <p className={styles.notice} role="status">{notice}</p>}
    {loading ? <p className={styles.empty}>Đang tải câu hỏi...</p> : groups.length === 0 ? <p className={styles.empty}>Chưa có nhóm câu hỏi nào.</p> : groups.map(group => editing?.id === group.id && adding ? <div key={group.id}>{form}</div> : <div className={styles.savedGroup} key={group.id}><div className={styles.groupHeading}><h3>{variants[group.taskVariant].label}</h3>{canCreate && !adding && <div className={styles.actions}><button type="button" className={styles.open} disabled={busy} onClick={() => edit(group)}>Chỉnh sửa</button><button type="button" className={`${styles.open} ${styles.dangerAction}`} disabled={busy} onClick={() => void deleteGroup(group)}>Xóa nhóm</button></div>}</div><p className={styles.instructions}>{group.instructions}</p>{isMatching(group.taskVariant) && <><p className={styles.help}>Lựa chọn dùng chung{group.candidateReuse ? " (được sử dụng lại)" : " (không sử dụng lại)"}:</p><ul>{group.candidates.map(c => <li key={c.id}>{c.label}. {c.text}</li>)}</ul></>}<ol className={styles.savedQuestions}>{group.questions.map(q => <li key={q.id}><strong>Câu {q.questionNumber}.</strong> {q.questionText}{group.taskVariant === "SENTENCE_COMPLETION" && <> [ô đáp án] {q.blankText}</>}{group.taskVariant === "MULTIPLE_CHOICE_SINGLE" && <ul>{q.options.map((option, i) => <li key={i}>{optionLabel(i)}. {option}</li>)}</ul>}<div>Đáp án: <strong>{q.answer}</strong></div>{q.alternatives.length > 0 && <div>Đáp án thay thế: {q.alternatives.join(", ")}</div>}{q.explanation && <div>Giải thích: {q.explanation}</div>}</li>)}</ol></div>)}
    {adding && !editing && form}
  </section>;
}
