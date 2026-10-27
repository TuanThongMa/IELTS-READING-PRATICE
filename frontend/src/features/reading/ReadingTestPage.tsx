import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { Link, useBlocker, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { api } from "../../api";
import { PassageText } from "./ReadingPage";
import styles from "./ReadingPage.module.css";
import resultStyles from "./ReadingResult.module.css";

type Choice = { label: string; text: string };
type Question = { id: number; number: number; text: string; afterBlank: string | null; choices: Choice[] };
type Group = { id: number; variant: string; answerInputMode: string; instructions: string; candidateReuse: boolean; candidates: Choice[]; questions: Question[] };
type Passage = { id: number; partNumber: number; title: string; body: string; startNumber: number; groups: Group[] };
type Test = { id: number; title: string; passages: Passage[] };
type ReadingMode = "PRACTICE" | "REAL_TEST";
type Attempt = { id: number; status: string; mode: ReadingMode; elapsedSeconds: number; answers: { questionId: number; value: string }[] };
type ClockState = { status: string; elapsedSeconds: number };
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
  const [searchParams] = useSearchParams();
  const [test, setTest] = useState<Test | null>(null); const [attempt, setAttempt] = useState<Attempt | null>(null); const [answers, setAnswers] = useState<Answers>({}); const [passageIndex, setPassageIndex] = useState(0); const [activeQuestionId, setActiveQuestionId] = useState<number | null>(null); const [elapsed, setElapsed] = useState(0); const [state, setState] = useState("loading"); const [saving, setSaving] = useState(false); const [saveError, setSaveError] = useState(false); const [expired, setExpired] = useState(false); const [confirm, setConfirm] = useState(false); const [exitConfirm, setExitConfirm] = useState(false); const [exitSaveError, setExitSaveError] = useState(false); const [loadAttempt, setLoadAttempt] = useState(0);
  const values = useRef<Answers>({}), saved = useRef<Answers>({}), running = useRef(false), debounce = useRef<ReturnType<typeof setTimeout> | null>(null), base = useRef(0), started = useRef(Date.now()), questionsPane = useRef<HTMLElement>(null), exitDialog = useRef<HTMLDialogElement>(null), submitted = useRef(false), leaving = useRef(false), exitApproved = useRef(false), expirySubmitting = useRef(false), expiryRetryAt = useRef(0);
  const blocker = useBlocker(({ currentLocation, nextLocation }) => currentLocation.pathname !== nextLocation.pathname && attempt?.mode === "REAL_TEST" && state === "ready" && !submitted.current && !exitApproved.current);
  const nowElapsed = () => {
    const current = base.current + Math.floor((Date.now() - started.current) / 1000);
    return attempt?.mode === "REAL_TEST" ? Math.min(3600, current) : current;
  };
  useEffect(() => { let active = true; setState("loading"); setTest(null); setAttempt(null); setActiveQuestionId(null); setExpired(false); expirySubmitting.current = false; expiryRetryAt.current = 0; submitted.current = false; leaving.current = false; exitApproved.current = false; (async () => { try { const requestedMode: ReadingMode = searchParams.get("mode") === "REAL_TEST" ? "REAL_TEST" : "PRACTICE"; const [detail, start] = await Promise.all([api(`/api/reading/tests/${id}`), api(`/api/reading/tests/${id}/attempts`, "POST", { mode: requestedMode })]); if (!detail.ok || !start.ok) throw new Error(); const data = await detail.json() as Test, existing = await start.json() as Attempt; if (!active) return; if (existing.status === "SUBMITTED") { submitted.current = true; navigate(`/reading/tests/attempts/${existing.id}/result`, { replace: true }); return; } const initial = Object.fromEntries(existing.answers.map(a => [a.questionId, a.value])); values.current = initial; saved.current = { ...initial }; setAnswers(initial); base.current = existing.elapsedSeconds; started.current = Date.now(); setElapsed(existing.elapsedSeconds); setTest(data); setAttempt(existing); setState("ready"); } catch { if (active) setState("error"); } })(); return () => { active = false; if (debounce.current) clearTimeout(debounce.current); }; }, [id, loadAttempt, searchParams, navigate]);
  useEffect(() => { if (blocker.state === "blocked") setExitConfirm(true); }, [blocker.state]);
  useEffect(() => { if (exitConfirm && exitDialog.current && !exitDialog.current.open) exitDialog.current.showModal(); else if (!exitConfirm && exitDialog.current?.open) exitDialog.current.close(); }, [exitConfirm]);
  useEffect(() => { if (attempt?.mode !== "REAL_TEST") return; const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; }; window.addEventListener("beforeunload", warn); return () => window.removeEventListener("beforeunload", warn); }, [attempt?.mode]);
  useEffect(() => { if (!attempt || state !== "ready") return; const tick = setInterval(() => { const current = nowElapsed(); setElapsed(current); if (attempt.mode === "REAL_TEST" && current >= 3600) { setExpired(true); if (navigator.onLine && Date.now() >= expiryRetryAt.current && !expirySubmitting.current) { expirySubmitting.current = true; void submit(true).finally(() => { if (!submitted.current) expirySubmitting.current = false; }); } } }, 1000); const heartbeat = setInterval(() => { void saveClock(); }, 15000); const onVisibilityChange = () => { void saveClock(); }; const onOnline = () => { void saveClock(true); }; document.addEventListener("visibilitychange", onVisibilityChange); window.addEventListener("online", onOnline); return () => { clearInterval(tick); clearInterval(heartbeat); document.removeEventListener("visibilitychange", onVisibilityChange); window.removeEventListener("online", onOnline); if (!submitted.current && !leaving.current) void saveClock(); }; }, [attempt, state]);
  async function flush() { if (!attempt) return false; while (running.current) await new Promise(resolve => window.setTimeout(resolve, 25)); running.current = true; setSaving(true); setSaveError(false); try { while (true) { const key = Object.keys(values.current).find(k => (values.current[Number(k)] || "") !== (saved.current[Number(k)] || "")); if (!key) break; const value = values.current[Number(key)] || ""; const r = await api(`/api/reading/tests/attempts/${attempt.id}/answers/${key}`, "PUT", { value }); if (!r.ok) throw new Error(); saved.current[Number(key)] = value; } return true; } catch { setSaveError(true); return false; } finally { running.current = false; setSaving(false); } }
  function answer(questionId: number, value: string) { values.current = { ...values.current, [questionId]: value }; setAnswers(values.current); if (debounce.current) clearTimeout(debounce.current); debounce.current = setTimeout(() => void flush(), 500); }
  async function saveClock(recovered = false) { if (!attempt) return false; try { const suffix = recovered ? "?recovered=true" : ""; const response = await api(`/api/reading/tests/attempts/${attempt.id}/heartbeat${suffix}`, "PUT"); if (!response.ok) return false; const clock = await response.json() as ClockState; const syncedElapsed = recovered ? clock.elapsedSeconds : Math.max(base.current, clock.elapsedSeconds); base.current = syncedElapsed; started.current = Date.now(); setElapsed(syncedElapsed); if (clock.status === "SUBMITTED") { submitted.current = true; navigate(`/reading/tests/attempts/${attempt.id}/result`, { replace: true }); } return true; } catch { return false; } }
  async function leavePractice() { if (!attempt || attempt.mode !== "PRACTICE") return; if (debounce.current) clearTimeout(debounce.current); const answersSaved = await flush(); if (!answersSaved) return; try { const response = await api(`/api/reading/tests/attempts/${attempt.id}/pause`, "PUT"); if (!response.ok) throw new Error(); leaving.current = true; navigate("/reading"); } catch { setSaveError(true); } }
  async function leaveRealTest(force = false) { if (!attempt || attempt.mode !== "REAL_TEST") return; setExitSaveError(false); if (debounce.current) clearTimeout(debounce.current); const answersSaved = await flush(); let paused: Attempt | null = null; try { const response = await api(`/api/reading/tests/attempts/${attempt.id}/pause`, "PUT"); if (response.ok) paused = await response.json() as Attempt; } catch { paused = null; } if (paused?.status === "SUBMITTED") { submitted.current = true; navigate(`/reading/tests/attempts/${attempt.id}/result`, { replace: true }); return; } if ((!answersSaved || !paused) && !force) { setExitSaveError(true); return; } leaving.current = true; exitApproved.current = true; setExitConfirm(false); if (blocker.state === "blocked") blocker.proceed(); else navigate("/reading"); }
  async function submit(fromExpiry = false) { if (!attempt || saving) return; if (debounce.current) clearTimeout(debounce.current); const answersSaved = await flush(); if (!answersSaved && !fromExpiry) return; try { const response = await api(`/api/reading/tests/attempts/${attempt.id}/submit`, "POST"); if (!response.ok) throw new Error(); submitted.current = true; navigate(`/reading/tests/attempts/${attempt.id}/result`, { replace: true }); } catch { setSaveError(true); if (fromExpiry) expiryRetryAt.current = Date.now() + 5000; else setConfirm(false); } }
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
      {attempt.mode === "REAL_TEST" ? <button type="button" className={styles.exitButton} onClick={() => { setExitSaveError(false); setExitConfirm(true); }}>Thoát thi</button> : <button type="button" className={styles.retry} onClick={() => void leavePractice()}>← Danh sách</button>}<h1>{test.title}</h1>
      {attempt.mode === "REAL_TEST" && <span className={styles.modeBadge}>Thi thật</span>}
      <span className={styles.preview} role="timer">{attempt.mode === "REAL_TEST" ? `Còn lại ${format(Math.max(0, 3600 - elapsed))}` : `Đã làm ${format(elapsed)}`} · {expired ? (saving ? "Đang nộp bài" : saveError ? <span role="alert">Hết giờ · chưa nộp được</span> : "Đang nộp bài") : saving ? "Đang lưu" : saveError ? <><span role="alert">Chưa lưu được</span> · <button type="button" className={styles.retry} onClick={() => void flush()}>Thử lại</button></> : "Đã lưu"}</span>
    </header>
    <div className={styles.mobileTabs} role="tablist"><button aria-selected>Passage {passage.partNumber}</button></div>
    <div className={styles.workspace} style={{ gridTemplateColumns: "minmax(0, 1fr) 12px minmax(0, 1fr)" }}>
      <section className={`${styles.pane} ${styles.readingPane}`}>
        <div className={styles.readingContent}>
          <p className={styles.passageLabel}>Reading passage {passage.partNumber} · Questions {passage.startNumber}–{passage.startNumber + passage.groups.flatMap(group => group.questions).length - 1}</p>
          <h2>{passage.title}</h2><PassageText body={passage.body} passageId={passage.id} allowDictionary={attempt.mode !== "REAL_TEST"} />
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
      {saveError && <p role="alert">Có câu trả lời chưa được lưu. Hãy thử lại trước khi nộp.</p>}
      <div className={styles.dialogActions}><button onClick={() => setConfirm(false)}>Tiếp tục làm</button><button className={styles.submitButton} disabled={saving} onClick={() => void submit()}>{saving ? "Đang lưu..." : "Xác nhận nộp bài"}</button></div>
    </dialog>}
    {attempt.mode === "REAL_TEST" && <dialog ref={exitDialog} className={styles.submitDialog} aria-labelledby="test-exit-title" onCancel={event => { event.preventDefault(); if (blocker.state === "blocked") blocker.reset(); setExitConfirm(false); }}>
      <h2 id="test-exit-title">Thoát chế độ thi thật?</h2><p>Lượt làm sẽ được lưu để bạn tiếp tục sau. Đồng hồ dừng khi thoát và tiếp tục khi mở lại lượt làm.</p>
      {exitSaveError && <p role="alert">Chưa xác nhận được lưu đầy đủ câu trả lời hoặc thời gian. Hãy thử lại; nếu vẫn muốn thoát, bạn có thể thoát dù chưa lưu dữ liệu mới nhất.</p>}
      <div className={styles.dialogActions}><button type="button" onClick={() => { if (blocker.state === "blocked") blocker.reset(); setExitConfirm(false); }}>Ở lại làm bài</button><button type="button" className={styles.submitButton} onClick={() => void leaveRealTest()}>Thoát, tiếp tục sau</button>{exitSaveError && <button type="button" className={styles.forceExitButton} onClick={() => void leaveRealTest(true)}>Thoát dù chưa lưu</button>}</div>
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
  const [activeQuestionNumber, setActiveQuestionNumber] = useState<number | null>(null);
  const workspace = useRef<HTMLDivElement>(null);
  const questionsPane = useRef<HTMLElement>(null);
  useEffect(() => {
    setActiveQuestionNumber(null);
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
      const isActive = activeQuestionNumber === question.number;
      return <button key={question.id} className={`${resultStyles[`nav${question.status}`]} ${isActive ? resultStyles.navActive : ""}`} onClick={() => { setActiveQuestionNumber(question.number); setPassageIndex(index); setTab("questions"); setTarget(question.number); }} aria-current={isActive ? "true" : undefined} aria-label={`Đi đến câu ${question.number}, ${statusNames[question.status]}${isActive ? ", đang chọn" : ""}`}>{question.number}</button>;
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
      <button type="button" onClick={() => { setReview(true); setPassageIndex(0); setTab("reading"); setActiveQuestionNumber(null); }}>Xem lại bài</button>
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
