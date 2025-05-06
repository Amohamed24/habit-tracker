
import { useState } from "react";
import { FaPlus } from "react-icons/fa";
import { DateTime } from 'luxon';
import HabitCard from '../../components/HabitCard'
import AddHabitModal from "../../components/AddHabitModal"
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

    const setAllDaysChecked = () => {
        setCheckedDays({
            Mon: true,
            Tue: true,
            Wed: true,
            Thu: true,
            Fri: true,
            Sat: true,
            Sun: true,
        })
    }

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
            habit.days.includes(selectedDayOfWeek) &&
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
                <HabitCard 
                    scheduledHabits={scheduledHabits}
                    selectedDate={selectedDate}
                    selectedDayOfWeek={selectedDayOfWeek}
                    markHabitComplete={markHabitComplete}
                />

                {/*Modal Card */}
                <AddHabitModal 
                    isModal={isModal} 
                    habit={habit} 
                    category={category} 
                    filteredDays={filteredDays} 
                    checkedDays={checkedDays} 
                    setHabit={setHabit} 
                    setCategory={setCategory} 
                    checkHabit={checkHabit} 
                    addHabit={addHabit} 
                    setIsModal={setIsModal}   
                    onSetDaily={setAllDaysChecked}             
                />
                </section>
            </main>
        </div>
    )
}

export default DashboardPage;