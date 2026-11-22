import * as Notifications from 'expo-notifications';
import { database } from './database';

const CHANNEL_ID = 'campusos-reminders';
const APP_MARKER = 'campusOSReminder';
let configured = false;

export function configureCampusNotifications(): void {
  if (configured) return;
  configured = true;
  Notifications.setNotificationHandler({ handleNotification: async () => ({ shouldShowBanner: true, shouldShowList: true, shouldPlaySound: true, shouldSetBadge: false }) });
}

export async function getCampusNotificationPermission(): Promise<boolean> { return Boolean((await Notifications.getPermissionsAsync()).granted); }
export async function requestCampusNotificationPermission(): Promise<boolean> {
  await Notifications.setNotificationChannelAsync(CHANNEL_ID, { name: 'CampusOS reminders', importance: Notifications.AndroidImportance.MAX, vibrationPattern: [0,250,250,250], sound: 'default', lightColor: '#176B3A' });
  const current = await Notifications.getPermissionsAsync();
  const result = current.granted ? current : await Notifications.requestPermissionsAsync();
  return Boolean(result.granted);
}

function parseLocalDateTime(dateValue: string, timeValue: string): Date | null {
  const dateParts = dateValue.split('-').map(Number); const timeParts = timeValue.split(':').map(Number);
  if (dateParts.length !== 3 || dateParts.some(value => !Number.isFinite(value))) return null;
  const date = new Date(dateParts[0], dateParts[1]-1, dateParts[2], Number.isFinite(timeParts[0]) ? timeParts[0] : 23, Number.isFinite(timeParts[1]) ? timeParts[1] : 59, 0, 0);
  return Number.isNaN(date.getTime()) ? null : date;
}

async function cancelCampusNotifications(): Promise<void> {
  const scheduled = await Notifications.getAllScheduledNotificationsAsync();
  for (const notification of scheduled) if (notification.content.data?.[APP_MARKER] === true) await Notifications.cancelScheduledNotificationAsync(notification.identifier);
}

export async function rescheduleCampusReminders(): Promise<void> {
  const db = await database;
  const setting = await db.getFirstAsync<{value:string}>("SELECT value FROM app_settings WHERE key='reminders_enabled'");
  await cancelCampusNotifications();
  if (setting?.value !== 'true') return;
  await Notifications.setNotificationChannelAsync(CHANNEL_ID, { name:'CampusOS reminders', importance:Notifications.AndroidImportance.MAX, vibrationPattern:[0,250,250,250], sound:'default' });
  const [classes,tasks,notes] = await Promise.all([
    db.getAllAsync<{id:string;title:string;day:number;start_time:string;end_time:string;kind:string;room:string}>('SELECT id,title,day,start_time,end_time,kind,room FROM schedule_entries'),
    db.getAllAsync<{id:string;title:string;due_date:string;due_time:string;done:number}>('SELECT id,title,due_date,due_time,done FROM tasks WHERE done=0 AND due_date IS NOT NULL AND due_date<>\'\'')    db.getAllAsync<{id:string;title:string;note_date:string;note_time:string}>('SELECT id,title,note_date,note_time FROM notes WHERE note_date<>\'\' AND note_time<>\'\'')

  ]);
  const now=Date.now(); let count=0;
  const scheduleOne=async(title:string,body:string,trigger:Date,kind:string,recordId:string)=>{
    if(trigger.getTime()<=now+10000 || count>=250) return;
    await Notifications.scheduleNotificationAsync({content:{title,body,sound:'default',data:{[APP_MARKER]:true,kind,recordId}},trigger:{type:Notifications.SchedulableTriggerInputTypes.DATE,date:trigger,channelId:CHANNEL_ID}});
    count+=1;
  };
  const base=new Date(); base.setHours(0,0,0,0);
  for(let offset=0;offset<=7 && count<250;offset++){
    const d=new Date(base); d.setDate(base.getDate()+offset); const day=(d.getDay()+6)%7;
    const dateValue=d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
    for(const row of classes){ if(Number(row.day)!==day) continue; const start=parseLocalDateTime(dateValue,row.start_time); if(!start) continue;
      const label=[row.kind,row.room].filter(Boolean).join(' · ');
      for(const minutes of [30,10]) await scheduleOne('CampusOS · Upcoming class',row.title+(label?' · '+label:'')+' starts in '+minutes+' minutes ('+row.start_time+').',new Date(start.getTime()-minutes*60000),'class',row.id);
      if(count>=250) break;
    }
  }
  for(const note of notes){
    if(count>=250) break;
    const alarm=parseLocalDateTime(note.note_date,note.note_time);
    if(!alarm || alarm.getTime()<=now) continue;
    await scheduleOne('CampusOS · Note alarm',note.title+' is scheduled for '+note.note_date+' at '+note.note_time+'.',alarm,'note',note.id);
  }
  for(const task of tasks){ if(count>=250) break; const due=parseLocalDateTime(task.due_date,task.due_time||'23:59'); if(!due || due.getTime()<=now) continue;
    for(const item of [[1440,'1 day'],[60,'1 hour']] as const) await scheduleOne('CampusOS · Task reminder',task.title+' is due in '+item[1]+(task.due_time?' · '+task.due_time:'' )+'.',new Date(due.getTime()-item[0]*60000),'task',task.id);
  }
}
