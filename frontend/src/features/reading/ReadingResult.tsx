import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../../api";
import styles from "./ReadingPage.module.css";
import resultStyles from "./ReadingResult.module.css";

type Choice = { label: string; text: string };
type Question = { id: number; number: number; text: string; afterBlank: string | null; status: "CORRECT" | "INCORRECT" | "SKIPPED"; choices: Choice[]; submittedAnswer: string | null; correctAnswer: string; acceptedAnswers: string[]; explanation: string | null };
type Group = { id: number; family: string; variant: string; instructions: string; candidates: Choice[]; questions: Question[] };
type Result = { attemptId: number; title: string; body: string; partNumber: number; total: number; correct: number; incorrect: number; skipped: number; accuracy: number; breakdown: { family: string; total: number; correct: number; incorrect: number; skipped: number }[]; groups: Group[] };
const familyNames: Record<string, string> = { OPTION: "Lựa chọn", BLANK: "Điền từ", MATCHING: "Matching" };
const statusNames: Record<Question["status"], string> = { CORRECT: "Đúng", INCORRECT: "Sai", SKIPPED: "Bỏ qua" };

export function ReadingResult() {
  const { id } = useParams();
  const [result, setResult] = useState<Result | null>(null);
  const [loadState, setLoadState] = useState("loading");
  const [review, setReview] = useState(false);
  const [tab, setTab] = useState<"reading" | "questions">("reading");
  const [width, setWidth] = useState(50);
  const [target, setTarget] = useState<number | null>(null);
  const workspace = useRef<HTMLDivElement>(null);
  const questionsPane = useRef<HTMLElement>(null);
  useEffect(() => {
    let active = true;
    api(`/api/reading/attempts/${id}/result`).then(async response => {
      if (!response.ok) { if (active) setLoadState(response.status === 404 ? "missing" : response.status === 409 ? "unfinished" : "error"); return; }
      const data = await response.json() as Result;
      if (active) { setResult(data); setLoadState("ready"); }
    }).catch(() => { if (active) setLoadState("error"); });
    return () => { active = false; };
  }, [id]);
  useEffect(() => {
    if (!review || target === null || tab !== "questions") return;
    const pane = questionsPane.current;
    const question = pane?.querySelector<HTMLElement>(`#review-question-${target}`);
    if (pane && question) pane.scrollBy({ top: question.getBoundingClientRect().top - pane.getBoundingClientRect().top - 16 });
    setTarget(null);
  }, [review, tab, target]);
  function resizePointer(e: PointerEvent<HTMLDivElement>) { const rect = workspace.current?.getBoundingClientRect(); if (rect?.width) setWidth(Math.min(65, Math.max(30, (e.clientX - rect.left) * 100 / rect.width))); }
  function resizeKey(e: KeyboardEvent<HTMLDivElement>) { if (["ArrowLeft", "ArrowRight", "Home", "End"].includes(e.key)) { e.preventDefault(); setWidth(current => e.key === "Home" ? 30 : e.key === "End" ? 65 : Math.max(30, Math.min(65, current + (e.key === "ArrowRight" ? 2 : -2)))); } }
  if (!result || loadState !== "ready") return <main className={styles.library}><Link to="/reading">← Danh sách passage</Link><p role={loadState === "loading" ? "status" : "alert"}>{loadState === "loading" ? "Đang tải kết quả..." : loadState === "missing" ? "Không tìm thấy kết quả của bạn." : loadState === "unfinished" ? "Bài làm chưa được nộp." : "Không thể tải kết quả. Hãy tải lại trang."}</p></main>;
  const questions = result.groups.flatMap(group => group.questions);
  const correctEnd = result.total ? result.correct * 100 / result.total : 0;
  const incorrectEnd = result.total ? (result.correct + result.incorrect) * 100 / result.total : 0;
  if (review) return <main className={styles.reader}><header className={styles.readerHeader}><button type="button" className={styles.retry} onClick={() => setReview(false)}>← Kết quả</button><h1>{result.title}</h1><span className={styles.preview}>Xem lại bài đã nộp</span></header><div className={styles.mobileTabs} role="tablist" aria-label="Khu vực xem lại"><button role="tab" aria-selected={tab === "reading"} onClick={() => setTab("reading")}>Bài đọc</button><button role="tab" aria-selected={tab === "questions"} onClick={() => setTab("questions")}>Câu hỏi</button></div><div className={styles.workspace} ref={workspace} style={{ gridTemplateColumns: `minmax(0, ${width}fr) 12px minmax(0, ${100 - width}fr)` }}>
    <section id="review-reading-panel" aria-label="Bài đọc" className={`${styles.pane} ${styles.readingPane} ${tab === "questions" ? styles.mobileHidden : ""}`}><div className={styles.readingContent}><p className={styles.passageLabel}>Reading passage {result.partNumber}</p><h2>{result.title}</h2><div className={styles.passageBody}>{result.body}</div></div></section>
    <div className={styles.divider} role="separator" tabIndex={0} aria-label="Điều chỉnh độ rộng bài đọc và câu hỏi" aria-orientation="vertical" aria-valuemin={30} aria-valuemax={65} aria-valuenow={Math.round(width)} aria-controls="review-reading-panel" onPointerDown={e => { e.currentTarget.setPointerCapture(e.pointerId); resizePointer(e); }} onPointerMove={e => { if (e.currentTarget.hasPointerCapture(e.pointerId)) resizePointer(e); }} onKeyDown={resizeKey}><span aria-hidden="true">⋮</span></div>
    <section ref={questionsPane} aria-label="Kết quả từng câu" className={`${styles.pane} ${styles.questionsPane} ${tab === "reading" ? styles.mobileHidden : ""}`}><div className={styles.questionsContent}>{result.groups.map(group => <section className={styles.group} key={group.id}><h2>Questions {group.questions[0]?.number}{group.questions.length > 1 ? `–${group.questions[group.questions.length - 1].number}` : ""}</h2><p className={styles.instructions}>{group.instructions}</p>{group.candidates.length > 0 && <div className={styles.candidates}><strong>Danh sách lựa chọn</strong><ul>{group.candidates.map(c => <li key={c.label}><b>{c.label}.</b> {c.text}</li>)}</ul></div>}{group.questions.map(q => <article className={styles.question} id={`review-question-${q.number}`} key={q.id}><span className={styles.number}>{q.number}</span><div className={styles.questionBody}><p>{q.text}{q.afterBlank !== null && <> <strong>___</strong> {q.afterBlank}</>}</p>{q.choices.length > 0 && <ul className={resultStyles.choices}>{q.choices.map(c => <li key={c.label}>{group.variant === "MULTIPLE_CHOICE_SINGLE" ? `${c.label}. ` : ""}{c.text}</li>)}</ul>}<div className={`${resultStyles.answer} ${resultStyles[q.status]}`}><strong>{statusNames[q.status]}</strong><div>Câu trả lời của bạn: {q.submittedAnswer ?? "Chưa trả lời"}</div><div>Đáp án: {q.acceptedAnswers.join(" / ")}</div></div><details className={resultStyles.explanation}><summary>Giải thích</summary><p>{q.explanation || "Chưa có giải thích."}</p></details></div></article>)}</section>)}</div></section>
  </div><footer className={styles.questionNav} aria-label="Điều hướng câu hỏi"><span>Câu hỏi</span><div>{questions.map(q => <button key={q.id} className={resultStyles[`nav${q.status}`]} onClick={() => { setTab("questions"); setTarget(q.number); }} aria-label={`Đi đến câu ${q.number}, ${statusNames[q.status]}`}>{q.number}</button>)}</div></footer></main>;
  return <main className={resultStyles.page}><Link to="/reading">← Danh sách passage</Link><header className={resultStyles.heading}><p>Passage {result.partNumber}</p><h1>Kết quả làm bài</h1><span>{result.title}</span></header><section className={resultStyles.summary} aria-label="Tổng kết kết quả"><div className={resultStyles.ring} style={{ background: `conic-gradient(var(--success) 0 ${correctEnd}%, var(--danger) ${correctEnd}% ${incorrectEnd}%, var(--border) ${incorrectEnd}% 100%)` }}><div><strong>{result.correct}/{result.total}</strong><span>câu đúng</span></div></div><div className={resultStyles.totals}><p><span>Đúng</span><strong>{result.correct} câu</strong></p><p><span>Sai</span><strong>{result.incorrect} câu</strong></p><p><span>Bỏ qua</span><strong>{result.skipped} câu</strong></p><p><span>Tỉ lệ đúng</span><strong>{Number(result.accuracy.toFixed(1))}%</strong></p></div></section><div className={resultStyles.actions}><button type="button" onClick={() => setReview(true)}>Xem lại bài</button><Link to="/reading">Về danh sách</Link></div><section className={resultStyles.breakdown}><h2>Bảng dữ liệu chi tiết</h2><div className={resultStyles.tableWrap}><table><thead><tr><th scope="col">Dạng câu hỏi</th><th scope="col">Tổng</th><th scope="col">Đúng</th><th scope="col">Sai</th><th scope="col">Bỏ qua</th></tr></thead><tbody>{result.breakdown.map(row => <tr key={row.family}><th scope="row">{familyNames[row.family] || row.family}</th><td>{row.total}</td><td>{row.correct}</td><td>{row.incorrect}</td><td>{row.skipped}</td></tr>)}</tbody></table></div></section></main>;
}
