import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useAuth } from './context/AuthContext.jsx';
import AppLayout from './components/layout/AppLayout.jsx';
import Spinner from './components/ui/Spinner.jsx';
import LoginPage from './pages/LoginPage.jsx';
import RegisterPage from './pages/RegisterPage.jsx';
import DashboardPage from './pages/DashboardPage.jsx';
import MarketsPage from './pages/MarketsPage.jsx';
import WatchlistPage from './pages/WatchlistPage.jsx';
import PortfolioPage from './pages/PortfolioPage.jsx';
import OrdersPage from './pages/OrdersPage.jsx';
import TradesPage from './pages/TradesPage.jsx';
import RiskPage from './pages/RiskPage.jsx';

function RequireAuth({ children }) {
  const { isAuthenticated, initializing } = useAuth();
  const location = useLocation();

  if (initializing) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Spinner label="Restoring your session…" />
      </div>
    );
  }
  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return children;
}

function RedirectIfAuthed({ children }) {
  const { isAuthenticated, initializing } = useAuth();
  if (initializing) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Spinner label="Loading…" />
      </div>
    );
  }
  if (isAuthenticated) {
    return <Navigate to="/" replace />;
  }
  return children;
}

export default function App() {
  return (
    <Routes>
      <Route
        path="/login"
        element={
          <RedirectIfAuthed>
            <LoginPage />
          </RedirectIfAuthed>
        }
      />
      <Route
        path="/register"
        element={
          <RedirectIfAuthed>
            <RegisterPage />
          </RedirectIfAuthed>
        }
      />
      <Route
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="/markets" element={<MarketsPage />} />
        <Route path="/watchlist" element={<WatchlistPage />} />
        <Route path="/portfolio" element={<PortfolioPage />} />
        <Route path="/orders" element={<OrdersPage />} />
        <Route path="/trades" element={<TradesPage />} />
        <Route path="/risk" element={<RiskPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
