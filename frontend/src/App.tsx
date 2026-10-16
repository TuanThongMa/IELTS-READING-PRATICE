import { Navigate, Route, Routes } from "react-router-dom";
import { FoundationPage } from "./features/foundation/FoundationPage";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<FoundationPage />} />
      <Route path="*" element={<Navigate replace to="/" />} />
    </Routes>
  );
}
