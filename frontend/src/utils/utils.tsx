export type Habit = {
    name: string; 
    days: string[]; 
    completions: { [date: string]: boolean};
    category: string; 
}

export type HabitDay = {
    dayOfWeek: string;
    week: number;
    habits: Habit[];
}

