import { useEffect, useRef, useState, type KeyboardEvent, type MouseEvent, type PointerEvent, type TouchEvent } from "react";
import { Link, useBlocker, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { api } from "../../api";
import { AudioButton } from "../../components/AudioButton";
import { AppHeader, type AccountUser } from "../../components/AppHeader";
import { answerErrorsFor, answerWordLimitError, type WordLimitMode } from "./answerTextPolicy";
import { supportsTaskContent, TaskQuestionInputs, TaskQuestionReview, type TaskTemplate } from "./ReadingTaskContent";
import styles from "./ReadingPage.module.css";

type ReadingMode = "PRACTICE" | "REAL_TEST";
type PendingStart = { kind: "passage" | "test"; id: number; title: string };
type Summary = { id: number; title: string; partNumber: number; questionCount: number; questionTypes: string[]; inProgress: boolean; attemptMode: ReadingMode | null; answeredCount: number; latestSubmittedAttemptId: number | null };
type TestSummary = { id: number; title: string; questionCount: number; inProgress: boolean; attemptMode: ReadingMode | null; elapsedSeconds: number; answeredCount: number; latestSubmittedAttemptId: number | null };
type Choice = { label: string; text: string };
type Question = { id: number; number: number; text: string; afterBlank: string | null; choices: Choice[] };
type Group = { id: number; variant: string; answerInputMode: string; instructions: string; wordLimitMode: WordLimitMode; candidateReuse: boolean; candidates: Choice[]; presentationTemplate: TaskTemplate | null; questions: Question[] };
type Passage = { id: number; title: string; body: string; partNumber: number; groups: Group[] };
type Attempt = { id: number; passageId: number; status: string; mode: ReadingMode; elapsedSeconds: number; answers: { questionId: number; value: string }[] };
type Answers = Record<number, string>;
type SaveState = "saved" | "saving" | "failed";
type Highlight = { id: number; startOffset: number; endOffset: number; selectedText: string; prefixContext: string; suffixContext: string };
type TextSelectionMenu = { start: number; end: number; text: string; context: string; left: number; top: number; width: number; maxHeight: number };
type LookupSense = { id: number; partOfSpeech: string; phoneticText: string | null; audioUrl: string | null; definition: string; example: string | null; relatedWords: { word: string; relationType: string }[] };
type LookupEntry = { id: number; word: string; sourceUrl: string; senses: LookupSense[] };
type VocabularyCategory = { id: number; name: string; noteCount: number };

export function PassageText({ body, passageId, allowDictionary = true }: { body: string; passageId: number; allowDictionary?: boolean }) {
  const root = useRef<HTMLDivElement>(null);
  const selectionRange = useRef<Range | null>(null);
  const [highlights, setHighlights] = useState<Highlight[]>([]);
  const [menu, setMenu] = useState<TextSelectionMenu | null>(null);
  const [entries, setEntries] = useState<LookupEntry[]>([]);
  const [lookupState, setLookupState] = useState<"idle" | "loading" | "ready" | "error">("idle");
  const [message, setMessage] = useState("");
  const [senseId, setSenseId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [highlightMode, setHighlightMode] = useState(false);
  const [lookupMode, setLookupMode] = useState(false);
  const [categories, setCategories] = useState<VocabularyCategory[]>([]);
  const [categoryIds, setCategoryIds] = useState<number[]>([]);
  const [categoriesLoading, setCategoriesLoading] = useState(false);
  const [categoryError, setCategoryError] = useState("");
  const [categoryPickerOpen, setCategoryPickerOpen] = useState(false);
  const [noteSaved, setNoteSaved] = useState(false);

  useEffect(() => {
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      const key = event.key.toLowerCase();
      if ((key !== "h" && (key !== "t" || !allowDictionary)) || event.repeat || event.altKey || event.ctrlKey || event.metaKey || document.querySelector("dialog[open]")) return;
      const target = event.target;
      if (target instanceof HTMLElement && (target.isContentEditable || target.closest("input, textarea, select, [role='textbox']"))) return;
      event.preventDefault();
      if (key === "h") setHighlightMode(current => { const next = !current; if (next) setLookupMode(false); return next; });
      else setLookupMode(current => { const next = !current; if (next) setHighlightMode(false); return next; });
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [allowDictionary]);

  useEffect(() => {
    let active = true;
    api(`/api/reading/passages/${passageId}/highlights`).then(async response => {
      if (!response.ok) throw new Error();
      const result = await response.json() as Highlight[];
      if (active) setHighlights(result);
    }).catch(() => { if (active) setMessage("Chưa tải được highlight đã lưu."); });
    return () => { active = false; };
  }, [passageId]);

  useEffect(() => {
    if (!menu) return;
    const pane = root.current?.closest(`.${styles.pane}`);
    const reposition = () => {
      const rect = selectionRange.current?.getBoundingClientRect();
      if (!rect || (!rect.width && !rect.height)) return;
      setMenu(current => current ? { ...current, ...selectionToolsPosition(rect) } : current);
    };
    pane?.addEventListener("scroll", reposition);
    window.addEventListener("resize", reposition);
    return () => {
      pane?.removeEventListener("scroll", reposition);
      window.removeEventListener("resize", reposition);
    };
  }, [menu?.start, menu?.end]);

  function locateSelection(event: MouseEvent<HTMLDivElement> | TouchEvent<HTMLDivElement>) {
    const selection = window.getSelection();
    const container = root.current;
    if (!selection || selection.isCollapsed || !container || !container.contains(selection.anchorNode) || !container.contains(selection.focusNode)) return;
    const range = selection.getRangeAt(0);
    const beforeStart = document.createRange(); beforeStart.selectNodeContents(container); beforeStart.setEnd(range.startContainer, range.startOffset);
    const beforeEnd = document.createRange(); beforeEnd.selectNodeContents(container); beforeEnd.setEnd(range.endContainer, range.endOffset);
    let start = beforeStart.toString().length;
    let end = beforeEnd.toString().length;
    const raw = body.slice(start, end);
    if (!raw.trim() || raw.includes("\n") || raw.includes("\r")) return;
    const leading = raw.length - raw.trimStart().length;
    const trailing = raw.length - raw.trimEnd().length;
    start += leading; end -= trailing;
    const selectedText = body.slice(start, end);
    if (!selectedText || selectedText.length > 2000) return;
    const lineStart = body.lastIndexOf("\n", start - 1) + 1;
    const foundEnd = body.indexOf("\n", end);
    const lineEnd = foundEnd < 0 ? body.length : foundEnd;
    const context = body.slice(lineStart, lineEnd).trim();
    const rect = range.getBoundingClientRect();
    selectionRange.current = range.cloneRange();
    const position = selectionToolsPosition(rect);
    const selected: TextSelectionMenu = { start, end, text: selectedText, context, ...position };
    setMenu(selected);
    setEntries([]); setLookupState("idle"); setMessage(""); setSenseId(null);
    setCategoryIds([]); setCategoryError(""); setCategoryPickerOpen(false); setNoteSaved(false);
    if (highlightMode) void saveHighlight(selected);
    else if (lookupMode && allowDictionary) void lookup(selected);
    if ("preventDefault" in event && event.type === "touchend") event.preventDefault();
  }

  async function saveHighlight(selection = menu) {
    if (!selection) return;
    setSaving(true); setMessage("");
    try {
      const response = await api(`/api/reading/passages/${passageId}/highlights`, "POST", { startOffset: selection.start, endOffset: selection.end, selectedText: selection.text });
      if (!response.ok) { setMessage(response.status === 409 ? "Vùng này chồng lên highlight khác hoặc không được phép trong full test." : "Không lưu được highlight. Hãy chọn lại đoạn văn."); return; }
      const saved = await response.json() as Highlight;
      setHighlights(current => [...current.filter(item => item.id !== saved.id), saved].sort((a, b) => a.startOffset - b.startOffset));
      setMessage("Đã lưu highlight cho passage này.");
      window.getSelection()?.removeAllRanges();
    } catch { setMessage("Không thể kết nối tới máy chủ."); }
    finally { setSaving(false); }
  }

  async function removeHighlight(highlight: Highlight) {
    try {
      const response = await api(`/api/reading/passages/${passageId}/highlights/${highlight.id}`, "DELETE");
      if (!response.ok) throw new Error();
      setHighlights(current => current.filter(item => item.id !== highlight.id));
    } catch { setMessage("Chưa xóa được highlight."); }
  }

  async function lookup(selection = menu) {
    if (!selection) return;
    setLookupState("loading"); setEntries([]); setMessage("");
    try {
      const response = await api(`/api/vocabulary/lookup?word=${encodeURIComponent(selection.text)}`);
      if (!response.ok) {
        let detail = "";
        try { const body = await response.json() as { detail?: string; message?: string }; detail = body.detail ?? body.message ?? ""; } catch { /* response không có JSON */ }
        setLookupState("error"); setMessage(detail || (response.status === 404 ? "Không tìm thấy từ này trong từ điển." : response.status === 503 ? "Từ điển chưa được cấu hình hoặc đang tạm thời không khả dụng." : "Không tra cứu được từ lúc này.")); return;
      }
      const result = await response.json() as LookupEntry[];
      setEntries(result); setSenseId(result[0]?.senses[0]?.id ?? null); setLookupState("ready");
    } catch { setLookupState("error"); setMessage("Không thể kết nối tới máy chủ."); }
  }

  async function saveVocabularyNote() {
    if (!menu || !senseId || !categoryIds.length) return;
    setSaving(true); setMessage("");
    try {
      const response = await api("/api/vocabulary-notes", "POST", { senseId, selectedText: menu.text, contextSentence: menu.context, passageId, categoryIds });
      if (!response.ok) { setMessage("Không lưu được từ vào sổ. Hãy thử lại."); return; }
      setCategoryPickerOpen(false);
      setNoteSaved(true);
      setMessage(`Đã lưu vào ${categoryIds.length} danh mục.`);
    } catch { setMessage("Không thể kết nối tới máy chủ."); }
    finally { setSaving(false); }
  }

  async function openCategoryPicker() {
    if (!senseId || noteSaved) return;
    setCategoryPickerOpen(true);
    setCategoriesLoading(true);
    setCategoryError("");
    setCategoryIds([]);
    try {
      const response = await api("/api/vocabulary/categories");
      if (!response.ok) throw new Error();
      setCategories(await response.json() as VocabularyCategory[]);
    } catch { setCategoryError("Chưa tải được danh mục. Hãy thử mở lại."); }
    finally { setCategoriesLoading(false); }
  }

  const segments: { text: string; highlight?: Highlight }[] = [];
  let cursor = 0;
  for (const highlight of [...highlights].sort((a, b) => a.startOffset - b.startOffset)) {
    if (highlight.startOffset < cursor || highlight.endOffset > body.length || body.slice(highlight.startOffset, highlight.endOffset) !== highlight.selectedText) continue;
    if (highlight.startOffset > cursor) segments.push({ text: body.slice(cursor, highlight.startOffset) });
    segments.push({ text: highlight.selectedText, highlight }); cursor = highlight.endOffset;
  }
  if (cursor < body.length) segments.push({ text: body.slice(cursor) });

  const selectedSense = entries.flatMap(entry => entry.senses).find(sense => sense.id === senseId);
  return <>
    <div className={styles.passageLayout}>
      <aside className={styles.toolsRail} aria-label="Công cụ bài đọc">
        <h3 className={styles.toolsHeading}>Công cụ</h3>
        <button type="button" className={`${styles.highlightToggle} ${highlightMode ? styles.highlightActive : ""}`} aria-pressed={highlightMode} onClick={() => setHighlightMode(current => { const next = !current; if (next) setLookupMode(false); return next; })}>
          <svg aria-hidden="true" viewBox="0 0 24 24"><path d="m14.1 4.2 5.7 5.7-9.6 9.6H4.5v-5.7l9.6-9.6Zm1.4-1.4 1.4-1.4a2 2 0 0 1 2.8 0l2.9 2.9a2 2 0 0 1 0 2.8l-1.4 1.4-5.7-5.7ZM3 21h18v-1.8H3V21Z" /></svg>
          <strong>Highlight</strong><span>Phím (H)</span>
        </button>
        {allowDictionary && <button type="button" className={`${styles.lookupToggle} ${lookupMode ? styles.lookupActive : ""}`} aria-pressed={lookupMode} onClick={() => setLookupMode(current => { const next = !current; if (next) setHighlightMode(false); return next; })}>
          <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M10.8 4.5a6.3 6.3 0 1 0 3.9 11.2l4.2 4.2 1.3-1.3-4.2-4.2a6.3 6.3 0 0 0-5.2-9.9Zm0 1.8a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9Z" /></svg>
          <strong>Tra từ</strong><span>Phím (T)</span>
        </button>}
      </aside>
      <div ref={root} className={styles.passageBody} onMouseUp={locateSelection} onTouchEnd={locateSelection}>{segments.map((segment, index) => segment.highlight
        ? <mark className={styles.userHighlight} key={`h-${segment.highlight.id}`} title="Chọn để bỏ highlight" onClick={() => void removeHighlight(segment.highlight!)}>{segment.text}</mark>
        : <span key={`t-${index}`}>{segment.text}</span>)}</div>
    </div>
    {menu && <div className={styles.selectionTools} style={{ left: menu.left, top: menu.top, width: menu.width, maxHeight: menu.maxHeight }} onMouseDown={event => event.preventDefault()} role="dialog" aria-label="Thao tác với văn bản đã chọn">
      <div className={styles.selectionQuote}>“{menu.text}”</div><div className={styles.selectionActions}><button type="button" onClick={() => void saveHighlight()} disabled={saving}>Highlight</button>{allowDictionary && <button type="button" onClick={() => void lookup()} disabled={lookupState === "loading"}>{lookupState === "loading" ? "Đang tra..." : "Tra từ"}</button>}<button type="button" aria-label="Đóng" onClick={() => { setMenu(null); window.getSelection()?.removeAllRanges(); }}>×</button></div>
      {lookupState === "error" && <p role="status" className={styles.selectionMessage}>{message}</p>}
      {entries.length > 0 && <div className={styles.lookupResults}>{entries.flatMap(entry => entry.senses.map(sense => <div key={sense.id} className={styles.lookupSense}>
        <input id={`reading-sense-${sense.id}`} type="radio" name="reading-sense" checked={senseId === sense.id} onChange={() => { setSenseId(sense.id); setCategoryPickerOpen(false); setCategoryIds([]); setNoteSaved(false); setMessage(""); }} />
        <div className={styles.lookupSenseText}>
          <label className={styles.lookupWord} htmlFor={`reading-sense-${sense.id}`}><b>{entry.word}</b></label>
          <div className={styles.lookupPronunciation}><label htmlFor={`reading-sense-${sense.id}`}><small>{sense.partOfSpeech}{sense.phoneticText ? ` · ${sense.phoneticText}` : ""}</small></label>{sense.audioUrl && <AudioButton source={sense.audioUrl} word={entry.word} />}</div>
          <label className={styles.lookupMeaning} htmlFor={`reading-sense-${sense.id}`}><span>{sense.definition}</span>{sense.example && <i>“{sense.example}”</i>}{sense.relatedWords.length > 0 && <small>Từ liên quan: {sense.relatedWords.map(word => word.word).join(", ")}</small>}</label>
        </div>
      </div>))}
        <button className={styles.saveVocabulary} type="button" disabled={!senseId || saving || noteSaved} onClick={() => void openCategoryPicker()}>{noteSaved ? "Đã lưu từ vựng" : "Lưu vào danh mục"}</button>
      </div>}
      {categoryPickerOpen && <section className={styles.categoryPicker} aria-label="Chọn danh mục lưu từ">
        <h3>Lưu “{selectedSense ? entries.find(entry => entry.senses.some(sense => sense.id === senseId))?.word : menu.text}” với nghĩa đã chọn</h3>
        {categoriesLoading ? <p className={styles.selectionMessage}>Đang tải danh mục...</p> : categoryError ? <p className={styles.selectionMessage} role="alert">{categoryError}</p> : categories.length === 0 ? <p className={styles.selectionMessage}>Bạn chưa có danh mục. Hãy tạo danh mục trong <Link to="/vocabulary">Sổ từ vựng</Link> rồi quay lại.</p> : <fieldset className={styles.categoryOptions}><legend>Chọn một hoặc nhiều danh mục</legend>{categories.map(category => <label key={category.id}><input type="checkbox" checked={categoryIds.includes(category.id)} onChange={event => setCategoryIds(current => event.target.checked ? [...current, category.id] : current.filter(id => id !== category.id))} /><span>{category.name}</span></label>)}</fieldset>}
        <div className={styles.categoryActions}><button type="button" onClick={() => setCategoryPickerOpen(false)}>Quay lại</button><button className={styles.saveVocabulary} type="button" disabled={categoriesLoading || categories.length === 0 || categoryIds.length === 0 || saving} onClick={() => void saveVocabularyNote()}>{saving ? "Đang lưu..." : "Lưu từ"}</button></div>
      </section>}
      {message && lookupState !== "error" && <p role="status" className={styles.selectionMessage}>{message}</p>}
    </div>}
  </>;
}

function selectionToolsPosition(rect: DOMRect): Pick<TextSelectionMenu, "left" | "top" | "width" | "maxHeight"> {
  const gap = 12;
  const maxWidth = Math.max(180, Math.min(380, window.innerWidth - gap * 2));
  const maxHeight = Math.min(window.innerHeight * 0.72, 560);
  const rightSpace = window.innerWidth - rect.right - gap;
  const leftSpace = rect.left - gap;
  const sideWidth = Math.min(maxWidth, Math.max(rightSpace, leftSpace));

  if (sideWidth >= Math.min(260, maxWidth)) {
    const useRight = rightSpace >= maxWidth || (rightSpace >= Math.min(260, maxWidth) && rightSpace >= leftSpace);
    const width = useRight ? Math.min(maxWidth, rightSpace) : Math.min(maxWidth, leftSpace);
    const left = useRight ? rect.right + gap : rect.left - gap - width;
    return { left, top: Math.max(gap, Math.min(window.innerHeight - maxHeight - gap, rect.top)), width, maxHeight };
  }

  const belowSpace = window.innerHeight - rect.bottom - gap * 2;
  const aboveSpace = rect.top - gap * 2;
  if (belowSpace >= 140 || belowSpace >= aboveSpace) {
    const height = Math.max(80, Math.min(maxHeight, belowSpace));
    return {
      left: Math.max(gap, Math.min(window.innerWidth - maxWidth - gap, rect.left + rect.width / 2 - maxWidth / 2)),
      top: rect.bottom + gap,
      width: maxWidth,
      maxHeight: height,
    };
  }
  const height = Math.max(80, Math.min(maxHeight, aboveSpace));
  return {
    left: Math.max(gap, Math.min(window.innerWidth - maxWidth - gap, rect.left + rect.width / 2 - maxWidth / 2)),
    top: Math.max(gap, rect.top - height - gap),
    width: maxWidth,
    maxHeight: height,
  };
}

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

export function ReadingLibrary({ user, onLogout }: { user: AccountUser | null; onLogout: () => void | Promise<void> }) {
  const authenticated = Boolean(user);
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const [items, setItems] = useState<Summary[]>([]);
  const [tests, setTests] = useState<TestSummary[]>([]);
  const [status, setStatus] = useState("loading");
  const [contentType, setContentType] = useState<"passage" | "test">(() => searchParams.get("type") === "test" ? "test" : "passage");
  const [partNumber, setPartNumber] = useState<number | null>(null);
  const [pendingStart, setPendingStart] = useState<PendingStart | null>(null);
  useEffect(() => { let active = true; Promise.all([api("/api/reading/passages"), api("/api/reading/tests")]).then(async ([passages, fullTests]) => { if (!passages.ok || !fullTests.ok) throw new Error(); if (active) { setItems(await passages.json() as Summary[]); setTests(await fullTests.json() as TestSummary[]); setStatus("ready"); } }).catch(() => { if (active) setStatus("error"); }); return () => { active = false; }; }, []);
  useEffect(() => {
    if (!pendingStart) return;
    const closeOnEscape = (event: globalThis.KeyboardEvent) => { if (event.key === "Escape") setPendingStart(null); };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [pendingStart]);
  function openPassage(item: Summary) {
    if (!authenticated) { navigate("/login"); return; }
    if (item.inProgress) { navigate(`/reading/passages/${item.id}?mode=${item.attemptMode ?? "PRACTICE"}`); return; }
    setPendingStart({ kind: "passage", id: item.id, title: item.title });
  }
  function openTest(test: TestSummary) {
    if (!authenticated) { navigate("/login"); return; }
    if (test.inProgress) { navigate(`/reading/tests/${test.id}?mode=${test.attemptMode ?? "PRACTICE"}`); return; }
    setPendingStart({ kind: "test", id: test.id, title: test.title });
  }
  function startSelectedMode(mode: ReadingMode) {
    if (!pendingStart) return;
    const path = pendingStart.kind === "passage" ? `/reading/passages/${pendingStart.id}` : `/reading/tests/${pendingStart.id}`;
    setPendingStart(null);
    navigate(`${path}?mode=${mode}`);
  }
  const visiblePassages = items.filter(item => partNumber === null || item.partNumber === partNumber);
  const visibleCount = contentType === "passage" ? visiblePassages.length : tests.length;
  return <div className={styles.libraryPage}>
    <AppHeader active="reading" user={user} onLogout={onLogout} />
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
              {authenticated && item.inProgress && <p className={styles.progressText}>{item.attemptMode === "REAL_TEST" ? "Thi thật" : "Luyện tập"} · {item.answeredCount}/{item.questionCount} câu đã trả lời</p>}
              <div className={styles.libraryCardActions}>{authenticated ? <button className={styles.startButton} type="button" onClick={() => openPassage(item)}>{item.inProgress ? "Tiếp tục" : "Làm bài"}</button> : <Link className={styles.startLink} to="/login">Đăng nhập để làm bài</Link>}{authenticated && <Link className={styles.cardHistoryLink} to={`/reading/history?passageId=${item.id}`}>Lịch sử</Link>}</div>
            </div>
          </article>)}</div> : tests.length === 0 ? <p className={styles.listState}>Chưa có full test được xuất bản.</p> : <div className={styles.libraryGrid}>{tests.map(test => <article className={styles.libraryCard} key={test.id}>
            <div className={styles.cardCover} data-part="test"><span>FULL TEST</span><span aria-hidden="true">60′</span></div>
            <div className={styles.libraryCardBody}><h3 title={test.title}>{test.title}</h3><p className={styles.cardMeta}>60 phút · {test.questionCount} câu hỏi</p>
              {authenticated && test.inProgress && <p className={styles.progressText}>{test.attemptMode === "REAL_TEST" ? "Thi thật" : "Luyện tập"} · {test.answeredCount}/{test.questionCount} câu đã trả lời</p>}
              <div className={styles.libraryCardActions}>{authenticated ? <button className={styles.startButton} type="button" onClick={() => openTest(test)}>{test.inProgress ? "Tiếp tục" : "Làm full test"}</button> : <Link className={styles.startLink} to="/login">Đăng nhập để làm bài</Link>}</div>
            </div>
          </article>)}</div>}
        </section>
      </div>
    </main>
    {pendingStart && <div className={styles.modeOverlay} role="presentation" onMouseDown={event => { if (event.target === event.currentTarget) setPendingStart(null); }}>
      <section className={styles.modeDialog} role="dialog" aria-modal="true" aria-labelledby="mode-title" aria-describedby="mode-description">
        <header><div><p className={styles.eyebrow}>BẮT ĐẦU READING</p><h2 id="mode-title">Chọn chế độ làm bài</h2></div><button type="button" aria-label="Đóng" onClick={() => setPendingStart(null)}>×</button></header>
        <p id="mode-description" className={styles.modeDescription}>{pendingStart.title}</p>
        <div className={styles.modeChoices}>
          <button type="button" className={styles.modeChoice} onClick={() => startSelectedMode("PRACTICE")}>
            <strong>Luyện tập</strong><span>Tra cứu từ, lưu vào danh mục và dùng Highlight như bình thường.</span>
          </button>
          <button type="button" className={styles.modeChoice} onClick={() => startSelectedMode("REAL_TEST")}>
            <strong>Thi thật</strong><span>Tập trung làm bài; chỉ còn Highlight, không tra cứu hay lưu từ.</span>
          </button>
        </div>
        <footer><button type="button" onClick={() => setPendingStart(null)}>Hủy</button></footer>
      </section>
    </div>}
  </div>;
}

export function ReadingPassage() {
  const { id } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const [passage, setPassage] = useState<Passage | null>(null);
  const [attempt, setAttempt] = useState<Attempt | null>(null);
  const [status, setStatus] = useState("loading");
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [answers, setAnswers] = useState<Answers>({});
  const [answerErrors, setAnswerErrors] = useState<Record<number, string>>({});
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [clockSaveError, setClockSaveError] = useState(false);
  const [saveState, setSaveState] = useState<SaveState>("saved");
  const [width, setWidth] = useState(50);
  const [tab, setTab] = useState<"reading" | "questions">("reading");
  const [target, setTarget] = useState<number | null>(null);
  const [activeQuestionNumber, setActiveQuestionNumber] = useState<number | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [exitConfirmOpen, setExitConfirmOpen] = useState(false);
  const [exitSaveError, setExitSaveError] = useState(false);
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
  const leaving = useRef(false);
  const exitApproved = useRef(false);
  const mounted = useRef(true);
  const exitDialog = useRef<HTMLDialogElement>(null);
  const blocker = useBlocker(({ currentLocation, nextLocation }) => currentLocation.pathname !== nextLocation.pathname && (saveState !== "saved" || (attempt?.mode === "REAL_TEST" && status === "ready" && !submitted.current && !exitApproved.current)));

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; if (timer.current) clearTimeout(timer.current); }; }, []);
  useEffect(() => {
    let active = true;
    setStatus("loading");
    setPassage(null);
    setAttempt(null);
    setActiveQuestionNumber(null);
    (async () => {
      try {
        const response = await api(`/api/reading/passages/${id}`);
        if (!response.ok) { if (active) setStatus(response.status === 404 ? "missing" : "error"); return; }
        const data = await response.json() as Passage;
        const requestedMode: ReadingMode = searchParams.get("mode") === "REAL_TEST" ? "REAL_TEST" : "PRACTICE";
        const start = await api(`/api/reading/passages/${id}/attempts`, "POST", { mode: requestedMode });
        if (!start.ok) throw new Error();
        const existing = await start.json() as Attempt;
        if (!active) return;
        submitted.current = false; leaving.current = false; exitApproved.current = false;
        const initial = Object.fromEntries(existing.answers.map(a => [a.questionId, a.value])) as Answers;
        values.current = initial; acknowledged.current = { ...initial }; elapsedBase.current = existing.elapsedSeconds; startedAt.current = Date.now(); setElapsedSeconds(existing.elapsedSeconds); setClockSaveError(false); setAnswers(initial); setAnswerErrors(answerErrorsFor(data.groups, initial)); setAttempt(existing); setPassage(data); setSaveState("saved"); setStatus("ready");
      } catch { if (active) setStatus("error"); }
    })();
    return () => { active = false; };
  }, [id, loadAttempt, searchParams]);
  useEffect(() => {
    if (!attempt || status !== "ready") return;
    const tick = setInterval(() => setElapsedSeconds(elapsedBase.current + Math.floor((Date.now() - startedAt.current) / 1000)), 1000);
    const checkpoint = setInterval(() => { void saveClock(); }, 15_000);
    const onVisibilityChange = () => { void saveClock(); };
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => { clearInterval(tick); clearInterval(checkpoint); document.removeEventListener("visibilitychange", onVisibilityChange); if (!submitted.current && !leaving.current) void saveClock(); };
  }, [attempt, status]);
  useEffect(() => {
    if (target === null || tab !== "questions") return;
    const pane = questionsPane.current;
    const question = pane?.querySelector<HTMLElement>(`#question-${target}`);
    if (pane && question) pane.scrollBy({ top: question.getBoundingClientRect().top - pane.getBoundingClientRect().top - 16 });
    setTarget(null);
  }, [target, tab]);
  useEffect(() => {
    if (blocker.state !== "blocked") return;
    if (attempt?.mode === "REAL_TEST") setExitConfirmOpen(true);
    else if (window.confirm("Một số câu trả lời chưa được lưu. Bạn vẫn muốn rời trang?")) blocker.proceed();
    else blocker.reset();
  }, [blocker, attempt?.mode]);
  useEffect(() => { if (saveState === "saved" && attempt?.mode !== "REAL_TEST") return; const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; }; window.addEventListener("beforeunload", warn); return () => window.removeEventListener("beforeunload", warn); }, [saveState, attempt?.mode]);
  useEffect(() => { if (confirmOpen && dialog.current && !dialog.current.open) dialog.current.showModal(); }, [confirmOpen]);
  useEffect(() => { if (exitConfirmOpen && exitDialog.current && !exitDialog.current.open) exitDialog.current.showModal(); else if (!exitConfirmOpen && exitDialog.current?.open) exitDialog.current.close(); }, [exitConfirmOpen]);

  async function flush() {
    if (!attempt || running.current) return false;
    const validation = passage ? answerErrorsFor(passage.groups, values.current) : {};
    setAnswerErrors(validation);
    if (Object.keys(validation).length > 0) { if (mounted.current) setSaveState("failed"); return false; }
    running.current = true;
    try {
      while (true) {
        const questionId = Object.keys(values.current).find(key => (values.current[Number(key)] || "") !== (acknowledged.current[Number(key)] || ""));
        if (!questionId) { if (mounted.current) setSaveState("saved"); return true; }
        const value = values.current[Number(questionId)] || "";
        const response = await api(`/api/reading/attempts/${attempt.id}/answers/${questionId}`, "PUT", { value });
        if (!response.ok) throw new Error();
        acknowledged.current[Number(questionId)] = value;
      }
    } catch { if (mounted.current) setSaveState("failed"); return false; }
    finally { running.current = false; }
  }
  function currentElapsed() { return elapsedBase.current + Math.floor((Date.now() - startedAt.current) / 1000); }
  async function saveClock() {
    if (!attempt || clockSaving.current) return false;
    clockSaving.current = true;
    try {
      const response = await api(`/api/reading/attempts/${attempt.id}/heartbeat`, "PUT", { elapsedSeconds: currentElapsed() });
      if (!response.ok) throw new Error();
      if (mounted.current) setClockSaveError(false);
      return true;
    } catch { if (mounted.current) setClockSaveError(true); return false; }
    finally { clockSaving.current = false; }
  }
  async function leaveRealTest(force = false) {
    if (!attempt || attempt.mode !== "REAL_TEST") return;
    setExitSaveError(false);
    if (timer.current) clearTimeout(timer.current);
    while (running.current) await new Promise(resolve => window.setTimeout(resolve, 25));
    await flush();
    while (running.current) await new Promise(resolve => window.setTimeout(resolve, 25));
    const answersSaved = !Object.keys(values.current).some(key => (values.current[Number(key)] || "") !== (acknowledged.current[Number(key)] || ""));
    let paused = false;
    try { const response = await api(`/api/reading/attempts/${attempt.id}/pause`, "PUT", { elapsedSeconds: currentElapsed() }); paused = response.ok; } catch { paused = false; }
    if ((!answersSaved || !paused) && !force) { setExitSaveError(true); return; }
    leaving.current = true;
    exitApproved.current = true;
    setExitConfirmOpen(false);
    if (blocker.state === "blocked") blocker.proceed();
    else navigate("/reading");
  }
  function updateAnswer(questionId: number, value: string) {
    const group = passage?.groups.find(item => item.questions.some(question => question.id === questionId));
    const validation = answerWordLimitError(value, group?.wordLimitMode ?? null);
    setAnswerErrors(current => { const next = { ...current }; if (validation) next[questionId] = validation; else delete next[questionId]; return next; });
    values.current = { ...values.current, [questionId]: value };
    setAnswers(values.current);
    if (timer.current) clearTimeout(timer.current);
    if (validation) { setSaveState("failed"); return; }
    setSaveState("saving");
    timer.current = setTimeout(() => { void flush(); }, 500);
  }
  function retry() { setSaveState("saving"); void flush(); }
  async function submit() {
    if (!attempt || saveState !== "saved" || Object.keys(answerErrors).length > 0 || submitting) return;
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
  return <main className={styles.reader}><header className={styles.readerHeader}>{attempt.mode === "REAL_TEST" ? <button type="button" className={styles.exitButton} onClick={() => { setExitSaveError(false); setExitConfirmOpen(true); }}>Thoát thi</button> : <Link to="/reading">← Danh sách</Link>}<h1>{passage.title}</h1>{attempt.mode === "REAL_TEST" && <span className={styles.modeBadge}>Thi thật</span>}<span className={styles.elapsedTime} role="timer" aria-label="Thời gian làm bài">{formatElapsed(elapsedSeconds)}</span><span className={styles.preview} role="status" aria-live="polite">{saveState === "saving" ? "Đang lưu..." : saveState === "failed" ? <>Chưa lưu được. <button type="button" className={styles.retry} onClick={retry}>Thử lưu lại</button></> : "Đã lưu"}</span></header><div className={styles.mobileTabs} role="tablist" aria-label="Khu vực bài đọc"><button role="tab" aria-selected={tab === "reading"} onClick={() => setTab("reading")}>Bài đọc</button><button role="tab" aria-selected={tab === "questions"} onClick={() => setTab("questions")}>Câu hỏi</button></div><div className={styles.workspace} ref={workspace} style={{ gridTemplateColumns: `minmax(0, ${width}fr) 12px minmax(0, ${100 - width}fr)` }}>
    <section id="reading-panel" aria-label="Bài đọc" className={`${styles.pane} ${styles.readingPane} ${tab === "questions" ? styles.mobileHidden : ""}`}><div className={styles.readingContent}><p className={styles.passageLabel}>Reading passage {passage.partNumber}</p><h2>{passage.title}</h2><PassageText body={passage.body} passageId={passage.id} allowDictionary={attempt.mode !== "REAL_TEST"} /></div></section>
    <div className={styles.divider} role="separator" tabIndex={0} aria-label="Điều chỉnh độ rộng bài đọc và câu hỏi" aria-orientation="vertical" aria-valuemin={30} aria-valuemax={65} aria-valuenow={Math.round(width)} aria-controls="reading-panel" onPointerDown={e => { e.currentTarget.setPointerCapture(e.pointerId); setFromPointer(e); }} onPointerMove={e => { if (e.currentTarget.hasPointerCapture(e.pointerId)) setFromPointer(e); }} onKeyDown={resizeKey}><span aria-hidden="true">⋮</span></div>
    <section ref={questionsPane} aria-label="Câu hỏi" className={`${styles.pane} ${styles.questionsPane} ${tab === "reading" ? styles.mobileHidden : ""}`}><div className={styles.questionsContent}><p className={styles.previewNote}>Câu trả lời được lưu tự động. Đồng hồ vẫn chạy khi bạn chuyển tab và thời lượng được lưu định kỳ mỗi 15 giây.</p>{clockSaveError && <p className={styles.clockWarning} role="alert">Chưa lưu được thời gian gần nhất. Hệ thống sẽ thử lại ở mốc tiếp theo.</p>}{passage.groups.map(group => <section className={styles.group} key={group.id}>
  <h2>Questions {group.questions[0]?.number}{group.questions.length > 1 ? `–${group.questions[group.questions.length - 1].number}` : ""}</h2>
  <p className={styles.instructions}>{group.instructions}</p>
  {supportsTaskContent(group.variant)
    ? <TaskQuestionInputs group={group} answers={answers} errors={answerErrors} onAnswer={updateAnswer} />
    : <>
      {group.candidates.length > 0 && <div className={styles.candidates}><strong>Danh sách lựa chọn</strong><ul>{group.candidates.map(c => <li key={c.label}><b>{c.label}.</b> {c.text}</li>)}</ul>{group.candidateReuse && <p>Một lựa chọn có thể được sử dụng nhiều lần.</p>}</div>}
      {group.questions.map(q => { const answerError = answerErrors[q.id]; return <article className={styles.question} id={`question-${q.number}`} key={q.id}><span className={styles.number}>{q.number}</span><div className={styles.questionBody}><p>{q.text}{group.answerInputMode === "TEXT_ENTRY" && <> <input aria-label={`Câu trả lời ${q.number}`} aria-invalid={!!answerError} aria-describedby={answerError ? `answer-error-${q.id}` : undefined} className={styles.blank} placeholder="Đáp án" maxLength={1000} value={answers[q.id] || ""} onChange={e => updateAnswer(q.id, e.target.value)} /> {q.afterBlank}{answerError && <span id={`answer-error-${q.id}`} className={styles.answerError} role="alert">{answerError}</span>}</>}</p>{group.candidates.length > 0 ? <select aria-label={`Đáp án câu ${q.number}`} value={answers[q.id] || ""} onChange={e => updateAnswer(q.id, e.target.value)}><option value="">Chọn đáp án</option>{group.candidates.map(c => <option key={c.label} value={c.label}>{c.label} — {c.text}</option>)}</select> : q.choices.length > 0 && <fieldset><legend className={styles.srOnly}>Lựa chọn câu {q.number}</legend>{q.choices.map(c => <label className={styles.option} key={c.label}><input type="radio" name={`question-${q.id}`} checked={answers[q.id] === c.label} onChange={() => updateAnswer(q.id, c.label)} /> <span>{group.variant === "MULTIPLE_CHOICE_SINGLE" ? `${c.label}. ` : ""}{c.text}</span></label>)}{answers[q.id] && <button type="button" className={styles.clearAnswer} onClick={() => updateAnswer(q.id, "")}>Bỏ chọn</button>}</fieldset>}</div></article>; })}
    </>}
</section>)}</div></section>
  </div><footer className={styles.questionNav} aria-label="Điều hướng câu hỏi"><span>Câu hỏi</span><div>{questions.map(q => { const isActive = activeQuestionNumber === q.number; return <button key={q.id} className={`${(answers[q.id] || "").trim() ? styles.answered : ""} ${isActive ? styles.activeQuestion : ""}`} onClick={() => { setActiveQuestionNumber(q.number); setTab("questions"); setTarget(q.number); }} aria-current={isActive ? "true" : undefined} aria-label={`Đi đến câu ${q.number}, ${(answers[q.id] || "").trim() ? "đã trả lời" : "chưa trả lời"}${isActive ? ", đang chọn" : ""}`}>{q.number}</button>; })}</div><span>Đã trả lời: {answered}/{questions.length}</span><button type="button" className={styles.submitButton} onClick={() => { setSubmitError(false); setConfirmOpen(true); }}>Nộp bài</button></footer>
    {confirmOpen && <dialog ref={dialog} className={styles.submitDialog} aria-labelledby="submit-title" onCancel={e => { if (submitting) e.preventDefault(); else setConfirmOpen(false); }}><h2 id="submit-title">Xác nhận nộp bài</h2><p>Bạn đã trả lời <strong>{answered}/{questions.length}</strong> câu.</p><p>{questions.length - answered > 0 ? `Còn ${questions.length - answered} câu chưa trả lời. Bạn vẫn có thể nộp bài.` : "Bạn đã trả lời tất cả câu hỏi."}</p><p>Sau khi nộp, bạn không thể thay đổi câu trả lời của lượt làm này.</p>{Object.keys(answerErrors).length > 0 && <p className={styles.answerError} role="alert">Hãy sửa các đáp án vượt giới hạn từ trước khi nộp.</p>}{saveState !== "saved" && <p role="status">{saveState === "saving" ? "Đang lưu câu trả lời, vui lòng chờ." : <>Có câu trả lời chưa được lưu. <button type="button" className={styles.retry} onClick={retry}>Thử lưu lại</button></>}</p>}{submitError && <p role="alert">Chưa xác nhận được việc nộp bài. Hãy kiểm tra kết nối và thử lại.</p>}<div className={styles.dialogActions}><button type="button" disabled={submitting} onClick={() => setConfirmOpen(false)}>Tiếp tục làm bài</button><button type="button" className={styles.submitButton} disabled={saveState !== "saved" || Object.keys(answerErrors).length > 0 || submitting} onClick={() => void submit()}>{submitting ? "Đang nộp..." : "Xác nhận nộp bài"}</button></div></dialog>}
    {attempt.mode === "REAL_TEST" && <dialog ref={exitDialog} className={styles.submitDialog} aria-labelledby="exit-title" onCancel={event => { event.preventDefault(); if (blocker.state === "blocked") blocker.reset(); setExitConfirmOpen(false); }}><h2 id="exit-title">Thoát chế độ thi thật?</h2><p>Lượt làm sẽ được lưu để bạn có thể tiếp tục sau. Đồng hồ sẽ dừng khi thoát và tiếp tục khi mở lại lượt làm.</p>{exitSaveError && <p role="alert">Chưa xác nhận được lưu đầy đủ câu trả lời hoặc thời gian. Hãy thử lại; nếu vẫn muốn thoát, có thể thoát dù chưa lưu dữ liệu mới nhất.</p>}<div className={styles.dialogActions}><button type="button" onClick={() => { if (blocker.state === "blocked") blocker.reset(); setExitConfirmOpen(false); }}>Ở lại làm bài</button><button type="button" className={styles.submitButton} onClick={() => void leaveRealTest()}>Thoát, tiếp tục sau</button>{exitSaveError && <button type="button" className={styles.forceExitButton} onClick={() => void leaveRealTest(true)}>Thoát dù chưa lưu</button>}</div></dialog>}
  </main>;
}
