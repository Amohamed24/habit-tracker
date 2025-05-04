
import { useState } from "react";
import { FaPlus, FaCheck } from "react-icons/fa";


const DashboardPage = () => {
    const [isModal, setIsModal] = useState(false)
    const [checkedDays, setCheckedDays] = useState({
        Mon: false,
        Tues: false,
        Wed: false,
        Thurs: false,
        Fri: false,
        Sat: false,
        Sun: false,
      });
    const [habit, setHabit] = useState("")

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

    const [trackHabit, setTrackHabit] = useState<
    { name: string; days: string[] }[]
    >([]);
      

    const addItem = () => {
        setIsModal(!isModal)
    }

    const checkHabit = (day: string) => {
        setCheckedDays(prev => ({
          ...prev,
          [day]: !prev[day]
        }));
      };    



    const addHabit = () => {
        const newHabit = {
          name: habit,
          days: filteredDays.filter((day) => checkedDays[day])
        };

        const selectedDay = filteredDays.filter((day) => checkedDays[day])

        if (!habit || selectedDay.length == 0) return window.alert('Cannot leave empty')

        // logic to check if habit exists or not
        const isDuplicate = trackHabit.some((habit) => newHabit.name.toLowerCase() == habit.name.toLowerCase())
        if (isDuplicate) return window.alert('Must have unique names')
      
        setTrackHabit((prev) => [...prev, newHabit]);

        // reset everything
        setHabit(""); 
        setCheckedDays({
            Mon: false,
            Tues: false,
            Wed: false,
            Thurs: false,
            Fri: false,
            Sat: false,
            Sun: false,
        })
        setIsModal(false);         
      };
    

  return (
    <div className='bg-gray-100 min-h-screen'>
        <header className='flex flex-row justify-between p-5 w-full text-center bg-blue-300'>
            <h1 className='text-2xl font-semibold'>HabitTracker Dashboard</h1>
            <button 
                onClick={addItem}
                className='border bg-gray-200 px-5 py-3 rounded-lg cursor-pointer hover:bg-gray-300'>
                <FaPlus/>
            </button>
        </header>
        <main>
            <section className='flex flex-col justify-between mt-20 mx-5 h-auto'>
                <div className='grid grid-cols-8 pb-10 text-xl gap-5 text-center'>
                    {habitDays.map((day) => (
                        <div key={day} className="h-auto w-auto">
                            {day}
                        </div>
                    ))}
                </div>

                {trackHabit.length == 0 ? (
                    <div className="text-center">No habits have been added yet</div> 
                    ) : (
                        trackHabit.map((habit) => (
                            <div key={habit.name}
                            className="mb-3">
                                <div className="grid grid-cols-8 gap-5">
                                    <div className="flex justify-center items-center bg-white h-auto w-auto flex-wrap">{habit.name}</div>

                                    {filteredDays.map((day: string) => (
                                        <div className="flex justify-center items-center border border-black bg-white h-auto w-auto gap-3">
                                            <span 
                                            onClick={() => checkHabit(day)}

                                            className='flex border h-24 w-24 rounded-[50%] justify-center items-center bg-gray-200 text-white'>
                                            {
                                               habit.days.includes(day) ? 
                                                <span className='flex h-24 w-24 rounded-[50%] justify-center items-center bg-green-200 border border-green-500 text-green-500'>
                                                    <span>
                                                        <FaCheck size={50}/>
                                                    </span>
                                                </span>
                                                : <span 
                                                className='flex border h-24 w-24 rounded-[50%] justify-center items-center bg-gray-200 text-white hover:cursor-pointer'>
                                                </span>
                                            }
                                            </span>
                                        </div>
                                    ))}
                                    </div>
                                </div>
                        )
                    ))
                }

                <div className='flex border border-dashed border-black px-20 my-5 py-10 items-center'>
                    <span className='flex border h-10 w-10 rounded-[50%] justify-center items-center bg-gray-400 text-white'>
                        <FaPlus size={20}/>
                    </span>
                    <h1 className="text-xl font-semibold pl-3">Add New Habit</h1>
                </div>
            </section>

            <section></section>

            {isModal ? 
                <div className="flex flex-col fixed justify-center inset-0 align-middle bg-black bg-opacity-80 z-100">
                    <div className="bg-white w-96 m-auto rounded-lg">
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
                            />

                            <h1 className="font-semibold mt-4 mb-2">Category</h1>

                                <select
                                className="border bg-gray-100 py-2 w-full pl-4 rounded-md"
                                >
                                    <option>Fitness</option>
                                    <option>Other</option>
                                    <option>Penis</option>
                                    <option>Penis</option>
                                    <option>Penis</option>
                                </select>

                            

                            <div className="flex flex-row justify-normal gap-3 flex-wrap my-6">
                            {filteredDays.map((day) => (
                                <button
                                    key={day}
                                    onClick={() => checkHabit(day)}
                                    className={`p-2 rounded ${
                                    checkedDays[day]
                                        ? "bg-green-300 text-white"
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
                                className=" bg-green-500 border border-none px-5 py-2 rounded-md  hover:cursor-pointer text-white hover:bg-green-400">
                                    Submit
                                </button>

                                <button 
                                onClick={() => setIsModal(false)}
                                className=" bg-white border border-red-300 px-5 py-2 rounded-md hover:cursor-pointer text-red-500 hover:border-red-400">
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
