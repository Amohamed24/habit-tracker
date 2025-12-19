import { FaCheck } from "react-icons/fa";
import { HabitDay } from "../utils/utils";
import { DateTime } from "luxon";
import { CiEdit, CiTrash } from "react-icons/ci";


type HabitCardProps = {
  scheduledHabits: Record<string, HabitDay>;
  selectedDate: string;
  selectedDayOfWeek: string;
  markHabitComplete: (index: number) => void;
};

const HabitCard: React.FC<HabitCardProps> = ({
  scheduledHabits,
  selectedDate,
  selectedDayOfWeek,
  markHabitComplete,
}) => {
  const habitsForDay = scheduledHabits[selectedDate]?.habits?.filter((habit) =>
    habit.days.includes(DateTime.fromISO(selectedDate).toFormat("ccc"))
  );

  if (!habitsForDay || habitsForDay.length === 0) {
    return (
      <div className="w-full text-center text-gray-500 py-4">
        No habits have been added yet.
      </div>
    );
  }

  return (
    <div className="w-full space-y-4 mt-6">
      {habitsForDay.map((habit, index) => {
        const isComplete = !!habit.completions[selectedDate];

        return (
          <div
            key={`${habit.name}-${index}`}
            className="flex items-center justify-between border border-gray-300 rounded-xl p-5 bg-white hover:shadow transition w-full"
          >

            <button
              onClick={() => markHabitComplete(index)}
              className={`flex items-center justify-center h-12 w-12 rounded-full border transition mr-5
                ${isComplete
                  ? "bg-green-200 border-green-500 text-green-600"
                  : "border-gray-400 hover:bg-gray-100"}
              `}
            >
              {isComplete && <FaCheck size={20} />}
            </button>

            <div className="flex-1 text-left">
              <h3 className="text-xl font-semibold text-gray-800 truncate">{habit.name}</h3>
              <p className="text-sm text-gray-500">
                {habit.category} | {habit.days.length === 7 ? "Daily" : habit.days.join(", ")}
              </p>
            </div>

          
            <div className="flex space-x-3">
              <button 
                aria-label="Edit Habit"
                className="p-2 text-gray-500 hover:text-blue-600 hover:bg-blue-50 rounded-full transition-colors duration-200"
              >
                <CiEdit size={25} />
              </button>
              <button
                aria-label="Delete Habit" 
                className="p-2 text-gray-500 hover:text-red-600 hover:bg-red-50 rounded-full transition-colors duration-200"
              >
                <CiTrash size={25} />
              </button>
            </div>

          </div>
        );
      })}
    </div>
  );
};

export default HabitCard;
