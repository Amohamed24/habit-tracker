import { useState, useEffect } from 'react';

const HabitForm = ({ habit, onSubmit, onCancel }) => {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [targetFrequency, setTargetFrequency] = useState('DAILY');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const isEditing = !!habit;

  useEffect(() => {
    if (habit) {
      setName(habit.name);
      setDescription(habit.description || '');
      setTargetFrequency(habit.targetFrequency);
    }
  }, [habit]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');

    if (!name.trim()) {
      setError('Habit name is required');
      return;
    }

    setLoading(true);

    try {
      await onSubmit({
        name: name.trim(),
        description: description.trim(),
        targetFrequency,
      });
      
      // Reset form if creating new habit
      if (!isEditing) {
        setName('');
        setDescription('');
        setTargetFrequency('DAILY');
      }
    } catch (err) {
      setError(err.message || 'Failed to save habit');
    } finally {
      setLoading(false);
    }
  };

  return (
    <form className="habit-form" onSubmit={handleSubmit}>
      <h3>{isEditing ? 'Edit Habit' : 'Create New Habit'}</h3>
      
      {error && <div className="error-message">{error}</div>}
      
      <div className="form-group">
        <label htmlFor="name">Habit Name *</label>
        <input
          type="text"
          id="name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="e.g., Exercise, Read, Meditate"
          required
          disabled={loading}
        />
      </div>
      
      <div className="form-group">
        <label htmlFor="description">Description (optional)</label>
        <textarea
          id="description"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="Add details about your habit..."
          rows={3}
          disabled={loading}
        />
      </div>
      
      <div className="form-group">
        <label htmlFor="frequency">Frequency</label>
        <select
          id="frequency"
          value={targetFrequency}
          onChange={(e) => setTargetFrequency(e.target.value)}
          disabled={loading}
        >
          <option value="DAILY">Daily</option>
          <option value="WEEKLY">Weekly</option>
        </select>
      </div>
      
      <div className="form-actions">
        <button type="submit" disabled={loading}>
          {loading ? 'Saving...' : isEditing ? 'Update' : 'Create'}
        </button>
        {onCancel && (
          <button type="button" onClick={onCancel} disabled={loading}>
            Cancel
          </button>
        )}
      </div>
    </form>
  );
};

export default HabitForm;