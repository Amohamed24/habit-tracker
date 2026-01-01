import { useAuth } from '../../context/AuthContext';
import HabitList from '../../components/habits/HabitList';

const Dashboard = () => {
  const { user, logout } = useAuth();

  return (
    <div className="dashboard">
      <header className="dashboard-header">
        <h1>HabitFlow</h1>
        <div className="user-info">
          <span>{user?.email}</span>
          <button onClick={logout} className="logout-btn">
            Logout
          </button>
        </div>
      </header>
      
      <main className="dashboard-content">
        <HabitList />
      </main>
    </div>
  );
};

export default Dashboard;