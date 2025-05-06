
import { useState } from "react";
import { FaPlus, FaCheck } from "react-icons/fa";
import { DateTime } from 'luxon';
import HabitCard from '../../components/HabitCard'
import { Habit, HabitDay } from "../../utils/utils";


const DashboardPage = () => {
    const [isModal, setIsModal] = useState(false)
    const [checkedDays, setCheckedDays] = useState({
        Mon: false,
        Tue: false,
        Wed: false,
        Thu: false,
        Fri: false,
        Sat: false,
        Sun: false,
      });
    const [habit, setHabit] = useState("")
    const [category, setCategory] = useState('')

    const habitDays = [
        'Habit',
        'Mon',
        'Tue',
        'Wed',
        'Thu',
        'Fri',
        'Sat',
        'Sun',
    ]

    const filteredDays = habitDays.filter((day) => day != 'Habit')

    const [scheduledHabits, setScheduledHabits] = useState<Record<string, HabitDay>>({})

    const today = DateTime.now().setZone('America/Chicago');
    const todaysDay = today.toFormat('ccc')
    const todaysDate = today.toFormat('yyyy-LL-dd')

    const [selectedDate, setSelectedDate] = useState(todaysDate)

    const addItem = () => {
        setIsModal(!isModal)
    }

    const checkHabit = (day: string) => {
        setCheckedDays(prev => ({
          ...prev,
          [day]: !prev[day]
        }));
      };    

    const selectedDayOfWeek = DateTime.fromISO(selectedDate).toFormat('ccc')

    const addHabit = () => {
        const selectedDays = filteredDays.filter((day) => checkedDays[day])

        const newHabit: Habit = {
          name: habit,
          days: selectedDays,
          completions: {},
          category: category
        };

        if (!habit || selectedDays.length == 0) return window.alert('Please complete all fields')
        
        if (category === '') return window.alert('Please select a category')

        // logic to check if habit exists or not
        const isDuplicate = scheduledHabits[selectedDate]?.habits.some(
            (habit) => newHabit.name.toLowerCase() == habit.name.toLowerCase()
        ) ?? false;
        if (isDuplicate) return window.alert('Must have unique names')
      
        setScheduledHabits((prev) => {
            const updated = {...prev}

            

            if (!selectedDays.includes(selectedDayOfWeek)) {
                return prev
            }

            if (!updated[selectedDate]) {
                updated[selectedDate] = {
                    dayOfWeek: todaysDay,
                    week: today.weekNumber,
                    habits: [],
                };
            }

            updated[selectedDate] = {
                ...updated[selectedDate],
                habits: [...updated[selectedDate].habits, newHabit]
            }

            return updated
        }),

        // reset everything
        setHabit(""); 
        setCheckedDays({
            Mon: false,
            Tue: false,
            Wed: false,
            Thu: false,
            Fri: false,
            Sat: false,
            Sun: false,
        })
        setCategory("")
        setIsModal(false); 
    };



    const completedCount = scheduledHabits[selectedDate]?.habits.filter(
        (habit) => 
            habit.days.includes(selectedDate) &&
            !!habit.completions?.[selectedDate]
    ).length || 0;


    const markHabitComplete = (index: number) => {
        setScheduledHabits((prev) => {
            const dateHabits = prev[selectedDate]
            if (!dateHabits) return prev
            
            const updatedHabits = [...dateHabits.habits]
            const oldHabit = updatedHabits[index]

            if (!oldHabit.days.includes(selectedDayOfWeek)) return prev
            
            const current = !!oldHabit.completions[selectedDate];            

            const updatedHabit = {
                ...oldHabit,
                completions: {
                    ...oldHabit.completions,
                    [selectedDate]: !current
                },
            };

            updatedHabits[index] = updatedHabit
              
            return {
                ...prev,
                [selectedDate]: {
                    ...dateHabits,
                    habits: updatedHabits,
                }
            }
        })
    }

    const weekNumber = [1, 2, 3, 4]

      
    
  return (
    <div className='flex flex-col bg-gray-100 min-h-screen'>
        <header className='flex flex-row justify-between items-center p-5 text-center bg-white m-5 rounded-2xl'>
            <h1 className='text-2xl font-semibold'>Your Dashboard</h1>
            <p className="text-gray-500 text-lg">{todaysDate}</p>
        </header>
        <main>
            <section className='flex flex-col justify-between m-auto my-5 h-auto border w-1/2 bg-white rounded-2xl p-10'>
                    <div>
                        <h1 className="text-3xl font-semibold">Today's Progress</h1>
                    </div>

                    <div>
                        <p>{completedCount} / {scheduledHabits[selectedDate]?.habits.length || 0}</p>
                        <p>Habits completed</p>
                    </div>
                </section>

            <section className='flex flex-col justify-between m-auto items-center h-auto border w-1/2 bg-white rounded-2xl p-10'>
                <div className='text-xl gap-5 text-center flex items-center justify-between w-full'>
                    <h1 className="text-3xl font-semibold">My Habits</h1>

                    <button 
                    onClick={addItem}
                    className='flex border border-none bg-purple-600 items-center px-5 py-3 rounded-full'>
                        <span className='flex  items-center  text-white'>
                            <FaPlus size={15}/>
                        </span>
                        <h1 className="text-xl font-semibold pl-2 text-white">Add</h1>
                    </button>
                </div>

                {/* Habit card */}
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

            {/*Modal Card */}
            </section>
            {isModal ? 
                <div className="flex flex-col fixed justify-center inset-0 align-middle bg-black bg-opacity-80 z-100">
                    <div className="bg-white w-[30rem] m-auto rounded-lg">
                        <header className="w-full py-5 text-center bg-purple-600 text-white font-semibold rounded-t-lg">
                            <h1 className="text-3xl ">Create New Habit</h1>
                        </header>

                        <div className="p-10">
                            <h1 className="font-semibold mb-2">Habit Name</h1>
                            <input
                                type="text"
                                placeholder="Enter habit name..."
                                className="border bg-gray-100 py-2 w-full pl-4 rounded-md"
                                value={habit}
                                onChange={(e) => setHabit(e.target.value)}
                                required
                            />

                            <h1 className="font-semibold mt-4 mb-2">Category</h1>
                            <select
                                value={category}
                                onChange={(e) => setCategory(e.target.value)}
                                className="border bg-gray-100 py-2 w-full pl-4 rounded-md"
                                required
                            >
                                <option value='' disabled hidden>Select category</option>
                                <option value='Fitness'>Fitness</option>
                                <option value='Career'>Career</option>
                                <option value='Spiritual'>Spiritual</option>
                                <option value='Other'>Other</option>
                            </select>

                            <h1 className="font-semibold mt-4 mb-2">Frequency</h1>
                            <div className="flex gap-5">
                                <button 
                                className="border border-gray-300 px-5 py-2 rounded-full  hover:cursor-pointer bg-purple-600 text-white" 
                                    >Daily
                                </button>
                                <button className="border border-gray-300 px-5 py-2 rounded-full  hover:cursor-pointer ">Custom</button>
                            </div>
                            
                            {/* Days it will appear on calendar for that week */}
                            <div className="flex flex-row justify-center gap-3 flex-wrap my-6">
                            {filteredDays.map((day) => (
                                <button
                                    key={day}
                                    onClick={() => checkHabit(day)}
                                    className={`p-2 rounded ${
                                    checkedDays[day]
                                        ? "bg-purple-600 text-white"
                                        : "bg-gray-200"
                                    }`}
                                >
                                    {day}
                                </button>
                                ))}
                            </div>

                            <div className="flex flex-row justify-center gap-3">
                                <button 
                                type="submit"
                                onClick={addHabit}
                                className=" bg-purple-600 border border-none px-5 py-2 rounded-md  hover:cursor-pointer text-white hover:bg-opacity-80">
                                    Submit
                                </button>

                                <button 
                                onClick={() => setIsModal(false)}
                                className=" bg-white border border-gray-700 px-5 py-2 rounded-md hover:cursor-pointer text-gray-700 hover:border-purple-600">
                                    Cancel
                                </button>
                            </div>
                        </div>
                        
                    </div>
                </div> 
                : 
                null
            }            
        </main>
    </div>
  )
}

export default DashboardPage;