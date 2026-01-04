import { lazy, Suspense } from 'react';
import { useSelector } from 'react-redux';
import ErrorBoundary from './components/ErrorBoundary';
import './App.css';

// Lazy load pages
const AuthPage = lazy(() => import('./pages/AuthPage'));
const Dashboard = lazy(() => import('./pages/dashboard/page'));

const LoadingScreen = () => (
  <div className="loading-screen">
    <div className="loading-spinner"></div>
    <p>Loading...</p>
  </div>
);

function App() {
  const { isAuthenticated } = useSelector((state) => state.auth);

  return (
    <ErrorBoundary>
      <Suspense fallback={<LoadingScreen />}>
        {isAuthenticated ? <Dashboard /> : <AuthPage />}
      </Suspense>
    </ErrorBoundary>
  );
}

export default App;