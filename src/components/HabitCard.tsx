

import { FaCheck } from "react-icons/fa";
import { HabitDay } from "../utils/utils";

type HabitCardProps = {
    scheduledHabits: Record<string, HabitDay>;
    selectedDate: string;
    selectedDayOfWeek: string;
    markHabitComplete: (index: number) => void;
}

const HabitCard: React.FC<HabitCardProps> = ({ scheduledHabits, selectedDate, selectedDayOfWeek, markHabitComplete  }) => {
  return (
    <div>
        <main>
        {!scheduledHabits[selectedDate] || scheduledHabits[selectedDate].habits.length === 0 ? (
                    <div className="text-center text-gray-500">No habits have been added yet</div> 
                    ) : (
                        scheduledHabits[selectedDate]?.habits
                        .filter((habit) => habit.days.includes(selectedDayOfWeek))
                        .map((habit, index) => (
                            <div key={`${habit.name}-${index}`}
                            className="flex flex-row-reverse justify-end items-center mb-3 border border-gray-400 rounded-lg p-5 mt-5 w-full">
                                <div className="">
                                    <div className="text-left"> 
                                        <div className="flex text-2xl font-semibold bg-white h-auto w-auto flex-wrap">{habit.name}</div>
                                        <div>
                                            <p className="text-gray-500">{habit.category} | {'Daily'}</p>
                                        </div>
                                    </div>
                                </div>
                                <div onClick={() => markHabitComplete(index)}>
                                    {habit.completions[selectedDate] ? (
                                    <span 
                                        className='flex h-16 w-16 rounded-[50%] justify-center items-center bg-green-200 border border-green-500 text-green-500 mr-5 hover:cursor-pointer'>
                                        <span>
                                            <FaCheck size={30}/>
                                        </span>
                                    </span>
                                    ) : (
                                    <span 
                                        className='flex h-16 w-16 rounded-[50%] justify-center items-center border border-gray-400 mr-5 hover:cursor-pointer'>
                                    </span>
                                    )}
                                </div>
                            </div>
                        )
                    ))
                }
        </main>
    </div>
  )
}

export default HabitCard;
