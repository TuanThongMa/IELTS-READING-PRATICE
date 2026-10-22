import { useEffect, useState, type FormEvent } from "react";
import { api } from "../../api";
import styles from "./AdminPage.module.css";

type Question = { id?: number; questionText: string; answer: string; explanation: string };
type SavedQuestion = Omit<Question, "explanation"> & { id: number; questionNumber: number; explanation: string | null };
type Group = { id: number; instructions: string; questions: SavedQuestion[] };
const emptyQuestion = (): Question => ({ questionText: "", answer: "", explanation: "" });
const toEditable = (q: SavedQuestion): Question => ({ id: q.id, questionText: q.questionText, answer: q.answer, explanation: q.explanation ?? "" });
const answers = ["TRUE", "FALSE", "NOT GIVEN"];

export function QuestionGroups({ passageId, canCreate, onDirtyChange }: { passageId: number; canCreate: boolean; onDirtyChange: (dirty: boolean) => void }) {
  const [groups, setGroups] = useState<Group[]>([]);
  const [loading, setLoading] = useState(true);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<Group | null>(null);
  const [busy, setBusy] = useState(false);
  const [instructions, setInstructions] = useState("");
  const [questions, setQuestions] = useState<Question[]>([emptyQuestion()]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const originalQuestions = editing?.questions.map(toEditable);
  const dirty = adding && (editing ? instructions !== editing.instructions || JSON.stringify(questions) !== JSON.stringify(originalQuestions) : instructions !== "" || questions.some(q => q.questionText !== "" || q.answer !== "" || q.explanation !== ""));
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
    setQuestions(current => current.map((question, i) => i === index ? { ...question, ...change } : question));
  }
  function cancel() {
    if (dirty && !window.confirm("Nhóm câu hỏi chưa lưu sẽ bị mất. Bạn có muốn hủy?")) return;
    setAdding(false); setEditing(null); setInstructions(""); setQuestions([emptyQuestion()]); setError("");
  }
  function edit(group: Group) {
    setEditing(group); setInstructions(group.instructions);
    setQuestions(group.questions.map(toEditable));
    setAdding(true); setNotice(""); setError("");
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(""); setNotice("");
    if (!instructions.trim() || questions.some(q => !q.questionText.trim() || !answers.includes(q.answer))) {
      setError("Vui lòng nhập hướng dẫn, nội dung và đáp án cho từng câu."); return;
    }
    setBusy(true);
    try {
      const response = await api(`/api/admin/passages/${passageId}/question-groups${editing ? `/${editing.id}` : ""}`, editing ? "PUT" : "POST", {
        instructions, questions,
      });
      if (!response.ok) {
        setError(response.status === 409 ? "Passage không còn là bản nháp hoặc tổng số câu vượt quá 40. Vui lòng kiểm tra lại." : response.status === 400 ? "Thông tin nhóm câu hỏi chưa hợp lệ." : response.status === 404 ? "Passage hoặc nhóm câu hỏi không còn tồn tại." : "Không thể lưu nhóm câu hỏi. Vui lòng thử lại.");
        return;
      }
      setAdding(false); setEditing(null); setInstructions(""); setQuestions([emptyQuestion()]);
      setNotice("Đã lưu nhóm câu hỏi."); await load();
    } catch { setError("Không thể kết nối tới máy chủ. Vui lòng kiểm tra danh sách trước khi thử lưu lại."); }
    finally { setBusy(false); }
  }

  return <section className={styles.questions} aria-labelledby="questions-title">
    <div className={styles.sectionHead}><h2 id="questions-title">Nhóm câu hỏi</h2>{canCreate && !adding && <button type="button" className={styles.secondary} onClick={() => { setAdding(true); setError(""); setNotice(""); }}>+ Thêm nhóm</button>}</div>
    {error && <p className={styles.error} role="alert">{error}</p>}{notice && <p className={styles.notice} role="status">{notice}</p>}
    {loading ? <p className={styles.empty}>Đang tải câu hỏi...</p> : groups.length === 0 ? <p className={styles.empty}>Chưa có nhóm câu hỏi nào.</p> : groups.map(group => <div className={styles.savedGroup} key={group.id}><div className={styles.groupHeading}><h3>True / False / Not Given</h3>{canCreate && !adding && <button type="button" className={styles.open} onClick={() => edit(group)}>Chỉnh sửa</button>}</div><p className={styles.instructions}>{group.instructions}</p><ol className={styles.savedQuestions}>{group.questions.map(q => <li key={q.id}><strong>Câu {q.questionNumber}.</strong> {q.questionText}<div>Đáp án: <strong>{q.answer}</strong></div>{q.explanation && <div>Giải thích: {q.explanation}</div>}</li>)}</ol></div>)}
    {adding && <form className={styles.questionForm} onSubmit={submit} noValidate><h3>{editing ? "Chỉnh sửa nhóm" : "Thêm nhóm"} True / False / Not Given</h3><p className={styles.help}>Số câu được đánh tự động theo thứ tự trong passage. Nếu hướng dẫn có nhắc khoảng số câu, hãy tự kiểm tra sau khi thêm hoặc bỏ câu.</p>
      <label>Hướng dẫn chung<textarea value={instructions} onChange={e => setInstructions(e.target.value)} maxLength={10000} rows={3} /></label>
      {questions.map((q, index) => <fieldset className={styles.question} key={q.id ?? `new-${index}`}><legend>Câu {previousCount + index + 1}</legend>
        <label>Nội dung câu hỏi<textarea value={q.questionText} onChange={e => updateQuestion(index, { questionText: e.target.value })} maxLength={10000} rows={2} /></label>
        <fieldset className={styles.answerChoices}><legend>Đáp án đúng</legend>{answers.map(answer => <label key={answer}><input type="radio" name={`answer-${index}`} value={answer} checked={q.answer === answer} onChange={() => updateQuestion(index, { answer })} />{answer}</label>)}</fieldset>
        <label>Giải thích (không bắt buộc)<textarea value={q.explanation} onChange={e => updateQuestion(index, { explanation: e.target.value })} maxLength={10000} rows={2} /></label>
        {questions.length > 1 && <button type="button" className={styles.open} onClick={() => setQuestions(current => current.filter((_, i) => i !== index))}>Bỏ câu hỏi này</button>}
      </fieldset>)}
      <div className={styles.actions}><button type="button" className={styles.secondary} disabled={busy || questions.length >= 40} onClick={() => setQuestions(current => [...current, emptyQuestion()])}>+ Thêm câu hỏi</button></div>
      <div className={styles.actions}><button className={styles.primary} disabled={busy}>{busy ? "Đang lưu..." : editing ? "Lưu thay đổi" : "Lưu nhóm câu hỏi"}</button><button type="button" className={styles.secondary} disabled={busy} onClick={cancel}>Hủy</button></div>
    </form>}
  </section>;
}
