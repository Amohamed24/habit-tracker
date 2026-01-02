import { createSlice, createAsyncThunk } from '@reduxjs/toolkit';
import { habitsAPI } from '../api/habits';

// Async thunks
export const fetchHabits = createAsyncThunk(
  'habits/fetchAll',
  async (_, { rejectWithValue }) => {
    try {
      return await habitsAPI.getAll();
    } catch (error) {
      return rejectWithValue(error.message);
    }
  }
);

export const createHabit = createAsyncThunk(
  'habits/create',
  async (habitData, { rejectWithValue }) => {
    try {
      return await habitsAPI.create(habitData);
    } catch (error) {
      return rejectWithValue(error.message);
    }
  }
);

export const updateHabit = createAsyncThunk(
  'habits/update',
  async ({ id, habitData }, { rejectWithValue }) => {
    try {
      return await habitsAPI.update(id, habitData);
    } catch (error) {
      return rejectWithValue(error.message);
    }
  }
);

export const deleteHabit = createAsyncThunk(
  'habits/delete',
  async (id, { rejectWithValue }) => {
    try {
      await habitsAPI.delete(id);
      return id;  // Return id so reducer knows which to remove
    } catch (error) {
      return rejectWithValue(error.message);
    }
  }
);

export const toggleHabitComplete = createAsyncThunk(
  'habits/toggleComplete',
  async ({ id, isCompleted }, { rejectWithValue }) => {
    try {
      if (isCompleted) {
        return await habitsAPI.unmarkComplete(id);
      } else {
        return await habitsAPI.markComplete(id);
      }
    } catch (error) {
      return rejectWithValue(error.message);
    }
  }
);

const initialState = {
  habits: [],
  loading: false,
  error: null,
};

const habitsSlice = createSlice({
  name: 'habits',
  initialState,
  reducers: {
    clearHabits: (state) => {
      state.habits = [];
      state.error = null;
    },
    clearError: (state) => {
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      // Fetch all
      .addCase(fetchHabits.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchHabits.fulfilled, (state, action) => {
        state.loading = false;
        state.habits = action.payload;
      })
      .addCase(fetchHabits.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload;
      })
      // Create
      .addCase(createHabit.pending, (state) => {
        state.error = null;
      })
      .addCase(createHabit.fulfilled, (state, action) => {
        state.habits.unshift(action.payload);  // Add to beginning
      })
      .addCase(createHabit.rejected, (state, action) => {
        state.error = action.payload;
      })
      // Update
      .addCase(updateHabit.fulfilled, (state, action) => {
        const index = state.habits.findIndex(h => h.id === action.payload.id);
        if (index !== -1) {
          state.habits[index] = action.payload;
        }
      })
      .addCase(updateHabit.rejected, (state, action) => {
        state.error = action.payload;
      })
      // Delete
      .addCase(deleteHabit.fulfilled, (state, action) => {
        state.habits = state.habits.filter(h => h.id !== action.payload);
      })
      .addCase(deleteHabit.rejected, (state, action) => {
        state.error = action.payload;
      })
      // Toggle complete
      .addCase(toggleHabitComplete.fulfilled, (state, action) => {
        const index = state.habits.findIndex(h => h.id === action.payload.id);
        if (index !== -1) {
          state.habits[index] = action.payload;
        }
      })
      .addCase(toggleHabitComplete.rejected, (state, action) => {
        state.error = action.payload;
      });
  },
});

export const { clearHabits, clearError } = habitsSlice.actions;
export default habitsSlice.reducer;