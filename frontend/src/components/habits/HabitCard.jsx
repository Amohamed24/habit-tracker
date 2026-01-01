import { useState } from 'react';

const HabitCard = ({ habit, onToggleComplete, onDelete, onEdit }) => {
  const [loading, setLoading] = useState(false);

  const handleToggle = async () => {
    setLoading(true);
    try {
      await onToggleComplete(habit.id, habit.completedToday);
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async () => {
    if (window.confirm('Are you sure you want to delete this habit?')) {
      await onDelete(habit.id);
    }
  };

  return (
    <div className={`habit-card ${habit.completedToday ? 'completed' : ''}`}>
      <div className="habit-info">
        <h3>{habit.name}</h3>
        {habit.description && <p>{habit.description}</p>}
        <div className="habit-stats">
          <span className="streak">🔥 {habit.currentStreak} day streak</span>
          <span className="total">✓ {habit.totalCompletions} total</span>
          <span className="frequency">{habit.targetFrequency.toLowerCase()}</span>
        </div>
      </div>
      
      <div className="habit-actions">
        <button
          className={`complete-btn ${habit.completedToday ? 'undo' : ''}`}
          onClick={handleToggle}
          disabled={loading}
        >
          {loading ? '...' : habit.completedToday ? 'Undo' : 'Complete'}
        </button>
        <button className="edit-btn" onClick={() => onEdit(habit)}>
          Edit
        </button>
        <button className="delete-btn" onClick={handleDelete}>
          Delete
        </button>
      </div>
    </div>
  );
};

export default HabitCard;