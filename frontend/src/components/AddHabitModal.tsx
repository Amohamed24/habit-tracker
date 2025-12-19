import { DateTime } from 'luxon';
import React, { useState } from 'react';
import DatePicker from "react-datepicker";
import "react-datepicker/dist/react-datepicker.css";

type AddHabitModalProps = {
  isModal: boolean;
  habit: string;
  category: string;
  filteredDays: string[];
  checkedDays: { [day: string]: boolean };
  setHabit: (val: string) => void;
  setCategory: (val: string) => void;
  checkHabit: (day: string) => void;
  addHabit: () => void;
  setIsModal: (val: boolean) => void;
  onSetDaily: () => void;
  selectedDate: string;
  onDateChange: (val: string) => void;
};

const AddHabitModal: React.FC<AddHabitModalProps> = ({
  isModal,
  habit,
  category,
  onDateChange,
  setHabit,
  setCategory,
  addHabit,
  setIsModal,
  onSetDaily,
  selectedDate,
  filteredDays,
  checkedDays,
  checkHabit
}) => {
    
  const [frequencyMode, setFrequencyMode] = useState<"daily" | "custom">("daily");

  if (!isModal) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-70"
    >
      <div className="bg-white w-[30rem] rounded-xl shadow-lg overflow-hidden">
        <header className="bg-purple-600 py-5 text-center text-white text-2xl font-semibold">
          Create New Habit
        </header>

        <div className="p-6 space-y-6">
          <div>
            <label htmlFor="habitName" className="font-medium text-gray-800 block mb-1">
              Habit Name
            </label>
            <input
              id="habitName"
              type="text"
              placeholder="Enter habit name..."
              className="w-full border bg-gray-100 py-2 px-4 rounded-md"
              value={habit}
              onChange={(e) => setHabit(e.target.value)}
              required
            />
          </div>

          <div>
            <label htmlFor="category" className="font-medium text-gray-800 block mb-1">
              Category
            </label>
            <select
              id="category"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              className="w-full border bg-gray-100 py-2 px-4 rounded-md"
              required
            >
              <option value='' disabled hidden>Select category</option>
              <option value='Fitness'>Fitness</option>
              <option value='Career'>Career</option>
              <option value='Spiritual'>Spiritual</option>
              <option value='Other'>Other</option>
            </select>
          </div>

          <div>
            <label className="font-medium text-gray-800 block mb-1">Frequency</label>
            <div className="flex gap-3">
              <button
                onClick={() => {
                    onSetDaily();
                    setFrequencyMode("daily");
                }}
                className={`px-4 py-2 rounded-full ${frequencyMode === "daily" ? "bg-purple-600 text-white" : "border border-gray-300 text-gray-700 hover:bg-gray-100"}`}
              >
                Daily
              </button>
              <button
                onClick={() => setFrequencyMode("custom")}
                className={`px-4 py-2 rounded-full ${frequencyMode === "custom" ? "bg-purple-600 text-white" : "border border-gray-300 text-gray-700 hover:bg-gray-100"}`}
              >
                Custom
              </button>
            </div>
          </div>

          {frequencyMode === "custom" && (
            <div className="flex flex-wrap gap-3 mt-3">
                {filteredDays.map((day) => (
                <label key={day} className="flex items-center gap-2">
                    <input
                    type="checkbox"
                    checked={checkedDays[day]}
                    onChange={() => checkHabit(day)}
                    className="accent-purple-600"
                    />
                    <span className="text-sm">{day}</span>
                </label>
                ))}
            </div>
          )}

          <div>
            <label className="font-medium text-gray-800 block mb-1">Select Date</label>
            <DatePicker
              selected={DateTime.fromISO(selectedDate).toJSDate()}
              onChange={(date: Date) => {
                const formatted = DateTime.fromJSDate(date).toFormat("yyyy-LL-dd");
                console.log("📆 Picker changed:", formatted);
                onDateChange(formatted);
              }}
              className="w-full border bg-gray-100 py-2 px-4 rounded-md"
              calendarClassName="rounded-md"
              dateFormat="yyyy-MM-dd"
            />
          </div>

          <div className="flex justify-end gap-3 pt-4">
            <button
              onClick={() => setIsModal(false)}
              className="px-5 py-2 border border-gray-400 text-gray-700 rounded-md hover:border-purple-600"
            >
              Cancel
            </button>
            <button
              type="submit"
              onClick={addHabit}
              className="px-5 py-2 bg-purple-600 text-white rounded-md hover:bg-purple-700"
            >
              Submit
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default AddHabitModal;
