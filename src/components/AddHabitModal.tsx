import React from 'react'

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
  onSetDaily: ()=> void;
}

const AddHabitModal:React.FC<AddHabitModalProps> = ({isModal, habit, category, filteredDays, checkedDays, setHabit, setCategory, checkHabit, addHabit, setIsModal, onSetDaily}) => {
    if (!isModal) return null;

    return (
        <div>
            <main>
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
                                    onClick={onSetDaily}
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

export default AddHabitModal
