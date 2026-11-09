import { useEffect, useRef, useState } from "react";
import { GlobalWorkerOptions, getDocument, type PDFDocumentProxy } from "pdfjs-dist";
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import styles from "./PdfImports.module.css";

GlobalWorkerOptions.workerSrc = workerUrl;

export function PdfPageViewer({ jobId, pageNumber, zoom }: { jobId: number; pageNumber: number; zoom: number }) {
  const viewportRef = useRef<HTMLDivElement>(null);
  const canvasHolderRef = useRef<HTMLDivElement>(null);
  const [pdf, setPdf] = useState<PDFDocumentProxy | null>(null);
  const [width, setWidth] = useState(700);
  const [error, setError] = useState("");
  const [rendering, setRendering] = useState(true);

  useEffect(() => {
    const element = viewportRef.current;
    if (!element) return;
    const observer = new ResizeObserver(() => setWidth(element.clientWidth));
    observer.observe(element);
    setWidth(element.clientWidth);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    let cancelled = false;
    let loadingTask: ReturnType<typeof getDocument> | null = null;
    const controller = new AbortController();
    setPdf(null);
    canvasHolderRef.current?.replaceChildren();
    setError("");
    setRendering(true);
    const load = async () => {
      try {
        const response = await fetch(`/api/admin/pdf-imports/${jobId}/source`, { credentials: "same-origin", signal: controller.signal });
        if (!response.ok) throw new Error("Không thể tải PDF gốc.");
        const bytes = new Uint8Array(await response.arrayBuffer());
        if (cancelled) return;
        loadingTask = getDocument({ data: bytes });
        const loaded = await loadingTask.promise;
        if (!cancelled) setPdf(loaded);
      } catch {
        if (!cancelled) { setError("Không thể mở PDF gốc. Hãy thử tải lại trang."); setRendering(false); }
      }
    };
    void load();
    return () => { cancelled = true; controller.abort(); if (loadingTask) void loadingTask.destroy(); };
  }, [jobId]);

  useEffect(() => {
    if (!pdf || !canvasHolderRef.current) return;
    let cancelled = false;
    let task: { cancel: () => void } | null = null;
    setRendering(true);
    const render = async () => {
      try {
        const page = await pdf.getPage(pageNumber);
        if (cancelled) return;
        const base = page.getViewport({ scale: 1 });
        const scale = Math.max(0.3, (Math.max(width - 32, 280) / base.width) * zoom);
        const viewport = page.getViewport({ scale });
        const pixelRatio = window.devicePixelRatio || 1;
        const canvas = document.createElement("canvas");
        canvas.setAttribute("aria-label", `PDF gốc, trang ${pageNumber}`);
        canvas.className = styles.pdfCanvas;
        canvas.width = Math.floor(viewport.width * pixelRatio);
        canvas.height = Math.floor(viewport.height * pixelRatio);
        canvas.style.width = `${viewport.width}px`;
        canvas.style.height = `${viewport.height}px`;
        const context = canvas.getContext("2d");
        if (!context) throw new Error("Canvas unavailable");
        context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
        const renderTask = page.render({ canvas, canvasContext: context, viewport });
        task = renderTask;
        await renderTask.promise;
        if (!cancelled) { canvasHolderRef.current?.replaceChildren(canvas); setError(""); setRendering(false); }
      } catch (cause) {
        if (!cancelled && !(cause instanceof Error && cause.name === "RenderingCancelledException")) {
          setError("Không thể hiển thị trang PDF này.");
          setRendering(false);
        }
      }
    };
    void render();
    return () => { cancelled = true; task?.cancel(); };
  }, [pdf, pageNumber, width, zoom]);

  return <div ref={viewportRef} className={styles.pdfViewport}>
    {rendering && !error && <p className={styles.pdfLoading}>Đang hiển thị trang {pageNumber}...</p>}
    {error && <p className={styles.error} role="alert">{error}</p>}
    <div ref={canvasHolderRef} style={{ display: error ? "none" : "block" }} />
  </div>;
}
