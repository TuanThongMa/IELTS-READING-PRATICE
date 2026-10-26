import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { Link, useBlocker, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { api } from "../../api";
import styles from "./ReadingPage.module.css";

type Summary = { id: number; title: string; partNumber: number; questionCount: number; questionTypes: string[]; inProgress: boolean; answeredCount: number; latestSubmittedAttemptId: number | null };
type TestSummary = { id: number; title: string; questionCount: number; inProgress: boolean; elapsedSeconds: number; answeredCount: number; latestSubmittedAttemptId: number | null };
type Choice = { label: string; text: string };
type Question = { id: number; number: number; text: string; afterBlank: string | null; choices: Choice[] };
type Group = { id: number; variant: string; answerInputMode: string; instructions: string; candidateReuse: boolean; candidates: Choice[]; questions: Question[] };
type Passage = { id: number; title: string; body: string; partNumber: number; groups: Group[] };
type Attempt = { id: number; passageId: number; status: string; elapsedSeconds: number; answers: { questionId: number; value: string }[] };
type Answers = Record<number, string>;
type SaveState = "saved" | "saving" | "failed";

function formatElapsed(seconds: number) {
  const hours = Math.floor(seconds / 3600).toString().padStart(2, "0");
  const minutes = Math.floor(seconds % 3600 / 60).toString().padStart(2, "0");
  const remainder = (seconds % 60).toString().padStart(2, "0");
  return `${hours}:${minutes}:${remainder}`;
}

const variantLabels: Record<string, string> = {
  MULTIPLE_CHOICE_SINGLE: "Trắc nghiệm một đáp án", MULTIPLE_CHOICE_MULTIPLE: "Trắc nghiệm nhiều đáp án",
  TRUE_FALSE_NOT_GIVEN: "True / False / Not Given", YES_NO_NOT_GIVEN: "Yes / No / Not Given",
  MATCHING_INFORMATION: "Matching Information", MATCHING_HEADINGS: "Matching Headings",
  MATCHING_FEATURES: "Matching Features", MATCHING_SENTENCE_ENDINGS: "Matching Sentence Endings",
  SENTENCE_COMPLETION: "Sentence Completion", SUMMARY_COMPLETION: "Summary Completion",
  NOTE_COMPLETION: "Note Completion", TABLE_COMPLETION: "Table Completion",
  FLOW_CHART_COMPLETION: "Flow-chart Completion", SHORT_ANSWER: "Short Answer",
};

export function ReadingLibrary({ authenticated }: { authenticated: boolean }) {
  const [searchParams] = useSearchParams();
  const [items, setItems] = useState<Summary[]>([]);
  const [tests, setTests] = useState<TestSummary[]>([]);
  const [status, setStatus] = useState("loading");
  const [contentType, setContentType] = useState<"passage" | "test">(() => searchParams.get("type") === "test" ? "test" : "passage");
  const [partNumber, setPartNumber] = useState<number | null>(null);
  useEffect(() => { let active = true; Promise.all([api("/api/reading/passages"), api("/api/reading/tests")]).then(async ([passages, fullTests]) => { if (!passages.ok || !fullTests.ok) throw new Error(); if (active) { setItems(await passages.json() as Summary[]); setTests(await fullTests.json() as TestSummary[]); setStatus("ready"); } }).catch(() => { if (active) setStatus("error"); }); return () => { active = false; }; }, []);
  const visiblePassages = items.filter(item => partNumber === null || item.partNumber === partNumber);
  const visibleCount = contentType === "passage" ? visiblePassages.length : tests.length;
  return <div className={styles.libraryPage}>
    <header className={styles.siteHeader}>
      <Link to="/reading" className={styles.siteBrand}><span aria-hidden="true">R</span> IELTS Reading Practice</Link>
      <nav aria-label="Điều hướng chính"><Link to="/">Trang chủ</Link><Link to="/reading" aria-current="page">Luyện Reading</Link></nav>
      {!authenticated && <Link className={styles.loginLink} to="/login">Đăng nhập</Link>}
    </header>
    <main className={styles.library}>
      <div className={styles.libraryIntro}><div><p className={styles.eyebrow}>IELTS ACADEMIC</p><h1>Luyện Reading</h1><p>Chọn một passage để luyện tập hoặc làm bài full test theo thời gian thực.</p></div>
        {authenticated && <Link className={styles.historyLink} to="/reading/history">Lịch sử làm bài</Link>}
      </div>
      <div className={styles.libraryLayout}>
        <aside className={styles.filterPanel} aria-label="Lọc dạng bài">
          <h2>Reading</h2>
          <label className={styles.filterChoice}><input type="radio" name="contentType" checked={contentType === "passage"} onChange={() => { setContentType("passage"); setPartNumber(null); }} /> <span>Bài lẻ</span></label>
          <div className={styles.partChoices}>{[1, 2, 3].map(part => <label key={part} className={styles.filterChoice}><input type="radio" name="partNumber" checked={contentType === "passage" && partNumber === part} onChange={() => { setContentType("passage"); setPartNumber(part); }} /> <span>Passage {part}</span></label>)}</div>
          <label className={styles.filterChoice}><input type="radio" name="contentType" checked={contentType === "test"} onChange={() => setContentType("test")} /> <span>Full test</span></label>
        </aside>
        <section className={styles.libraryResults} aria-labelledby="results-title">
          <div className={styles.libraryTitle}><div><p className={styles.eyebrow}>THƯ VIỆN BÀI ĐỌC</p><h2 id="results-title">{contentType === "test" ? "Full test" : partNumber ? `Passage ${partNumber}` : "Passage mới nhất"}</h2></div><span>{visibleCount} bài</span></div>
          {status === "loading" ? <p className={styles.listState}>Đang tải danh sách...</p> : status === "error" ? <div className={styles.listState} role="alert"><p>Không thể tải danh sách bài đọc lúc này.</p><button type="button" onClick={() => window.location.reload()}>Thử tải lại</button></div> : contentType === "passage" ? visiblePassages.length === 0 ? <p className={styles.listState}>Chưa có passage được xuất bản trong mục này.</p> : <div className={styles.libraryGrid}>{visiblePassages.map(item => <article className={styles.libraryCard} key={item.id}>
            <div className={styles.cardCover} data-part={item.partNumber}><span>PASSAGE {item.partNumber}</span><span aria-hidden="true">0{item.partNumber}</span></div>
            <div className={styles.libraryCardBody}><h3 title={item.title}>{item.title}</h3><p className={styles.cardMeta}>{item.questionCount} câu hỏi</p><ul className={styles.typeList}>{item.questionTypes.map(type => <li key={type}>{variantLabels[type] ?? type.replaceAll("_", " ")}</li>)}</ul>
              {authenticated && item.inProgress && <p className={styles.progressText}>Đang làm · {item.answeredCount}/{item.questionCount} câu đã trả lời</p>}
              <div className={styles.libraryCardActions}><Link className={styles.startLink} to={authenticated ? `/reading/passages/${item.id}` : "/login"}>{authenticated ? item.inProgress ? "Tiếp tục" : "Làm bài" : "Đăng nhập để làm bài"}</Link>{authenticated && <Link className={styles.cardHistoryLink} to={`/reading/history?passageId=${item.id}`}>Lịch sử</Link>}</div>
            </div>
          </article>)}</div> : tests.length === 0 ? <p className={styles.listState}>Chưa có full test được xuất bản.</p> : <div className={styles.libraryGrid}>{tests.map(test => <article className={styles.libraryCard} key={test.id}>
            <div className={styles.cardCover} data-part="test"><span>FULL TEST</span><span aria-hidden="true">60′</span></div>
            <div className={styles.libraryCardBody}><h3 title={test.title}>{test.title}</h3><p className={styles.cardMeta}>60 phút · {test.questionCount} câu hỏi</p>
              {authenticated && test.inProgress && <p className={styles.progressText}>Đang làm · {test.answeredCount}/{test.questionCount} câu đã trả lời</p>}
              <div className={styles.libraryCardActions}><Link className={styles.startLink} to={authenticated ? `/reading/tests/${test.id}` : "/login"}>{authenticated ? test.inProgress ? "Tiếp tục" : "Làm full test" : "Đăng nhập để làm bài"}</Link></div>
            </div>
          </article>)}</div>}
        </section>
      </div>
    </main>
  </div>;
}

export function ReadingPassage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [passage, setPassage] = useState<Passage | null>(null);
  const [attempt, setAttempt] = useState<Attempt | null>(null);
  const [status, setStatus] = useState("loading");
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [answers, setAnswers] = useState<Answers>({});
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [clockSaveError, setClockSaveError] = useState(false);
  const [saveState, setSaveState] = useState<SaveState>("saved");
  const [width, setWidth] = useState(50);
  const [tab, setTab] = useState<"reading" | "questions">("reading");
  const [target, setTarget] = useState<number | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState(false);
  const dialog = useRef<HTMLDialogElement>(null);
  const workspace = useRef<HTMLDivElement>(null);
  const questionsPane = useRef<HTMLElement>(null);
  const values = useRef<Answers>({});
  const acknowledged = useRef<Answers>({});
  const running = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const elapsedBase = useRef(0);
  const startedAt = useRef(0);
  const clockSaving = useRef(false);
  const submitted = useRef(false);
  const mounted = useRef(true);
  const blocker = useBlocker(saveState !== "saved");

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; if (timer.current) clearTimeout(timer.current); }; }, []);
  useEffect(() => {
    let active = true;
    setStatus("loading");
    setPassage(null);
    setAttempt(null);
    (async () => {
      try {
        const response = await api(`/api/reading/passages/${id}`);
        if (!response.ok) { if (active) setStatus(response.status === 404 ? "missing" : "error"); return; }
        const data = await response.json() as Passage;
        const start = await api(`/api/reading/passages/${id}/attempts`, "POST");
        if (!start.ok) throw new Error();
        const existing = await start.json() as Attempt;
        if (!active) return;
        const initial = Object.fromEntries(existing.answers.map(a => [a.questionId, a.value])) as Answers;
        values.current = initial; acknowledged.current = { ...initial }; elapsedBase.current = existing.elapsedSeconds; startedAt.current = Date.now(); setElapsedSeconds(existing.elapsedSeconds); setClockSaveError(false); setAnswers(initial); setAttempt(existing); setPassage(data); setSaveState("saved"); setStatus("ready");
      } catch { if (active) setStatus("error"); }
    })();
    return () => { active = false; };
  }, [id, loadAttempt]);
  useEffect(() => {
    if (!attempt || status !== "ready") return;
    const tick = setInterval(() => setElapsedSeconds(elapsedBase.current + Math.floor((Date.now() - startedAt.current) / 1000)), 1000);
    const checkpoint = setInterval(() => { void saveClock(); }, 15_000);
    const onVisibilityChange = () => { void saveClock(); };
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => { clearInterval(tick); clearInterval(checkpoint); document.removeEventListener("visibilitychange", onVisibilityChange); if (!submitted.current) void saveClock(); };
  }, [attempt, status]);
  useEffect(() => {
    if (target === null || tab !== "questions") return;
    const pane = questionsPane.current;
    const question = pane?.querySelector<HTMLElement>(`#question-${target}`);
    if (pane && question) pane.scrollBy({ top: question.getBoundingClientRect().top - pane.getBoundingClientRect().top - 16 });
    setTarget(null);
  }, [target, tab]);
  useEffect(() => { if (blocker.state === "blocked") { if (window.confirm("Một số câu trả lời chưa được lưu. Bạn vẫn muốn rời trang?")) blocker.proceed(); else blocker.reset(); } }, [blocker]);
  useEffect(() => { if (saveState === "saved") return; const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; }; window.addEventListener("beforeunload", warn); return () => window.removeEventListener("beforeunload", warn); }, [saveState]);
  useEffect(() => { if (confirmOpen && dialog.current && !dialog.current.open) dialog.current.showModal(); }, [confirmOpen]);

  async function flush() {
    if (!attempt || running.current) return;
    running.current = true;
    try {
      while (true) {
        const questionId = Object.keys(values.current).find(key => (values.current[Number(key)] || "") !== (acknowledged.current[Number(key)] || ""));
        if (!questionId) { if (mounted.current) setSaveState("saved"); break; }
        const value = values.current[Number(questionId)] || "";
        const response = await api(`/api/reading/attempts/${attempt.id}/answers/${questionId}`, "PUT", { value });
        if (!response.ok) throw new Error();
        acknowledged.current[Number(questionId)] = value;
      }
    } catch { if (mounted.current) setSaveState("failed"); }
    finally { running.current = false; }
  }
  function currentElapsed() { return elapsedBase.current + Math.floor((Date.now() - startedAt.current) / 1000); }
  async function saveClock() {
    if (!attempt || clockSaving.current) return;
    clockSaving.current = true;
    try {
      const response = await api(`/api/reading/attempts/${attempt.id}/heartbeat`, "PUT", { elapsedSeconds: currentElapsed() });
      if (!response.ok) throw new Error();
      if (mounted.current) setClockSaveError(false);
    } catch { if (mounted.current) setClockSaveError(true); }
    finally { clockSaving.current = false; }
  }
  function updateAnswer(questionId: number, value: string) {
    values.current = { ...values.current, [questionId]: value };
    setAnswers(values.current); setSaveState("saving");
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => { void flush(); }, 500);
  }
  function retry() { setSaveState("saving"); void flush(); }
  async function submit() {
    if (!attempt || saveState !== "saved" || submitting) return;
    setSubmitting(true); setSubmitError(false);
    try {
      const response = await api(`/api/reading/attempts/${attempt.id}/submit`, "POST", { elapsedSeconds: currentElapsed() });
      if (!response.ok) throw new Error();
      submitted.current = true;
      setConfirmOpen(false); navigate(`/reading/attempts/${attempt.id}/result`, { replace: true });
    } catch { setSubmitError(true); }
    finally { setSubmitting(false); }
  }
  function setFromPointer(e: PointerEvent<HTMLDivElement>) { const rect = workspace.current?.getBoundingClientRect(); if (rect && rect.width) setWidth(Math.min(65, Math.max(30, (e.clientX - rect.left) * 100 / rect.width))); }
  function resizeKey(e: KeyboardEvent<HTMLDivElement>) { if (e.key === "ArrowLeft" || e.key === "ArrowRight" || e.key === "Home" || e.key === "End") { e.preventDefault(); setWidth(current => e.key === "Home" ? 30 : e.key === "End" ? 65 : Math.max(30, Math.min(65, current + (e.key === "ArrowRight" ? 2 : -2)))); } }
  if (status !== "ready" || !passage || !attempt) return <main className={styles.library}><Link to="/reading">← Danh sách passage</Link><p role={status === "error" || status === "missing" ? "alert" : undefined}>{status === "loading" ? "Đang mở bài làm..." : status === "missing" ? "Passage không tồn tại hoặc chưa được xuất bản." : "Không thể mở bài làm. Hãy kiểm tra kết nối rồi thử tải lại."}</p>{status === "error" && <button type="button" className={styles.submitButton} onClick={() => setLoadAttempt(value => value + 1)}>Thử tải lại</button>}</main>;
  const questions = passage.groups.flatMap(group => group.questions);
  const answered = questions.filter(q => (answers[q.id] || "").trim()).length;
  return <main className={styles.reader}><header className={styles.readerHeader}><Link to="/reading">← Danh sách</Link><h1>{passage.title}</h1><span className={styles.elapsedTime} role="timer" aria-label="Thời gian làm bài">{formatElapsed(elapsedSeconds)}</span><span className={styles.preview} role="status" aria-live="polite">{saveState === "saving" ? "Đang lưu..." : saveState === "failed" ? <>Chưa lưu được. <button type="button" className={styles.retry} onClick={retry}>Thử lưu lại</button></> : "Đã lưu"}</span></header><div className={styles.mobileTabs} role="tablist" aria-label="Khu vực bài đọc"><button role="tab" aria-selected={tab === "reading"} onClick={() => setTab("reading")}>Bài đọc</button><button role="tab" aria-selected={tab === "questions"} onClick={() => setTab("questions")}>Câu hỏi</button></div><div className={styles.workspace} ref={workspace} style={{ gridTemplateColumns: `minmax(0, ${width}fr) 12px minmax(0, ${100 - width}fr)` }}>
    <section id="reading-panel" aria-label="Bài đọc" className={`${styles.pane} ${styles.readingPane} ${tab === "questions" ? styles.mobileHidden : ""}`}><div className={styles.readingContent}><p className={styles.passageLabel}>Reading passage {passage.partNumber}</p><h2>{passage.title}</h2><div className={styles.passageBody}>{passage.body}</div></div></section>
    <div className={styles.divider} role="separator" tabIndex={0} aria-label="Điều chỉnh độ rộng bài đọc và câu hỏi" aria-orientation="vertical" aria-valuemin={30} aria-valuemax={65} aria-valuenow={Math.round(width)} aria-controls="reading-panel" onPointerDown={e => { e.currentTarget.setPointerCapture(e.pointerId); setFromPointer(e); }} onPointerMove={e => { if (e.currentTarget.hasPointerCapture(e.pointerId)) setFromPointer(e); }} onKeyDown={resizeKey}><span aria-hidden="true">⋮</span></div>
    <section ref={questionsPane} aria-label="Câu hỏi" className={`${styles.pane} ${styles.questionsPane} ${tab === "reading" ? styles.mobileHidden : ""}`}><div className={styles.questionsContent}><p className={styles.previewNote}>Câu trả lời được lưu tự động. Đồng hồ vẫn chạy khi bạn chuyển tab và thời lượng được lưu định kỳ mỗi 15 giây.</p>{clockSaveError && <p className={styles.clockWarning} role="alert">Chưa lưu được thời gian gần nhất. Hệ thống sẽ thử lại ở mốc tiếp theo.</p>}{passage.groups.map(group => <section className={styles.group} key={group.id}><h2>Questions {group.questions[0]?.number}{group.questions.length > 1 ? `–${group.questions[group.questions.length - 1].number}` : ""}</h2><p className={styles.instructions}>{group.instructions}</p>{group.candidates.length > 0 && <div className={styles.candidates}><strong>Danh sách lựa chọn</strong><ul>{group.candidates.map(c => <li key={c.label}><b>{c.label}.</b> {c.text}</li>)}</ul>{group.candidateReuse && <p>Một lựa chọn có thể được sử dụng nhiều lần.</p>}</div>}{group.questions.map(q => <article className={styles.question} id={`question-${q.number}`} key={q.id}><span className={styles.number}>{q.number}</span><div className={styles.questionBody}><p>{q.text}{group.answerInputMode === "TEXT_ENTRY" && <> <input aria-label={`Câu trả lời ${q.number}`} className={styles.blank} placeholder="Đáp án" maxLength={1000} value={answers[q.id] || ""} onChange={e => updateAnswer(q.id, e.target.value)} /> {q.afterBlank}</>}</p>{group.candidates.length > 0 ? <select aria-label={`Đáp án câu ${q.number}`} value={answers[q.id] || ""} onChange={e => updateAnswer(q.id, e.target.value)}><option value="">Chọn đáp án</option>{group.candidates.map(c => <option key={c.label} value={c.label}>{c.label} — {c.text}</option>)}</select> : q.choices.length > 0 && <fieldset><legend className={styles.srOnly}>Lựa chọn câu {q.number}</legend>{q.choices.map(c => <label className={styles.option} key={c.label}><input type="radio" name={`question-${q.id}`} checked={answers[q.id] === c.label} onChange={() => updateAnswer(q.id, c.label)} /> <span>{group.variant === "MULTIPLE_CHOICE_SINGLE" ? `${c.label}. ` : ""}{c.text}</span></label>)}{answers[q.id] && <button type="button" className={styles.clearAnswer} onClick={() => updateAnswer(q.id, "")}>Bỏ chọn</button>}</fieldset>}</div></article>)}</section>)}</div></section>
  </div><footer className={styles.questionNav} aria-label="Điều hướng câu hỏi"><span>Câu hỏi</span><div>{questions.map(q => <button key={q.id} className={(answers[q.id] || "").trim() ? styles.answered : ""} onClick={() => { setTab("questions"); setTarget(q.number); }} aria-label={`Đi đến câu ${q.number}, ${(answers[q.id] || "").trim() ? "đã trả lời" : "chưa trả lời"}`}>{q.number}</button>)}</div><span>Đã trả lời: {answered}/{questions.length}</span><button type="button" className={styles.submitButton} onClick={() => { setSubmitError(false); setConfirmOpen(true); }}>Nộp bài</button></footer>
    {confirmOpen && <dialog ref={dialog} className={styles.submitDialog} aria-labelledby="submit-title" onCancel={e => { if (submitting) e.preventDefault(); else setConfirmOpen(false); }}><h2 id="submit-title">Xác nhận nộp bài</h2><p>Bạn đã trả lời <strong>{answered}/{questions.length}</strong> câu.</p><p>{questions.length - answered > 0 ? `Còn ${questions.length - answered} câu chưa trả lời. Bạn vẫn có thể nộp bài.` : "Bạn đã trả lời tất cả câu hỏi."}</p><p>Sau khi nộp, bạn không thể thay đổi câu trả lời của lượt làm này.</p>{saveState !== "saved" && <p role="status">{saveState === "saving" ? "Đang lưu câu trả lời, vui lòng chờ." : <>Có câu trả lời chưa được lưu. <button type="button" className={styles.retry} onClick={retry}>Thử lưu lại</button></>}</p>}{submitError && <p role="alert">Chưa xác nhận được việc nộp bài. Hãy kiểm tra kết nối và thử lại.</p>}<div className={styles.dialogActions}><button type="button" disabled={submitting} onClick={() => setConfirmOpen(false)}>Tiếp tục làm bài</button><button type="button" className={styles.submitButton} disabled={saveState !== "saved" || submitting} onClick={() => void submit()}>{submitting ? "Đang nộp..." : "Xác nhận nộp bài"}</button></div></dialog>}
  </main>;
}
