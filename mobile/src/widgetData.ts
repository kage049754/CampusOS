import { database } from './database';
import CampusNextClassWidget from './widgets/CampusNextClassWidget';
import CampusTasksWidget from './widgets/CampusTasksWidget';

type ClassRow = { title:string; start_time:string; end_time:string; day:number; kind:string; room:string };
type TaskRow = { title:string; due_date:string; due_time:string; priority:string };

const dateKey=(date:Date)=>date.getFullYear()+'-'+String(date.getMonth()+1).padStart(2,'0')+'-'+String(date.getDate()).padStart(2,'0');

export async function updateCampusWidgets():Promise<void>{
 try{
  const db=await database;
  const now=new Date();
  const day=(now.getDay()+6)%7;
  const time=String(now.getHours()).padStart(2,'0')+':'+String(now.getMinutes()).padStart(2,'0');
  const today=dateKey(now);
  const [classes,tasks,count]=await Promise.all([
   db.getAllAsync<ClassRow>('SELECT title,start_time,end_time,day,kind,room FROM schedule_entries WHERE day=? ORDER BY start_time',day),
   db.getAllAsync<TaskRow>("SELECT title,due_date,due_time,priority FROM tasks WHERE done=0 AND due_date<>'' ORDER BY due_date,due_time LIMIT 1"),
   db.getFirstAsync<{n:number}>('SELECT COUNT(*) AS n FROM tasks WHERE done=0')
  ]);
  const next=classes.find(row=>row.end_time>time);
  const active=classes.find(row=>row.start_time<=time&&row.end_time>time);
  const nextTitle=next?.title||'No more classes today';
  const nextTime=next?(active?'In progress · '+active.end_time+' end':next.start_time+'–'+next.end_time):'Open your weekly timetable';
  const detail=next?[next.kind,row.room].filter(Boolean).join(' · '):'Add or edit classes in Schedule.';
  CampusNextClassWidget.updateSnapshot({title:nextTitle,time:nextTime,detail});
  const task=tasks[0];
  const dueText=task?(task.due_date===today?'Due today': 'Due '+task.due_date)+(task.due_time?' · '+task.due_time:''):'No upcoming task deadline';
  CampusTasksWidget.updateSnapshot({
   count:count?.n||0,
   title:task?.title||(count?.n?'Tasks without a due date':'No pending tasks'),
   due:dueText
  });
 }catch(error){
  console.warn('CampusOS widgets could not be refreshed',error);
 }
}
