import { useState, useEffect, useCallback, useMemo } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import {
  fetchHabits,
  createHabit,
  updateHabit,
  deleteHabit,
  toggleHabitComplete,
} from '../../store/habitsSlice';
import HabitCard from './HabitCard';
import HabitForm from './HabitForm';
import useFocusTrap from '../../hooks/useFocasTrap';

const HabitList = () => {
  const dispatch = useDispatch();
  const { habits, loading, error } = useSelector((state) => state.habits);
  
  const [editingHabit, setEditingHabit] = useState(null);
  const [showAddForm, setShowAddForm] = useState(false);
  const [announcement, setAnnouncement] = useState('');

  useEffect(() => {
    dispatch(fetchHabits());
  }, [dispatch]);

  // Close modal on Escape key
  useEffect(() => {
    const handleEscape = (e) => {
      if (e.key === 'Escape') {
        if (showAddForm) setShowAddForm(false);
        if (editingHabit) setEditingHabit(null);
      }
    };

    document.addEventListener('keydown', handleEscape);
    return () => document.removeEventListener('keydown', handleEscape);
  }, [showAddForm, editingHabit]);

  const handleCreate = useCallback(async (habitData) => {
    await dispatch(createHabit(habitData)).unwrap();
    setShowAddForm(false);
    setAnnouncement(`Habit "${habitData.name}" created successfully`);
  }, [dispatch]);

  const handleUpdate = useCallback(async (habitData) => {
    await dispatch(updateHabit({ id: editingHabit.id, habitData })).unwrap();
    setEditingHabit(null);
  }, [dispatch, editingHabit?.id]);

  const handleDelete = useCallback(async (id) => {
    const habit = habits.find(h => h.id === id);
    await dispatch(deleteHabit(id)).unwrap();
    setAnnouncement(`Habit "${habit?.name}" deleted`);
  }, [dispatch, habits]);

  const handleToggleComplete = useCallback(async (id, isCompleted) => {
    const habit = habits.find(h => h.id === id);
    await dispatch(toggleHabitComplete({ id, isCompleted })).unwrap();
    setAnnouncement(
      isCompleted 
        ? `${habit?.name} marked as incomplete` 
        : `${habit?.name} completed`
    );
  }, [dispatch, habits]);

  // Calculate stats
  const completedToday = useMemo(
    () => habits.filter(h => h.completedToday).length,
    [habits]
  );
  const totalHabits = habits.length;

  const addModalRef = useFocusTrap(showAddForm);
  const editModalRef = useFocusTrap(!!editingHabit);

  if (loading && habits.length === 0) {
    return (
      <div className="loading-container">
        <div className="loading-spinner"></div>
        <p>Loading your habits...</p>
      </div>
    );
  }

  return (
    <div className="habit-list-container">
      {/* Progress Bar */}
      {totalHabits > 0 && (
        <div className="progress-section">
          <div className="progress-header">
            <span className="progress-label">Today's Progress</span>
            <span className="progress-count">{completedToday}/{totalHabits}</span>
          </div>
          <div 
            className="progress-bar"
            role="progressbar"
            aria-valuenow={completedToday}
            aria-valuemin={0}
            aria-valuemax={totalHabits}
            aria-label={`${completedToday} of ${totalHabits} habits completed today`}
          >
            <div 
              className="progress-fill" 
              style={{ width: `${(completedToday / totalHabits) * 100}%` }}
            ></div>
          </div>
        </div>
      )}

      {error && <div className="error-message">{error}</div>}

      {/* Habit Cards */}
      {habits.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M12 2C6.48 2 2 6.48 2 12C2 17.52 6.48 22 12 22C17.52 22 22 17.52 22 12C22 6.48 17.52 2 12 2ZM12 20C7.59 20 4 16.41 4 12C4 7.59 7.59 4 12 4C16.41 4 20 7.59 20 12C20 16.41 16.41 20 12 20Z" fill="currentColor"/>
              <path d="M12 6V12L16 14" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
            </svg>
          </div>
          <h2>No habits yet</h2>
          <p>Create your first habit to start building better routines!</p>
        </div>
      ) : (
        <div className="habits-list">
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

      {/* Edit Modal */}
      {editingHabit && (
        <div className="modal-overlay" onClick={() => setEditingHabit(null)}>
          <div 
            className="modal" 
            onClick={(e) => e.stopPropagation()}
            ref={editModalRef}
            role="dialog"
            aria-modal="true"
            aria-labelledby="edit-modal-title"
          >
            <div className="modal-header">
              <h2>Edit Habit</h2>
              <button 
                className="modal-close"
                onClick={() => setEditingHabit(null)}
                aria-label="Close"
              >
                <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M18 6L6 18M6 6L18 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                </svg>
              </button>
            </div>
            <HabitForm 
              habit={editingHabit}
              onSubmit={handleUpdate}
              onCancel={() => setEditingHabit(null)}
            />
          </div>
        </div>
      )}

      {/* Add Habit Modal */}
      {showAddForm && (
        <div className="modal-overlay" onClick={() => setShowAddForm(false)}>
          <div 
            className="modal" 
            onClick={(e) => e.stopPropagation()}
            ref={addModalRef}
            role="dialog"
            aria-modal="true"
            aria-labelledby="add-modal-title"
          >
            <div className="modal-header">
              <h2>Create New Habit</h2>
              <button 
                className="modal-close"
                onClick={() => setShowAddForm(false)}
                aria-label="Close"
              >
                <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M18 6L6 18M6 6L18 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                </svg>
              </button>
            </div>
            <HabitForm 
              onSubmit={handleCreate}
              onCancel={() => setShowAddForm(false)}
            />
          </div>
        </div>
      )}

      {/* Floating Action Button */}
      <button 
        className="fab"
        onClick={() => setShowAddForm(true)}
        aria-label="Add new habit"
      >
        <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M12 5V19M5 12H19" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"/>
        </svg>
      </button>
      
      <div 
        role="status" 
        aria-live="polite" 
        aria-atomic="true"
        className="sr-only"
      >
        {announcement}
      </div>
    </div>
  );
};

export default HabitList;