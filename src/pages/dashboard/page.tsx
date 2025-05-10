
import { useEffect, useState } from "react";
import { FaPlus } from "react-icons/fa";
import { DateTime } from 'luxon';
import HabitCard from '../../components/HabitCard'
import AddHabitModal from "../../components/AddHabitModal"
import { Habit, HabitDay } from "../../utils/utils";
import { FaArrowLeft, FaArrowRight } from "react-icons/fa";


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
    const [lastDateSelectionTime, setLastDateSelectionTime] = useState<number>(Date.now());

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

        if (!habit) return window.alert('Please complete all fields')
        if (category === '') return window.alert('Please select a category')

        const selectedDayOfWeek = DateTime.fromISO(selectedDate).toFormat('ccc')
        console.log("📅 Dashboard render — selectedDate:", selectedDate, "| selectedDayOfWeek:", selectedDayOfWeek);


        // logic to check if habit exists or not
        const isDuplicate = scheduledHabits[selectedDate]?.habits.some(
            (habit) => newHabit.name.toLowerCase() == habit.name.toLowerCase()
        ) ?? false;
        if (isDuplicate) return window.alert('Must have unique names')
      
        setScheduledHabits((prev) => {
            const updated = {...prev}

            const weekdayOfSelectedDate = DateTime.fromISO(selectedDate).toFormat("ccc");
            if (!selectedDays.includes(weekdayOfSelectedDate)) {
                return prev;
            }


            if (!updated[selectedDate]) {
                updated[selectedDate] = {
                    dayOfWeek: DateTime.fromISO(selectedDate).toFormat("ccc"),
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

        setSelectedDate((prev) => {
            return DateTime.fromISO(prev).toISODate()!;
        }); 

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

    const handleDateChange = (newDateStr: string) => {
        setSelectedDate(newDateStr);
        setLastDateSelectionTime(Date.now())
    }

    useEffect(() => {
        const weekday = DateTime.fromISO(selectedDate).toFormat("ccc");
        
        setCheckedDays({
            Mon: false,
            Tue: false,
            Wed: false,
            Thu: false,
            Fri: false,
            Sat: false,
            Sun: false,
            [weekday]: true,
          });          
      
        console.log("📆 Picker changed:", selectedDate, "| Auto-selected:", weekday);
    })
      

    const weekNumber = [1, 2, 3, 4]

      
    return (
        <div className='flex flex-col bg-gray-100 min-h-screen'>
            <header className='flex justify-between items-center px-8 py-6 shadow-sm bg-white mx-10 mt-8 mb-4 rounded-2xl'>
                <h1 className='text-2xl font-semibold'>Your Dashboard</h1>
                <p className="text-gray-500 text-lg">{todaysDate}</p>
            </header>

            <main>
                <section className='flex items-center justify-between gap-4 mx-10 my-4 px-6 py-4 h-auto border bg-white rounded-2xl'>
                        <div className='flex gap-4'>
                            {filteredDays.map((day) => (
                                <button
                                    key={day}
                                    className={`h-12 w-12 rounded-full flex items-center justify-center text-sm font-semibold
                                    ${checkedDays[day] ? 'bg-purple-600 text-white' : 'bg-gray-200 text-gray-800'}
                                    hover:bg-purple-500 hover:text-white transition`}
                                >
                                    {day.slice(0, 2)}
                                </button>
                            ))}
                            <div className="flex gap-2">
                                <button className="flex items-center justify-center border border-purple-400 text-purple-400 h-10 w-10 rounded-full">
                                    <FaArrowLeft 
                                        size={20}
                                    />
                                </button>
                                <button className="flex items-center justify-center border border-purple-400 text-purple-400  h-10 w-10 rounded-full">
                                    <FaArrowRight 
                                        size={20}
                                    />
                                </button>
                            </div>
                        </div>
                </section>

                <section className='mx-10 my-6 p-6 bg-white rounded-2xl border text-center'>
                        <h2 className="text-2xl font-semibold text-gray-800 mb-2">Today's Progress</h2>
                        <p className="text-3xl font-bold text-purple-600">
                            {completedCount} / {scheduledHabits[selectedDate]?.habits.length || 0}
                        </p>
                        <p className="text-gray-500">Habits completed</p>
                </section>

                <section className='flex flex-col mx-10 my-6 p-6 bg-white rounded-2xl border'>
                    <div className='flex justify-between items-center mb-6'>
                        <h1 className="text-2xl font-semibold text-gray-800">Habits for {selectedDate}</h1>

                        <button 
                            onClick={addItem}
                            className='flex items-center bg-purple-600 text-white px-4 py-2 rounded-full hover:bg-purple-500 transition'
                        >
                            <FaPlus className='mr-2' size={15}/>
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
                    selectedDate={selectedDate}
                    onDateChange={handleDateChange}
                />
                </section>
            </main>
        </div>
    )
}

export default DashboardPage;