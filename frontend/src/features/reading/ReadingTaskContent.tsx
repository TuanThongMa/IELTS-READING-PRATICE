import type { ReactNode } from "react";
import styles from "./ReadingTaskContent.module.css";

export type TaskChoice = { label: string; text: string };
export type TaskPart = { text: string | null; questionIndex: number | null };
export type TaskTemplate = {
  version: number;
  kind: "TABLE" | "FLOW_CHART";
  caption: string | null;
  columns: string[] | null;
  rows: TaskPart[][][] | null;
  steps: TaskPart[][] | null;
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
  wordLimitMode?: string | null;
  candidates: TaskChoice[];
  presentationTemplate: TaskTemplate | null;
  questions: TaskQuestion[];
};

export function supportsTaskContent(variant: string) {
  return ["MULTIPLE_CHOICE_MULTIPLE", "TABLE_COMPLETION", "FLOW_CHART_COMPLETION", "SHORT_ANSWER"].includes(variant);
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
