import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api } from "../../api";
import styles from "./ReadingPage.module.css";
import resultStyles from "./ReadingResult.module.css";

type Choice = { label: string; text: string };
type Question = { id: number; number: number; text: string; afterBlank: string | null; choices: Choice[] };
type Group = { id: number; variant: string; answerInputMode: string; instructions: string; candidateReuse: boolean; candidates: Choice[]; questions: Question[] };
type Passage = { id: number; partNumber: number; title: string; body: string; startNumber: number; groups: Group[] };
type Test = { id: number; title: string; passages: Passage[] };
type Attempt = { id: number; elapsedSeconds: number; answers: { questionId: number; value: string }[] };
type ReviewQuestion = { id: number; number: number; text: string; afterBlank: string | null; status: "CORRECT" | "INCORRECT" | "SKIPPED"; choices: Choice[]; submittedAnswer: string | null; correctAnswer: string; acceptedAnswers: string[]; explanation: string | null };
type ReviewGroup = { id: number; family: string; variant: string; instructions: string; candidates: Choice[]; questions: ReviewQuestion[] };
type ReviewPassage = { id: number; partNumber: number; title: string; body: string; startNumber: number; groups: ReviewGroup[] };
type Result = { testId: number; title: string; elapsedSeconds: number; correct: number; incorrect: number; skipped: number; total: number; accuracy: number; band: string; breakdown: { family: string; total: number; correct: number; incorrect: number; skipped: number }[]; passages: ReviewPassage[] };
const familyNames: Record<string, string> = { OPTION: "Lựa chọn", BLANK: "Điền từ", MATCHING: "Matching" };
const statusNames: Record<ReviewQuestion["status"], string> = { CORRECT: "Đúng", INCORRECT: "Sai", SKIPPED: "Bỏ qua" };
type Answers = Record<number, string>;
const format = (seconds: number) => `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;

export function ReadingTest() {
  const { id } = useParams(); const navigate = useNavigate();
  const [test, setTest] = useState<Test | null>(null); const [attempt, setAttempt] = useState<Attempt | null>(null); const [answers, setAnswers] = useState<Answers>({}); const [passageIndex, setPassageIndex] = useState(0); const [activeQuestionId, setActiveQuestionId] = useState<number | null>(null); const [elapsed, setElapsed] = useState(0); const [state, setState] = useState("loading"); const [saving, setSaving] = useState(false); const [confirm, setConfirm] = useState(false); const [loadAttempt, setLoadAttempt] = useState(0);
  const values = useRef<Answers>({}), saved = useRef<Answers>({}), running = useRef(false), debounce = useRef<ReturnType<typeof setTimeout> | null>(null), base = useRef(0), started = useRef(Date.now()), questionsPane = useRef<HTMLElement>(null);
  const nowElapsed = () => Math.min(3600, base.current + Math.floor((Date.now() - started.current) / 1000));
  useEffect(() => { let active = true; setState("loading"); setTest(null); setAttempt(null); (async () => { try { const [detail, start] = await Promise.all([api(`/api/reading/tests/${id}`), api(`/api/reading/tests/${id}/attempts`, "POST")]); if (!detail.ok || !start.ok) throw new Error(); const data = await detail.json() as Test, existing = await start.json() as Attempt; if (!active) return; const initial = Object.fromEntries(existing.answers.map(a => [a.questionId, a.value])); values.current = initial; saved.current = { ...initial }; setAnswers(initial); base.current = existing.elapsedSeconds; started.current = Date.now(); setElapsed(existing.elapsedSeconds); setTest(data); setAttempt(existing); setState("ready"); } catch { if (active) setState("error"); } })(); return () => { active = false; if (debounce.current) clearTimeout(debounce.current); }; }, [id, loadAttempt]);
  useEffect(() => { if (!attempt || state !== "ready") return; const tick = setInterval(() => { const current = nowElapsed(); setElapsed(current); if (current >= 3600) void submit(true); }, 1000); const heartbeat = setInterval(() => { void api(`/api/reading/tests/attempts/${attempt.id}/heartbeat`, "PUT", { elapsedSeconds: nowElapsed() }); }, 15000); return () => { clearInterval(tick); clearInterval(heartbeat); }; }, [attempt, state]);
  async function flush() { if (!attempt || running.current) return; running.current = true; setSaving(true); try { while (true) { const key = Object.keys(values.current).find(k => (values.current[Number(k)] || "") !== (saved.current[Number(k)] || "")); if (!key) break; const value = values.current[Number(key)] || ""; const r = await api(`/api/reading/tests/attempts/${attempt.id}/answers/${key}`, "PUT", { value }); if (!r.ok) throw new Error(); saved.current[Number(key)] = value; } } finally { running.current = false; setSaving(false); } }
  function answer(questionId: number, value: string) { values.current = { ...values.current, [questionId]: value }; setAnswers(values.current); if (debounce.current) clearTimeout(debounce.current); debounce.current = setTimeout(() => void flush(), 500); }
  async function submit(expired = false) { if (!attempt || saving) return; await flush(); const r = await api(`/api/reading/tests/attempts/${attempt.id}/submit`, "POST", { elapsedSeconds: nowElapsed() }); if (r.ok) navigate(`/reading/tests/attempts/${attempt.id}/result`, { replace: true }); else if (!expired) setConfirm(false); }
  function goToQuestion(question: Question) {
    const index = test?.passages.findIndex(passage => passage.groups.some(group => group.questions.some(item => item.id === question.id))) ?? -1;
    if (index < 0) return;
    setActiveQuestionId(question.id);
    setPassageIndex(index);
    window.setTimeout(() => {
      const panel = questionsPane.current;
      const target = panel?.querySelector<HTMLElement>(`#test-question-${question.number}`);
      if (!panel || !target) return;
      panel.scrollBy({ top: target.getBoundingClientRect().top - panel.getBoundingClientRect().top - 16 });
    }, 0);
  }
  if (state !== "ready" || !test || !attempt) return <main className={styles.library}><Link to="/reading">← Danh sách Reading</Link><p role={state === "error" ? "alert" : undefined}>{state === "loading" ? "Đang mở full test..." : "Không thể mở full test. Hãy kiểm tra kết nối rồi thử tải lại."}</p>{state === "error" && <button type="button" className={styles.submitButton} onClick={() => setLoadAttempt(value => value + 1)}>Thử tải lại</button>}</main>;
  const passage = test.passages[passageIndex]; const all = test.passages.flatMap(p => p.groups.flatMap(g => g.questions)); const answered = all.filter(q => (answers[q.id] || "").trim()).length;
  return <main className={styles.reader}>
    <header className={styles.readerHeader}>
      <Link to="/reading">← Danh sách</Link><h1>{test.title}</h1>
      <span className={styles.preview} role="timer">{format(Math.max(0, 3600 - elapsed))} · {saving ? "Đang lưu" : "Đã lưu"}</span>
    </header>
    <div className={styles.mobileTabs} role="tablist"><button aria-selected>Passage {passage.partNumber}</button></div>
    <div className={styles.workspace} style={{ gridTemplateColumns: "minmax(0, 1fr) 12px minmax(0, 1fr)" }}>
      <section className={`${styles.pane} ${styles.readingPane}`}>
        <div className={styles.readingContent}>
          <p className={styles.passageLabel}>Reading passage {passage.partNumber} · Questions {passage.startNumber}–{passage.startNumber + passage.groups.flatMap(group => group.questions).length - 1}</p>
          <h2>{passage.title}</h2><div className={styles.passageBody}>{passage.body}</div>
        </div>
      </section>
      <div className={styles.divider} aria-hidden>⋮</div>
      <section ref={questionsPane} className={`${styles.pane} ${styles.questionsPane}`}>
        <div className={styles.questionsContent}>
          <div className={styles.previewNote}><strong>Passage {passage.partNumber}</strong><br />Câu trả lời tự lưu; đồng hồ vẫn chạy khi bạn chuyển tab.</div>
          {passage.groups.map(group => <section className={styles.group} key={group.id}>
            <h2>Questions {group.questions[0]?.number}–{group.questions.at(-1)?.number}</h2><p className={styles.instructions}>{group.instructions}</p>
            {group.candidates.length > 0 && <div className={styles.candidates}><strong>Danh sách lựa chọn</strong><ul>{group.candidates.map(choice => <li key={choice.label}><b>{choice.label}.</b> {choice.text}</li>)}</ul></div>}
            {group.questions.map(question => <article className={styles.question} id={`test-question-${question.number}`} key={question.id}>
              <span className={styles.number}>{question.number}</span>
              <div className={styles.questionBody}>
                <p>{question.text}{group.answerInputMode === "TEXT_ENTRY" && <> <input className={styles.blank} value={answers[question.id] || ""} onChange={event => answer(question.id, event.target.value)} aria-label={`Câu trả lời ${question.number}`} /> {question.afterBlank}</>}</p>
                {group.candidates.length > 0 ? <select value={answers[question.id] || ""} onChange={event => answer(question.id, event.target.value)}><option value="">Chọn đáp án</option>{group.candidates.map(choice => <option value={choice.label} key={choice.label}>{choice.label} — {choice.text}</option>)}</select> : question.choices.length > 0 && <fieldset><legend className={styles.srOnly}>Câu {question.number}</legend>{question.choices.map(choice => <label className={styles.option} key={choice.label}><input type="radio" name={`test-${question.id}`} checked={answers[question.id] === choice.label} onChange={() => answer(question.id, choice.label)} /> {group.variant === "MULTIPLE_CHOICE_SINGLE" ? `${choice.label}. ` : ""}{choice.text}</label>)}</fieldset>}
              </div>
            </article>)}
          </section>)}
        </div>
      </section>
    </div>
    <footer className={styles.questionNav}>
      <span>Câu hỏi</span>
      <div>{all.map(question => <button key={question.id} className={`${(answers[question.id] || "").trim() ? styles.answered : ""} ${activeQuestionId === question.id ? styles.activeQuestion : ""}`} onClick={() => goToQuestion(question)} aria-current={activeQuestionId === question.id ? "true" : undefined}>{question.number}</button>)}</div>
      <span>{answered}/40</span><button className={styles.submitButton} onClick={() => setConfirm(true)}>Nộp bài</button>
    </footer>
    {confirm && <dialog open className={styles.submitDialog}>
      <h2>Xác nhận nộp full test</h2><p>Bạn đã trả lời <strong>{answered}/40</strong> câu. Thời gian còn lại: <strong>{format(Math.max(0, 3600 - elapsed))}</strong>.</p><p>Sau khi nộp, bạn không thể thay đổi câu trả lời.</p>
      <div className={styles.dialogActions}><button onClick={() => setConfirm(false)}>Tiếp tục làm</button><button className={styles.submitButton} onClick={() => void submit()}>Xác nhận nộp bài</button></div>
    </dialog>}
  </main>;
}

export function ReadingTestResult() {
  const { id } = useParams();
  const [result, setResult] = useState<Result | null>(null);
  const [review, setReview] = useState(false);
  const [passageIndex, setPassageIndex] = useState(0);
  const [tab, setTab] = useState<"reading" | "questions">("reading");
  const [width, setWidth] = useState(50);
  const [target, setTarget] = useState<number | null>(null);
  const workspace = useRef<HTMLDivElement>(null);
  const questionsPane = useRef<HTMLElement>(null);
  useEffect(() => {
    api(`/api/reading/tests/attempts/${id}/result`).then(async response => {
      if (response.ok) setResult(await response.json() as Result);
    });
  }, [id]);
  useEffect(() => {
    if (!review || target === null || tab !== "questions") return;
    window.setTimeout(() => {
      const pane = questionsPane.current;
      const question = pane?.querySelector<HTMLElement>(`#full-review-question-${target}`);
      if (pane && question) pane.scrollBy({ top: question.getBoundingClientRect().top - pane.getBoundingClientRect().top - 16 });
    }, 0);
    setTarget(null);
  }, [review, tab, target, passageIndex]);
  function resizePointer(event: PointerEvent<HTMLDivElement>) {
    const rect = workspace.current?.getBoundingClientRect();
    if (rect?.width) setWidth(Math.min(65, Math.max(30, (event.clientX - rect.left) * 100 / rect.width)));
  }
  function resizeKey(event: KeyboardEvent<HTMLDivElement>) {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    setWidth(current => event.key === "Home" ? 30 : event.key === "End" ? 65 : Math.max(30, Math.min(65, current + (event.key === "ArrowRight" ? 2 : -2))));
  }
  if (!result) return <main className={styles.library}><p>Đang tải kết quả...</p></main>;

  const correctEnd = result.total ? result.correct * 100 / result.total : 0;
  const incorrectEnd = result.total ? (result.correct + result.incorrect) * 100 / result.total : 0;
  const questions = result.passages.flatMap(passage => passage.groups.flatMap(group => group.questions));
  const passage = result.passages[passageIndex];
  if (review && passage) return <main className={styles.reader}>
    <header className={styles.readerHeader}>
      <button type="button" className={styles.retry} onClick={() => setReview(false)}>← Kết quả</button>
      <h1>{result.title}</h1><span className={styles.preview}>Xem lại full test đã nộp</span>
    </header>
    <div className={styles.mobileTabs} role="tablist" aria-label="Khu vực xem lại">
      <button role="tab" aria-selected={tab === "reading"} onClick={() => setTab("reading")}>Bài đọc</button>
      <button role="tab" aria-selected={tab === "questions"} onClick={() => setTab("questions")}>Câu hỏi</button>
    </div>
    <div className={styles.workspace} ref={workspace} style={{ gridTemplateColumns: `minmax(0, ${width}fr) 12px minmax(0, ${100 - width}fr)` }}>
      <section id="full-review-reading-panel" aria-label="Bài đọc" className={`${styles.pane} ${styles.readingPane} ${tab === "questions" ? styles.mobileHidden : ""}`}>
        <div className={styles.readingContent}><p className={styles.passageLabel}>Reading passage {passage.partNumber} · Questions {passage.startNumber}–{passage.startNumber + passage.groups.flatMap(group => group.questions).length - 1}</p><h2>{passage.title}</h2><div className={styles.passageBody}>{passage.body}</div></div>
      </section>
      <div className={styles.divider} role="separator" tabIndex={0} aria-label="Điều chỉnh độ rộng bài đọc và câu hỏi" aria-orientation="vertical" aria-valuemin={30} aria-valuemax={65} aria-valuenow={Math.round(width)} aria-controls="full-review-reading-panel" onPointerDown={event => { event.currentTarget.setPointerCapture(event.pointerId); resizePointer(event); }} onPointerMove={event => { if (event.currentTarget.hasPointerCapture(event.pointerId)) resizePointer(event); }} onKeyDown={resizeKey}><span aria-hidden="true">⋮</span></div>
      <section ref={questionsPane} aria-label="Kết quả từng câu" className={`${styles.pane} ${styles.questionsPane} ${tab === "reading" ? styles.mobileHidden : ""}`}>
        <div className={styles.questionsContent}>
          <div className={styles.previewNote}><strong>Passage {passage.partNumber}</strong><br />Đáp án và kết quả từng câu của bạn.</div>
          {passage.groups.map(group => <section className={styles.group} key={group.id}>
            <h2>Questions {group.questions[0]?.number}{group.questions.length > 1 ? `–${group.questions[group.questions.length - 1].number}` : ""}</h2><p className={styles.instructions}>{group.instructions}</p>
            {group.candidates.length > 0 && <div className={styles.candidates}><strong>Danh sách lựa chọn</strong><ul>{group.candidates.map(candidate => <li key={candidate.label}><b>{candidate.label}.</b> {candidate.text}</li>)}</ul></div>}
            {group.questions.map(question => <article className={styles.question} id={`full-review-question-${question.number}`} key={question.id}>
              <span className={styles.number}>{question.number}</span><div className={styles.questionBody}>
                <p>{question.text}{question.afterBlank !== null && <> <strong>___</strong> {question.afterBlank}</>}</p>
                {question.choices.length > 0 && <ul className={resultStyles.choices}>{question.choices.map(choice => <li key={choice.label}>{group.variant === "MULTIPLE_CHOICE_SINGLE" ? `${choice.label}. ` : ""}{choice.text}</li>)}</ul>}
                <div className={`${resultStyles.answer} ${resultStyles[question.status]}`}><strong>{statusNames[question.status]}</strong><div>Câu trả lời của bạn: {question.submittedAnswer ?? "Chưa trả lời"}</div><div>Đáp án: {question.acceptedAnswers.join(" / ")}</div></div>
                <details className={resultStyles.explanation}><summary>Giải thích</summary><p>{question.explanation || "Chưa có giải thích."}</p></details>
              </div>
            </article>)}
          </section>)}
        </div>
      </section>
    </div>
    <footer className={styles.questionNav} aria-label="Điều hướng câu hỏi"><span>Câu hỏi</span><div>{questions.map(question => {
      const index = result.passages.findIndex(item => item.groups.some(group => group.questions.some(candidate => candidate.id === question.id)));
      return <button key={question.id} className={resultStyles[`nav${question.status}`]} onClick={() => { setPassageIndex(index); setTab("questions"); setTarget(question.number); }} aria-label={`Đi đến câu ${question.number}, ${statusNames[question.status]}`}>{question.number}</button>;
    })}</div></footer>
  </main>;
  return <main className={resultStyles.page}>
    <Link to="/reading">← Danh sách Reading</Link>
    <header className={resultStyles.heading}>
      <p>Full test</p>
      <h1>Kết quả làm bài</h1>
      <span>{result.title}</span>
    </header>
    <section className={resultStyles.summary} aria-label="Tổng kết kết quả full test">
      <div className={resultStyles.ring} style={{ background: `conic-gradient(var(--success) 0 ${correctEnd}%, var(--danger) ${correctEnd}% ${incorrectEnd}%, var(--border) ${incorrectEnd}% 100%)` }}>
        <div><strong>{result.correct}/{result.total}</strong><span>câu đúng</span></div>
      </div>
      <div className={resultStyles.totals}>
        <p><span>Đúng</span><strong>{result.correct} câu</strong></p>
        <p><span>Sai</span><strong>{result.incorrect} câu</strong></p>
        <p><span>Bỏ qua</span><strong>{result.skipped} câu</strong></p>
        <p><span>Tỉ lệ đúng</span><strong>{Number(result.accuracy.toFixed(1))}%</strong></p>
        <p><span>Band IELTS Academic</span><strong>{result.band}</strong></p>
        <p><span>Thời gian làm bài</span><strong>{format(result.elapsedSeconds)}</strong></p>
      </div>
    </section>
    <div className={resultStyles.actions}>
      <button type="button" onClick={() => { setReview(true); setPassageIndex(0); setTab("reading"); }}>Xem lại bài</button>
      <Link className={resultStyles.secondaryAction} to="/reading">Về danh sách</Link>
    </div>
    <section className={resultStyles.breakdown}>
      <h2>Bảng dữ liệu chi tiết</h2>
      <div className={resultStyles.tableWrap}><table>
        <thead><tr><th scope="col">Dạng câu hỏi</th><th scope="col">Tổng</th><th scope="col">Đúng</th><th scope="col">Sai</th><th scope="col">Bỏ qua</th></tr></thead>
        <tbody>{result.breakdown.map(row => <tr key={row.family}>
          <th scope="row">{familyNames[row.family] || row.family}</th><td>{row.total}</td><td>{row.correct}</td><td>{row.incorrect}</td><td>{row.skipped}</td>
        </tr>)}</tbody>
      </table></div>
    </section>
  </main>;
}
