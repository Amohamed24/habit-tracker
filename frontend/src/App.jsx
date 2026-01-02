import { useSelector } from 'react-redux';
import AuthPage from './pages/AuthPage';
import Dashboard from './pages/dashboard/page';
import './App.css';

function App() {
  const { isAuthenticated, loading } = useSelector((state) => state.auth);

  if (loading) {
    return <div className="loading-screen">Loading...</div>;
  }

  return isAuthenticated ? <Dashboard /> : <AuthPage />;
}

export default App;