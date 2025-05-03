
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
      

    const addItem = () => {
        setIsModal(!isModal)
    }

    const checkHabit = (day: string) => {
        setCheckedDays(prev => ({
          ...prev,
          [day]: !prev[day]
        }));
      };    

    const habitDays = [
        'Habit',
        'Mon',
        'Tues',
        'Wed',
        'Thurs',
        'Fri',
        'Sat',
        'Sun',
    ]

    const filteredDays = habitDays.filter((day) => day != 'Habit')
    

  return (
    <div className='bg-gray-100 h-screen'>
        <header className='flex flex-row justify-between p-5 w-full text-center bg-blue-300'>
            <h1 className='text-2xl font-semibold'>HabitTracker Dashboard</h1>
            <button 
            onClick={addItem}
            className='border bg-gray-200 px-5 py-3 rounded-lg cursor-pointer hover:bg-gray-300'> <FaPlus
            
            /></button>
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

                <div className="grid grid-cols-8 gap-5">

                    <div className="flex justify-center items-center border border-black bg-white h-auto w-auto ">Meditation</div>
                   
                    {filteredDays.map((day: string) => (
                        <div className="flex justify-center items-center border border-black bg-white h-auto w-auto">
                            <span 
                            onClick={() => checkHabit(day)}

                            className='flex border h-24 w-24 rounded-[50%] justify-center items-center bg-gray-200 text-white'>
                            {
                                checkedDays[day] ? 
                                <span className='flex h-24 w-24 rounded-[50%] justify-center items-center bg-green-200 border border-green-500 text-green-500'>
                                            <span>
                                                <FaCheck size={50}/>
                                            </span>
                                        </span>
                                : <span 
                                className='flex border h-24 w-24 rounded-[50%] justify-center items-center bg-gray-200 text-white hover:cursor-pointer'></span>
                            }
                            </span>
                        </div>
                    ))}

                </div>

                <div className='flex border border-dashed border-black px-20 m-1 py-10 items-center'>
                    <span className='flex border h-10 w-10 rounded-[50%] justify-center items-center bg-gray-400 text-white'>
                        <FaPlus size={20}/>
                    </span>
                    <h1 className="text-xl font-semibold pl-3">Add New Habit</h1>
                </div>
            </section>

            <section></section>

            {
                isModal ? 
                <div className="border border-black bg-white mx-96 p-10 h-48">
                    <h1 className="font-semibold mb-3">Habit Name</h1>
                    <input
                        type="text"
                        placeholder="Enter habit name..."
                        className="border border-black py-2 w-full pl-3 rounded-md"
                    />
                </div> 
                : 
                null
            }            
        </main>
      
    </div>
  )
}

export default DashboardPage;
