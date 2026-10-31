import { useState, type DragEvent, type ReactNode } from "react";
import styles from "./ReadingTaskContent.module.css";

export type TaskChoice = { label: string; text: string };
export type TaskPart = { text: string | null; questionIndex: number | null; sourceText?: string | null };
export type TaskTemplate = {
  version: number;
  kind: "TABLE" | "FLOW_CHART" | "PARAGRAPH";
  caption: string | null;
  columns: string[] | null;
  rows: TaskPart[][][] | null;
  steps: TaskPart[][] | null;
  paragraphs: TaskPart[][] | null;
};
export type TaskQuestion = {
  id: number;
  number: number;
  text: string;
  afterBlank: string | null;
  choices: TaskChoice[];
  status?: "CORRECT" | "INCORRECT" | "SKIPPED";
  submittedAnswer?: string | null;
  correctAnswer?: string;
  acceptedAnswers?: string[];
  explanation?: string | null;
};
export type TaskGroup = {
  id: number;
  variant: string;
  answerInputMode: string;
  instructions: string;
  candidateReuse: boolean;
  wordLimitMode?: string | null;
  candidates: TaskChoice[];
  presentationTemplate: TaskTemplate | null;
  questions: TaskQuestion[];
};

export function supportsTaskContent(variant: string, template?: TaskTemplate | null) {
  return ["MULTIPLE_CHOICE_MULTIPLE", "TABLE_COMPLETION", "FLOW_CHART_COMPLETION", "SHORT_ANSWER"].includes(variant)
    || template?.kind === "PARAGRAPH";
}

export function supportsMatchingInteraction(variant: string) {
  return variant.startsWith("MATCHING_");
}

export function MatchingQuestionInputs({ group, answers, onAnswer, questionIdPrefix = "question-" }: {
  group: TaskGroup;
  answers: Record<number, string>;
  onAnswer: (questionId: number, value: string) => void;
  questionIdPrefix?: string;
}) {
  const [selectedLabel, setSelectedLabel] = useState("");
  const [announcement, setAnnouncement] = useState("");
  const [dragOverQuestionId, setDragOverQuestionId] = useState<number | null>(null);
  const candidateByLabel = new Map(group.candidates.map(candidate => [candidate.label, candidate]));
  const assignedLabels = new Set(group.questions.map(question => answers[question.id]).filter(Boolean));
  const isAvailable = (label: string, questionId?: number) => group.candidateReuse
    || !assignedLabels.has(label)
    || group.questions.some(question => question.id === questionId && answers[questionId] === label);

  function assign(questionId: number, label: string) {
    const candidate = candidateByLabel.get(label);
    if (!candidate || !isAvailable(label, questionId)) {
      setAnnouncement("Lựa chọn này đã được gán cho câu khác.");
      return;
    }
    if (answers[questionId] === label) {
      setSelectedLabel("");
      return;
    }
    onAnswer(questionId, label);
    const question = group.questions.find(item => item.id === questionId);
    setSelectedLabel("");
    setAnnouncement(`Câu ${question?.number} đã chọn ${candidate.label}: ${candidate.text}`);
  }

  function clearAnswer(questionId: number) {
    if (answers[questionId]) onAnswer(questionId, "");
    const question = group.questions.find(item => item.id === questionId);
    setAnnouncement(`Đã xóa đáp án câu ${question?.number}.`);
  }

  function onDrop(event: DragEvent<HTMLButtonElement>, questionId: number) {
    event.preventDefault();
    const label = event.dataTransfer.getData("text/plain") || selectedLabel;
    assign(questionId, label);
  }

  return <div className={styles.matching}>
    <p className={styles.matchingHint}><span className={styles.matchingDesktopHint}>Trên máy tính, kéo lựa chọn vào ô hoặc chọn lựa chọn rồi bấm ô câu hỏi.</span><span className={styles.matchingMobileHint}>Chọn đáp án phù hợp cho từng câu.</span></p>
    <div className={styles.matchingRows}>
      {group.questions.map(question => {
        const answer = candidateByLabel.get(answers[question.id] || "");
        return <article className={styles.matchingRow} id={`${questionIdPrefix}${question.number}`} key={question.id}>
          <p className={styles.matchingPrompt}>{question.text}</p>
          <div className={styles.matchingAnswer}>
            <b className={styles.matchingNumber}>{question.number}</b>
            <button type="button" className={`${styles.matchingSlot} ${answer ? styles.matchingAssigned : ""} ${dragOverQuestionId === question.id ? styles.matchingDropTarget : ""}`}
              aria-label={`Câu ${question.number}: ${answer ? `${answer.label}, ${answer.text}` : selectedLabel ? "gán lựa chọn đang chọn" : "chưa trả lời; chọn để gán lựa chọn"}`}
              onClick={() => selectedLabel ? assign(question.id, selectedLabel) : setAnnouncement(`Chọn một lựa chọn bên dưới cho câu ${question.number}.`)}
              onDragOver={event => {
                const label = event.dataTransfer.getData("text/plain") || selectedLabel;
                if (label && isAvailable(label, question.id)) { event.preventDefault(); setDragOverQuestionId(question.id); }
              }}
              onDragLeave={() => setDragOverQuestionId(null)}
              onDrop={event => { onDrop(event, question.id); setDragOverQuestionId(null); }}>
              {answer ? <><b>{answer.label}</b><span>{answer.text}</span></> : <span>{selectedLabel ? "Bấm để gán lựa chọn đang chọn" : "Chọn đáp án"}</span>}
            </button>
            {answer && <button type="button" className={styles.matchingClear} aria-label={`Xóa đáp án câu ${question.number}`}
              onClick={() => clearAnswer(question.id)}>
              Xóa
            </button>}
            <select className={styles.matchingMobileSelect} aria-label={`Đáp án câu ${question.number}`} value={answers[question.id] || ""}
              onChange={event => event.target.value ? assign(question.id, event.target.value) : clearAnswer(question.id)}>
              <option value="">Chọn đáp án</option>
              {group.candidates.map(candidate => <option key={candidate.label} value={candidate.label} disabled={!isAvailable(candidate.label, question.id)}>{candidate.label} — {candidate.text}</option>)}
            </select>
          </div>
        </article>;
      })}
    </div>
    <div className={styles.matchingBank} aria-label="Ngân hàng đáp án">
      <h3>Lựa chọn</h3>
      <div className={styles.matchingChoices}>
        {group.candidates.map(candidate => {
          const disabled = !isAvailable(candidate.label);
          return <button type="button" key={candidate.label} draggable={!disabled}
            className={`${styles.matchingChoice} ${selectedLabel === candidate.label ? styles.matchingChoiceSelected : ""} ${disabled ? styles.matchingChoiceUsed : ""}`}
            aria-pressed={selectedLabel === candidate.label} aria-label={`${candidate.label}: ${candidate.text}${disabled ? ", đã được gán" : ""}`}
            disabled={disabled}
            onClick={() => {
              setSelectedLabel(current => current === candidate.label ? "" : candidate.label);
              setAnnouncement(selectedLabel === candidate.label ? "Đã bỏ chọn đáp án." : `Đã chọn ${candidate.label}: ${candidate.text}. Bây giờ chọn ô câu hỏi.`);
            }}
            onDragStart={event => { event.dataTransfer.setData("text/plain", candidate.label); event.dataTransfer.effectAllowed = "copy"; setSelectedLabel(candidate.label); }}
            onDragEnd={() => { setSelectedLabel(""); setDragOverQuestionId(null); }}>
            <span className={styles.matchingChoiceLabel}>{candidate.label}</span><span>{candidate.text}</span>{disabled && <small>Đã nối</small>}
          </button>;
        })}
      </div>
      {group.candidateReuse && <p className={styles.matchingReuse}>Một lựa chọn có thể được sử dụng nhiều lần.</p>}
    </div>
    <span className={styles.matchingSrOnly} role="status" aria-live="polite">{announcement}</span>
  </div>;
}

function ReviewAnswer({ question, showExpected = true }: { question: TaskQuestion; showExpected?: boolean }) {
  const status = question.status ?? "SKIPPED";
  return <span className={`${styles.reviewAnswer} ${styles[status.toLowerCase()]}`}>
    <b>{question.number}</b>
    <span>{question.submittedAnswer || "Chưa trả lời"}</span>
    <small>{status === "CORRECT" ? "Đúng" : status === "INCORRECT" ? "Sai" : "Bỏ qua"}{showExpected && <> · Đáp án: {question.acceptedAnswers?.join(" / ") || question.correctAnswer || "—"}</>}</small>
  </span>;
}

function renderParts(parts: TaskPart[], renderBlank: (questionIndex: number) => ReactNode) {
  return parts.map((part, index) => part.questionIndex !== null
    ? <span key={`question-${part.questionIndex}-${index}`}>{renderBlank(part.questionIndex)}</span>
    : <span key={`text-${index}`}>{part.text}</span>);
}

export function TaskQuestionInputs({ group, answers, errors, onAnswer, questionIdPrefix = "question-" }: {
  group: TaskGroup;
  answers: Record<number, string>;
  errors: Record<number, string>;
  onAnswer: (questionId: number, value: string) => void;
  questionIdPrefix?: string;
}) {
  const wordLimit = group.wordLimitMode ? ({
    ONE_WORD: "ONE WORD ONLY",
    TWO_WORDS: "NO MORE THAN TWO WORDS",
    TWO_WORDS_AND_ONE_NUMBER: "NO MORE THAN TWO WORDS AND/OR A NUMBER",
    THREE_WORDS_AND_ONE_NUMBER: "NO MORE THAN THREE WORDS AND/OR A NUMBER",
  } as Record<string, string>)[group.wordLimitMode] : null;

  if (group.variant === "MULTIPLE_CHOICE_MULTIPLE") {
    const choices = group.questions[0]?.choices ?? [];
    return <div className={styles.multipleChoice}>
      <ul className={styles.choiceBank}>{choices.map(choice => <li key={choice.label}><b>{choice.label}.</b> {choice.text}</li>)}</ul>
      <div className={styles.answerSlots}>{group.questions.map(question => {
        const usedElsewhere = new Set(group.questions.filter(other => other.id !== question.id).map(other => answers[other.id]).filter(Boolean));
        return <label className={styles.answerSlot} id={`${questionIdPrefix}${question.number}`} key={question.id}>
          <b>{question.number}</b>
          <select aria-label={`Đáp án câu ${question.number}`} value={answers[question.id] || ""} onChange={event => onAnswer(question.id, event.target.value)}>
            <option value="">Chọn chữ cái</option>
            {choices.map(choice => <option key={choice.label} value={choice.label} disabled={usedElsewhere.has(choice.label)}>{choice.label}</option>)}
          </select>
        </label>;
      })}</div>
      <p className={styles.hint}>Mỗi ô là một câu hỏi. Các đáp án có thể được điền theo thứ tự bất kỳ.</p>
    </div>;
  }

  if (group.variant === "SHORT_ANSWER") return <div className={styles.shortAnswers}>
    {wordLimit && <p className={styles.hint}>Answer using {wordLimit}.</p>}
    {group.questions.map(question => <article className={styles.shortQuestion} key={question.id}>
          <b className={styles.questionNumber}>{question.number}</b>
      <div><p>{question.text}</p><input aria-label={`Câu trả lời ${question.number}`} value={answers[question.id] || ""} onChange={event => onAnswer(question.id, event.target.value)} aria-invalid={!!errors[question.id]} />
        {errors[question.id] && <span className={styles.error} role="alert">{errors[question.id]}</span>}</div>
    </article>)}
  </div>;

  const template = group.presentationTemplate;
  if (!template) return <p className={styles.error} role="alert">Bố cục của nhóm câu hỏi chưa tải được.</p>;
  const renderBlank = (questionIndex: number) => {
    const question = group.questions[questionIndex];
    if (!question) return null;
    return <span className={styles.blankWrap} id={`${questionIdPrefix}${question.number}`} key={question.id}>
      <b className={styles.questionNumber}>{question.number}</b>
      <input aria-label={`Câu trả lời ${question.number}`} value={answers[question.id] || ""} onChange={event => onAnswer(question.id, event.target.value)} aria-invalid={!!errors[question.id]} />
      {errors[question.id] && <small className={styles.error} role="alert">{errors[question.id]}</small>}
    </span>;
  };
  const renderParagraphBlank = (questionIndex: number) => {
    const question = group.questions[questionIndex];
    if (!question) return null;
    const error = errors[question.id];
    return <span className={styles.paragraphBlankWrap} id={`${questionIdPrefix}${question.number}`} key={question.id}>
      <span className={styles.paragraphBlank}>
        <b className={styles.paragraphNumber}>{question.number}</b>
        <input aria-label={`Câu trả lời ${question.number}`} value={answers[question.id] || ""} onChange={event => onAnswer(question.id, event.target.value)} aria-invalid={!!error} />
      </span>
      {error && <small className={styles.error} role="alert">{error}</small>}
    </span>;
  };
  if (template.kind === "PARAGRAPH" && template.paragraphs) return <div className={styles.paragraphCompletion}>
    {wordLimit && <p className={styles.hint}>Answer using {wordLimit}.</p>}
    {template.paragraphs.map((paragraph, index) => <p key={index}>{renderParts(paragraph, renderParagraphBlank)}</p>)}
  </div>;
  if (template.kind === "TABLE" && template.columns && template.rows) return <div className={styles.tableScroll}>
    {wordLimit && <p className={styles.hint}>Answer using {wordLimit}.</p>}
    <table className={styles.completionTable}>
      {template.caption && <caption>{template.caption}</caption>}
      <thead><tr>{template.columns.map((column, index) => <th key={index} scope="col">{column}</th>)}</tr></thead>
      <tbody>{template.rows.map((row, rowIndex) => <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex}>{renderParts(cell, renderBlank)}</td>)}</tr>)}</tbody>
    </table>
  </div>;
  if (template.kind === "FLOW_CHART" && template.steps) return <>
    {wordLimit && <p className={styles.hint}>Answer using {wordLimit}.</p>}
    <ol className={styles.flowChart}>
    {template.steps.map((step, index) => <li key={index}><div className={styles.flowStep}>{renderParts(step, renderBlank)}</div>{index < template.steps!.length - 1 && <span className={styles.flowArrow} aria-hidden="true">↓</span>}</li>)}
    </ol>
  </>;
  return <p className={styles.error} role="alert">Kiểu bố cục câu hỏi không được hỗ trợ.</p>;
}

export function TaskQuestionReview({ group, questionIdPrefix = "review-question-" }: { group: Pick<TaskGroup, "variant" | "questions" | "presentationTemplate" | "wordLimitMode">; questionIdPrefix?: string }) {
  const wordLimit = group.wordLimitMode ? ({ ONE_WORD: "ONE WORD ONLY", TWO_WORDS: "NO MORE THAN TWO WORDS", TWO_WORDS_AND_ONE_NUMBER: "NO MORE THAN TWO WORDS AND/OR A NUMBER", THREE_WORDS_AND_ONE_NUMBER: "NO MORE THAN THREE WORDS AND/OR A NUMBER" } as Record<string, string>)[group.wordLimitMode] : null;
  if (group.variant === "MULTIPLE_CHOICE_MULTIPLE") return <div className={styles.multipleChoice}>
    <ul className={styles.choiceBank}>{(group.questions[0]?.choices ?? []).map(choice => <li key={choice.label}><b>{choice.label}.</b> {choice.text}</li>)}</ul>
    <p className={styles.hint}>Đáp án đúng của nhóm: <strong>{group.questions[0]?.acceptedAnswers?.join(", ") || "—"}</strong>. Mỗi ô bên dưới là câu trả lời người học đã chọn.</p>
    <div className={styles.answerSlots}>{group.questions.map(question => <span id={`${questionIdPrefix}${question.number}`} key={question.id}><ReviewAnswer question={question} showExpected={false} /></span>)}</div>
  </div>;

  if (group.variant === "SHORT_ANSWER") return <div className={styles.shortAnswers}>
    {wordLimit && <p className={styles.hint}>Answer using {wordLimit}.</p>}
    {group.questions.map(question => <article className={styles.shortQuestion} key={question.id}>
      <b className={styles.questionNumber} id={`${questionIdPrefix}${question.number}`}>{question.number}</b>
      <div><p>{question.text}</p><ReviewAnswer question={question} />
        {question.explanation && <details className={styles.explanation}><summary>Giải thích</summary><p>{question.explanation}</p></details>}</div>
    </article>)}
  </div>;

  const template = group.presentationTemplate;
  if (!template) return <p className={styles.error}>Bố cục của nhóm câu hỏi chưa tải được.</p>;
  const renderBlank = (questionIndex: number) => {
    const question = group.questions[questionIndex];
    return question ? <span id={`${questionIdPrefix}${question.number}`} key={question.id}><ReviewAnswer question={question} /></span> : null;
  };
  const renderParagraphReviewBlank = (questionIndex: number) => {
    const question = group.questions[questionIndex];
    if (!question) return null;
    const status = question.status ?? "SKIPPED";
    const label = status === "CORRECT" ? "Đúng" : status === "INCORRECT" ? "Sai" : "Bỏ qua";
    const expected = question.acceptedAnswers?.join(" / ") || question.correctAnswer || "—";
    return <span id={`${questionIdPrefix}${question.number}`} className={`${styles.paragraphReviewBlank} ${styles[`paragraphReview${status[0] + status.slice(1).toLowerCase()}`]}`} key={question.id}
      aria-label={`Câu ${question.number}: ${label}; câu trả lời ${question.submittedAnswer || "chưa trả lời"}; đáp án ${expected}`}>
      <b className={styles.paragraphNumber}>{question.number}</b><span>{question.submittedAnswer || "—"}</span><small>{label}</small>
    </span>;
  };
  if (template.kind === "PARAGRAPH" && template.paragraphs) return <div className={styles.paragraphCompletion}>
    {wordLimit && <p className={styles.hint}>Answer using {wordLimit}.</p>}
    {template.paragraphs.map((paragraph, index) => <p key={index}>{renderParts(paragraph, renderParagraphReviewBlank)}</p>)}
    {group.questions.map(question => <details className={styles.explanation} key={question.id}>
      <summary>Câu {question.number} · Đáp án: {question.acceptedAnswers?.join(" / ") || question.correctAnswer || "—"}</summary>
      <p>{question.explanation || "Chưa có giải thích."}</p>
    </details>)}
  </div>;
  if (template.kind === "TABLE" && template.columns && template.rows) return <div className={styles.tableScroll}>
    {wordLimit && <p className={styles.hint}>Answer using {wordLimit}.</p>}
    <table className={styles.completionTable}>
      {template.caption && <caption>{template.caption}</caption>}
      <thead><tr>{template.columns.map((column, index) => <th key={index} scope="col">{column}</th>)}</tr></thead>
      <tbody>{template.rows.map((row, rowIndex) => <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex}>{renderParts(cell, renderBlank)}</td>)}</tr>)}</tbody>
    </table>
    {group.questions.map(question => question.explanation && <details className={styles.explanation} key={question.id}><summary>Câu {question.number} · Giải thích</summary><p>{question.explanation}</p></details>)}
  </div>;
  if (template.kind === "FLOW_CHART" && template.steps) return <>
    {wordLimit && <p className={styles.hint}>Answer using {wordLimit}.</p>}
    <ol className={styles.flowChart}>{template.steps.map((step, index) => <li key={index}><div className={styles.flowStep}>{renderParts(step, renderBlank)}</div>{index < template.steps!.length - 1 && <span className={styles.flowArrow} aria-hidden="true">↓</span>}</li>)}</ol>
    {group.questions.map(question => question.explanation && <details className={styles.explanation} key={question.id}><summary>Câu {question.number} · Giải thích</summary><p>{question.explanation}</p></details>)}
  </>;
  return <p className={styles.error}>Kiểu bố cục câu hỏi không được hỗ trợ.</p>;
}
