import { useState, useEffect } from 'react';
import { habitsAPI } from '../../api/habits';
import HabitCard from './HabitCard';
import HabitForm from './HabitForm';

const HabitList = () => {
  const [habits, setHabits] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editingHabit, setEditingHabit] = useState(null);
  const [showForm, setShowForm] = useState(false);

  // Fetch habits on mount
  useEffect(() => {
    fetchHabits();
  }, []);

  const fetchHabits = async () => {
    try {
      setLoading(true);
      const data = await habitsAPI.getAll();
      setHabits(data);
      setError('');
    } catch (err) {
      setError('Failed to load habits');
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = async (habitData) => {
    const newHabit = await habitsAPI.create(habitData);
    setHabits([newHabit, ...habits]);
    setShowForm(false);
  };

  const handleUpdate = async (habitData) => {
    const updated = await habitsAPI.update(editingHabit.id, habitData);
    setHabits(habits.map(h => h.id === updated.id ? updated : h));
    setEditingHabit(null);
  };

  const handleDelete = async (id) => {
    await habitsAPI.delete(id);
    setHabits(habits.filter(h => h.id !== id));
  };

  const handleToggleComplete = async (id, isCompleted) => {
    let updated;
    if (isCompleted) {
      updated = await habitsAPI.unmarkComplete(id);
    } else {
      updated = await habitsAPI.markComplete(id);
    }
    setHabits(habits.map(h => h.id === updated.id ? updated : h));
  };

  if (loading) {
    return <div className="loading">Loading habits...</div>;
  }

  return (
    <div className="habit-list-container">
      <div className="habit-list-header">
        <h2>My Habits</h2>
        <button 
          className="add-habit-btn"
          onClick={() => setShowForm(!showForm)}
        >
          {showForm ? 'Cancel' : '+ Add Habit'}
        </button>
      </div>

      {error && <div className="error-message">{error}</div>}

      {showForm && (
        <HabitForm 
          onSubmit={handleCreate}
          onCancel={() => setShowForm(false)}
        />
      )}

      {editingHabit && (
        <div className="modal-overlay">
          <div className="modal">
            <HabitForm 
              habit={editingHabit}
              onSubmit={handleUpdate}
              onCancel={() => setEditingHabit(null)}
            />
          </div>
        </div>
      )}

      {habits.length === 0 ? (
        <div className="empty-state">
          <p>No habits yet. Create your first habit to get started!</p>
        </div>
      ) : (
        <div className="habits-grid">
          {habits.map(habit => (
            <HabitCard
              key={habit.id}
              habit={habit}
              onToggleComplete={handleToggleComplete}
              onDelete={handleDelete}
              onEdit={setEditingHabit}
            />
          ))}
        </div>
      )}
    </div>
  );
};

export default HabitList;