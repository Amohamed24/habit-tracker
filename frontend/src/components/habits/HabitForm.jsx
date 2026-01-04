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
      {error && <div className="form-error">{error}</div>}
      
      <div className="form-group">
        <label htmlFor="habit-name">
          Habit Name <span className="required">*</span>
        </label>
        <input
          type="text"
          id="habit-name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="e.g., Morning meditation"
          required
          disabled={loading}
          aria-required="true"
          aria-invalid={error && !name.trim() ? 'true' : 'false'}
        />
      </div>
      
      <div className="form-group">
        <label htmlFor="habit-description">Description <span className="optional">(optional)</span></label>
        <textarea
          id="habit-description"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="Add details about your habit..."
          rows={3}
          disabled={loading}
        />
      </div>
      
      <div className="form-group">
        <label>Frequency</label>
        <div className="frequency-options">
          <button
            type="button"
            className={`frequency-btn ${targetFrequency === 'DAILY' ? 'active' : ''}`}
            onClick={() => setTargetFrequency('DAILY')}
            disabled={loading}
          >
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <rect x="3" y="4" width="18" height="18" rx="2" stroke="currentColor" strokeWidth="2"/>
              <path d="M3 10H21" stroke="currentColor" strokeWidth="2"/>
              <path d="M8 2V6" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
              <path d="M16 2V6" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
            </svg>
            Daily
          </button>
          <button
            type="button"
            className={`frequency-btn ${targetFrequency === 'WEEKLY' ? 'active' : ''}`}
            onClick={() => setTargetFrequency('WEEKLY')}
            disabled={loading}
          >
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <rect x="3" y="4" width="18" height="18" rx="2" stroke="currentColor" strokeWidth="2"/>
              <path d="M3 10H21" stroke="currentColor" strokeWidth="2"/>
              <path d="M9 16H15" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
            </svg>
            Weekly
          </button>
        </div>
      </div>
      
      <div className="form-actions">
        {onCancel && (
          <button type="button" className="btn-secondary" onClick={onCancel} disabled={loading}>
            Cancel
          </button>
        )}
        <button type="submit" className="btn-primary" disabled={loading}>
          {loading ? (
            <>
              <span className="btn-spinner"></span>
              {isEditing ? 'Saving...' : 'Creating...'}
            </>
          ) : (
            isEditing ? 'Save Changes' : 'Create Habit'
          )}
        </button>
      </div>
    </form>
  );
};

export default HabitForm;