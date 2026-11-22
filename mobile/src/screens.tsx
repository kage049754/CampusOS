import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Linking, Modal, Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import * as DocumentPicker from 'expo-document-picker';
import * as FileSystem from 'expo-file-system/legacy';
import { File as ExpoFile } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { BackupModule, backupModules, restoreCampusBackup, shareCampusBackup } from './backup';
import { persistPickedFile, removeStoredFile } from './storage';
import { getCampusNotificationPermission, requestCampusNotificationPermission, rescheduleCampusReminders } from './notifications';
import { updateCampusWidgets } from './widgetData';
import { database } from './database';
import { PaletteName, palettes, Theme } from './theme';

type P = { theme: Theme };
type ClassRow = { id:string; subject_id:string|null; title:string; day:number; start_time:string; end_time:string; kind:string; room:string; professor:string; notes:string };
type TaskRow = { id:string; title:string; details:string; checklist:string; due_date:string; due_time:string; done:number; pinned:number; priority:string; status:string; subject_id:string|null; created_at:number };
type NoteRow = { id:string; title:string; body:string; subject_id:string|null; favorite:number; note_date:string; created_at:number; updated_at:number };
type BudgetRow = { id:string; type:string; amount:number; category:string; note:string; date:string; target?:string; created_at?:number };
type SavingsGoal = { id:string; title:string; target:number; saved:number; due_date:string; created_at:number };
type FileRow = { id:string; subject_id:string|null; name:string; mime_type:string; local_uri:string; size:number; folder_name?:string; favorite?:number };
type SubjectRow = { id:string; legacy_id?:string|null; code:string; title:string; color:string; favorite?:number; created_at:number };
const days=['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'];
const dateKey=(date:Date)=>date.getFullYear()+'-'+String(date.getMonth()+1).padStart(2,'0')+'-'+String(date.getDate()).padStart(2,'0');
const today=()=>dateKey(new Date());
const uid=(p:string)=>p+'-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2,7);
const mins=(v:string)=>{const [h,m]=v.split(':').map(Number);return (h||0)*60+(m||0);};

function Card({theme,title,children}:{theme:Theme;title?:string;children:React.ReactNode}){return <View style={[s.card,{backgroundColor:theme.surface,borderColor:theme.border}]}>{title?<Text style={[s.heading,{color:theme.text}]}>{title}</Text>:null}{children}</View>}
function Field({theme,label,value,onChangeText,multiline=false,keyboardType='default'}:{theme:Theme;label:string;value:string;onChangeText:(v:string)=>void;multiline?:boolean;keyboardType?:'default'|'numeric'|'decimal-pad'}){return <View style={{gap:5}}><Text style={{color:theme.muted,fontSize:12,fontWeight:'700'}}>{label}</Text><TextInput value={value} onChangeText={onChangeText} placeholder={label} placeholderTextColor={theme.muted} keyboardType={keyboardType} multiline={multiline} style={[s.input,{backgroundColor:theme.surfaceAlt,borderColor:theme.border,color:theme.text},multiline&&{minHeight:90,textAlignVertical:'top'}]}/></View>}
function Button({theme,label,onPress,secondary=false}:{theme:Theme;label:string;onPress:()=>void;secondary?:boolean}){return <Pressable onPress={onPress} style={({pressed})=>[s.button,{backgroundColor:secondary?theme.surfaceAlt:theme.primary,opacity:pressed?.7:1}]}><Text style={{color:secondary?theme.primary:'#FFF',fontWeight:'800'}}>{label}</Text></Pressable>}
function Empty({theme,text}:{theme:Theme;text:string}){return <View style={[s.empty,{backgroundColor:theme.surfaceAlt}]}><Text style={{color:theme.muted,textAlign:'center'}}>{text}</Text></View>}
function Editor({theme,title,visible,onClose,children}:{theme:Theme;title:string;visible:boolean;onClose:()=>void;children:React.ReactNode}){return <Modal visible={visible} animationType="slide" transparent onRequestClose={onClose}><View style={s.shade}><View style={[s.editor,{backgroundColor:theme.surface}]}><View style={s.editorHead}><Text style={[s.heading,{color:theme.text,flex:1}]}>{title}</Text><Pressable onPress={onClose}><Text style={{color:theme.primary,fontWeight:'700'}}>Close</Text></Pressable></View><ScrollView contentContainerStyle={{gap:12,paddingBottom:18}} keyboardShouldPersistTaps="handled">{children}</ScrollView></View></View></Modal>}

type HomeTileRow = { tile_key:string; visible:number; position:number };
const DEFAULT_HOME_TILES:HomeTileRow[]=[
 {tile_key:'profile',visible:1,position:0},
 {tile_key:'stats',visible:1,position:1},
 {tile_key:'classes',visible:1,position:2},
 {tile_key:'pinned',visible:1,position:3},
 {tile_key:'tasks',visible:1,position:4}
];
export function HomeScreen({theme,go}:P&{go:(v:string)=>void}){
 const [profile,setProfile]=useState({full_name:'',student_number:'',year_section:''});
 const [stats,setStats]=useState([0,0,0]);
 const [todayClasses,setTodayClasses]=useState<ClassRow[]>([]);
 const [next,setNext]=useState<ClassRow|null>(null);
 const [tasks,setTasks]=useState<TaskRow[]>([]);
 const [pinnedNotes,setPinnedNotes]=useState<NoteRow[]>([]);
 const [tiles,setTiles]=useState<HomeTileRow[]>(DEFAULT_HOME_TILES);
 const refresh=useCallback(async()=>{
  const db=await database;
  const profileRow=await db.getFirstAsync<typeof profile>('SELECT full_name,student_number,year_section FROM profile WHERE id=1');
  if(profileRow)setProfile(profileRow);
  const todayIndex=(new Date().getDay()+6)%7;
  const classRows=await db.getAllAsync<ClassRow>('SELECT * FROM schedule_entries WHERE day=? ORDER BY start_time',todayIndex);
  setTodayClasses(classRows);
  const now=new Date().toTimeString().slice(0,5);
  setNext(classRows.find(row=>row.end_time>now)||null);
  const [taskCount,subjectCount,noteCount,taskRows,noteRows]=await Promise.all([
   db.getFirstAsync<{n:number}>('SELECT COUNT(*) n FROM tasks WHERE done=0'),
   db.getFirstAsync<{n:number}>('SELECT COUNT(*) n FROM subjects'),
   db.getFirstAsync<{n:number}>('SELECT COUNT(*) n FROM notes'),
   db.getAllAsync<TaskRow>('SELECT * FROM tasks WHERE done=0 ORDER BY due_date,due_time LIMIT 4'),
   db.getAllAsync<NoteRow>('SELECT * FROM notes WHERE favorite=1 ORDER BY updated_at DESC LIMIT 4')
  ]);
  setStats([taskCount?.n||0,subjectCount?.n||0,noteCount?.n||0]);
  setTasks(taskRows);setPinnedNotes(noteRows);
  let savedTiles=await db.getAllAsync<HomeTileRow>('SELECT * FROM home_tiles ORDER BY position');
  if(savedTiles.length===0){
   for(const tile of DEFAULT_HOME_TILES)await db.runAsync('INSERT OR IGNORE INTO home_tiles (tile_key,visible,position) VALUES (?,?,?)',tile.tile_key,tile.visible,tile.position);
   savedTiles=await db.getAllAsync<HomeTileRow>('SELECT * FROM home_tiles ORDER BY position');
  }
  const allowed=new Set(DEFAULT_HOME_TILES.map(tile=>tile.tile_key));
  const normalized=savedTiles.filter(tile=>allowed.has(tile.tile_key));
  const known=new Set(normalized.map(tile=>tile.tile_key));
  for(const tile of DEFAULT_HOME_TILES)if(!known.has(tile.tile_key))normalized.push({...tile,position:normalized.length});
  normalized.sort((a,b)=>a.position-b.position);
  setTiles(normalized);
 },[]);
 useEffect(()=>{refresh();},[refresh]);
 const renderTile=(key:string)=>{
  if(key==='profile')return <View style={[s.hero,{backgroundColor:theme.primary}]}><Text style={s.eyebrow}>YOUR PERSONAL STUDENT PLANNER</Text><Text style={s.heroTitle}>{profile.full_name?'Hello, '+profile.full_name.split(' ')[0]:'Good day.'}</Text><Text style={s.heroSub}>{[profile.year_section,profile.student_number].filter(Boolean).join(' · ')||'Your classes, tasks, and notes in one place.'}</Text></View>;
  if(key==='stats')return <View style={s.quickGrid}>{[['Open tasks',stats[0]],['Subjects',stats[1]],['Notes',stats[2]]].map(([label,value])=><View key={String(label)} style={[s.quick,{backgroundColor:theme.surface,borderColor:theme.border}]}><Text style={{color:theme.primary,fontSize:22,fontWeight:'900'}}>{value}</Text><Text style={{color:theme.text,fontWeight:'700'}}>{label}</Text></View>)}</View>;
  if(key==='classes')return <Card theme={theme} title="Today's classes"><Text style={{color:theme.muted,fontSize:11}}>{todayClasses.length} class session{todayClasses.length===1?'':'s'} today</Text>{next?<View style={{gap:4}}><Text style={{color:theme.primary,fontSize:11,fontWeight:'800'}}>NEXT CLASS</Text><Text style={[s.itemTitle,{color:theme.text}]}>{next.title}</Text><Text style={{color:theme.muted}}>{next.start_time}–{next.end_time} · {next.kind}{next.room?' · '+next.room:''}</Text></View>:<Text style={{color:theme.muted}}>No more classes scheduled today.</Text>}{todayClasses.slice(0,4).map(row=><View key={row.id} style={[s.listRow,{borderBottomColor:theme.border}]}><View style={{flex:1}}><Text style={{color:theme.text,fontWeight:'700'}}>{row.title}</Text><Text style={{color:theme.muted,fontSize:11}}>{row.start_time}–{row.end_time} · {row.kind}{row.room?' · '+row.room:''}</Text></View></View>)}<Button theme={theme} label="Open schedule" secondary onPress={()=>go('Schedule')}/></Card>;
  if(key==='pinned')return <Card theme={theme} title="Pinned notes">{pinnedNotes.length?pinnedNotes.map(row=><Pressable key={row.id} onPress={()=>go('Notepad')} style={[s.listRow,{borderBottomColor:theme.border,alignItems:'flex-start'}]}><View style={{flex:1,gap:3}}><Text style={{color:theme.text,fontWeight:'800'}}>{row.title}</Text><Text style={{color:theme.muted,fontSize:11}} numberOfLines={2}>{row.body}</Text></View><Text style={{color:theme.primary}}>★</Text></Pressable>):<Text style={{color:theme.muted}}>Favorite a note to pin it here.</Text>}<Button theme={theme} label="Open Notepad" secondary onPress={()=>go('Notepad')}/></Card>;
  if(key==='tasks')return <Card theme={theme} title="Upcoming tasks">{tasks.length?tasks.map(row=><View key={row.id} style={[s.listRow,{borderBottomColor:theme.border}]}><View style={{flex:1,gap:3}}><Text style={{color:theme.text,fontWeight:'700'}}>{row.title}</Text><Text style={{color:theme.muted,fontSize:11}}>{row.due_date||'No due date'}{row.due_time?' · '+row.due_time:''}</Text></View><Text style={{color:theme.primary,fontSize:11,fontWeight:'700'}}>{row.priority||'Medium'}</Text></View>):<Empty theme={theme} text="No pending tasks. You're all caught up."/>}<Button theme={theme} label="Open Tasks" secondary onPress={()=>go('Tasks')}/></Card>;
  return null;
 };
 return <>{tiles.filter(tile=>tile.visible===1).map(tile=><View key={tile.tile_key}>{renderTile(tile.tile_key)}</View>)}</>;
}

type DisplayClass = ClassRow & { mergedCount:number; mergedIds:string[]; merged_end_time:string };
function mergeAdjacentClasses(items:ClassRow[]):DisplayClass[]{
 const sorted=[...items].sort((a,b)=>mins(a.start_time)-mins(b.start_time));
 const result:DisplayClass[]=[];
 for(const row of sorted){
  const previous=result[result.length-1];
  const sameSubject=Boolean(previous&&((previous.subject_id&&row.subject_id&&previous.subject_id===row.subject_id)||previous.title===row.title));
  if(previous&&sameSubject&&previous.room.trim().toLowerCase()===row.room.trim().toLowerCase()&&previous.kind===row.kind&&previous.merged_end_time===row.start_time){
   result[result.length-1]={...previous,merged_end_time:row.end_time,mergedCount:previous.mergedCount+1,mergedIds:[...previous.mergedIds,row.id]};
  }else result.push({...row,merged_end_time:row.end_time,mergedCount:1,mergedIds:[row.id]});
 }
 return result;
}

export function ScheduleScreen({theme}:P){
 const [rows,setRows]=useState<ClassRow[]>([]);
 const [visible,setVisible]=useState(false);
 const [settingsVisible,setSettingsVisible]=useState(false);
 const [edit,setEdit]=useState<ClassRow|null>(null);
 const [selectedDays,setSelectedDays]=useState<number[]>([0,1,2,3,4,5]);
 const [startHour,setStartHour]=useState('07');
 const [endHour,setEndHour]=useState('21');
 const [title,setTitle]=useState(''),[code,setCode]=useState(''),[day,setDay]=useState(0),[kind,setKind]=useState('Lecture'),[start,setStart]=useState('08:00'),[end,setEnd]=useState('09:00'),[room,setRoom]=useState(''),[prof,setProf]=useState(''),[notes,setNotes]=useState('');
 const refresh=useCallback(async()=>{
  const db=await database;
  const [classRows,daySetting,startSetting,endSetting]=await Promise.all([
   db.getAllAsync<ClassRow>('SELECT * FROM schedule_entries ORDER BY day,start_time'),
   db.getFirstAsync<{value:string}>("SELECT value FROM app_settings WHERE key='schedule_days'"),
   db.getFirstAsync<{value:string}>("SELECT value FROM app_settings WHERE key='schedule_start_hour'"),
   db.getFirstAsync<{value:string}>("SELECT value FROM app_settings WHERE key='schedule_end_hour'")
  ]);
  setRows(classRows);
  if(daySetting?.value)setSelectedDays(daySetting.value.split(',').map(Number).filter(value=>Number.isInteger(value)&&value>=0&&value<=6));
  if(startSetting?.value)setStartHour(startSetting.value);
  if(endSetting?.value)setEndHour(endSetting.value);
 },[]);
 useEffect(()=>{refresh();},[refresh]);
 const open=async(row?:ClassRow)=>{
  setEdit(row||null);setTitle(row?.title||'');setCode('');
  if(row?.subject_id){const db=await database;const subject=await db.getFirstAsync<SubjectRow>('SELECT * FROM subjects WHERE id=?',row.subject_id);if(subject){setTitle(subject.title);setCode(subject.code);}}
  setDay(row?.day??(selectedDays[0]??0));setKind(row?.kind||'Lecture');setStart(row?.start_time||'08:00');setEnd(row?.end_time||'09:00');setRoom(row?.room||'');setProf(row?.professor||'');setNotes(row?.notes||'');setVisible(true);
 };
 const save=async()=>{
  if(!title.trim()||!/^([01]\\d|2[0-3]):[0-5]\\d$/.test(start)||!/^([01]\\d|2[0-3]):[0-5]\\d$/.test(end)||mins(end)<=mins(start))return Alert.alert('Check class details','Provide a subject and valid start/end times.');
  const conflict=rows.find(row=>row.day===day&&row.id!==edit?.id&&mins(start)<mins(row.end_time)&&mins(end)>mins(row.start_time));
  if(conflict)return Alert.alert('Schedule conflict','This overlaps '+conflict.title+' ('+conflict.start_time+'–'+conflict.end_time+').');
  const db=await database;
  let subject=edit?.subject_id?await db.getFirstAsync<SubjectRow>('SELECT * FROM subjects WHERE id=?',edit.subject_id):null;
  if(!subject){subject=code.trim()?await db.getFirstAsync<SubjectRow>('SELECT * FROM subjects WHERE lower(code)=lower(?) LIMIT 1',code.trim()):await db.getFirstAsync<SubjectRow>('SELECT * FROM subjects WHERE lower(title)=lower(?) LIMIT 1',title.trim());}
  const subjectId=subject?.id||uid('subject');
  if(!subject)await db.runAsync('INSERT INTO subjects (id,code,title,color,created_at) VALUES (?,?,?,?,?)',subjectId,code.trim(),title.trim(),theme.primary,Date.now());
  else if(subject.title!==title.trim()||(code.trim()&&subject.code!==code.trim()))await db.runAsync('UPDATE subjects SET code=?,title=? WHERE id=?',code.trim()||subject.code,title.trim(),subjectId);
  const id=edit?.id||uid('class');
  await db.runAsync('INSERT OR REPLACE INTO schedule_entries (id,subject_id,title,day,start_time,end_time,kind,room,professor,notes) VALUES (?,?,?,?,?,?,?,?,?,?)',id,subjectId,code.trim()?code.trim()+' · '+title.trim():title.trim(),day,start,end,kind,room.trim(),prof.trim(),notes.trim());
  if(!selectedDays.includes(day)){
   const next=[...selectedDays,day].sort((a,b)=>a-b);setSelectedDays(next);
   await db.runAsync("INSERT OR REPLACE INTO app_settings (key,value) VALUES ('schedule_days',?)",next.join(','));
  }
  setVisible(false);await refresh();
  rescheduleCampusReminders().catch(error=>console.warn('CampusOS reminders could not be refreshed',error));
  updateCampusWidgets().catch(error=>console.warn('CampusOS widgets could not be refreshed',error));
 };
 const del=(row:ClassRow)=>Alert.alert('Delete class?',row.title,[{text:'Cancel'},{text:'Delete',style:'destructive',onPress:async()=>{const db=await database;await db.runAsync('DELETE FROM schedule_entries WHERE id=?',row.id);await refresh();rescheduleCampusReminders().catch(error=>console.warn('CampusOS reminders could not be refreshed',error));updateCampusWidgets().catch(error=>console.warn('CampusOS widgets could not be refreshed',error));updateCampusWidgets().catch(error=>console.warn('CampusOS widgets could not be refreshed',error));}}]);
 const toggleDay=(value:number)=>setSelectedDays(current=>current.includes(value)?current.filter(dayValue=>dayValue!==value):[...current,value].sort((a,b)=>a-b));
 const saveSettings=async()=>{
  const first=Number(startHour),last=Number(endHour);
  if(!Number.isInteger(first)||!Number.isInteger(last)||first<0||first>23||last<1||last>24||last<=first)return Alert.alert('Invalid timetable range','Choose a start hour from 0–23 and a later end hour up to 24.');
  if(selectedDays.length===0)return Alert.alert('Choose class days','At least one timetable day must remain enabled.');
  const db=await database;
  await db.runAsync("INSERT OR REPLACE INTO app_settings (key,value) VALUES ('schedule_days',?)",selectedDays.join(','));
  await db.runAsync("INSERT OR REPLACE INTO app_settings (key,value) VALUES ('schedule_start_hour',?)",String(first));
  await db.runAsync("INSERT OR REPLACE INTO app_settings (key,value) VALUES ('schedule_end_hour',?)",String(last));
  setSettingsVisible(false);
 };
 const startLabel=String(startHour).padStart(2,'0')+':00';
 const endLabel=String(endHour).padStart(2,'0')+':00';
 return <>
  <Card theme={theme} title="Weekly timetable">
   <Text style={{color:theme.muted}}>Lecture and laboratory sessions are checked for overlaps. Adjacent sessions with the same subject, room, and type are grouped in this view.</Text>
   <View style={s.listRow}><View style={{flex:1}}><Text style={{color:theme.text,fontWeight:'800'}}>{selectedDays.length} timetable days</Text><Text style={{color:theme.muted,fontSize:11}}>{startLabel}–{endLabel} · {rows.length} class entries</Text></View><Pressable onPress={()=>setSettingsVisible(true)}><Text style={{color:theme.primary,fontWeight:'800',padding:6}}>Timetable settings</Text></Pressable></View>
   <Button theme={theme} label="＋ Add class" onPress={()=>open()}/>
  </Card>
  {selectedDays.map(index=>{
   const items=mergeAdjacentClasses(rows.filter(row=>row.day===index));
   return <Card key={index} theme={theme} title={days[index]}>
    {items.length?items.map(row=><View key={row.mergedIds.join('|')} style={[s.listRow,{borderBottomColor:theme.border,alignItems:'flex-start'}]}>
     <View style={{width:4,alignSelf:'stretch',borderRadius:4,backgroundColor:theme.primary}}/>
     <View style={{flex:1,gap:3}}>
      <Text style={[s.itemTitle,{color:theme.text}]}>{row.title}</Text>
      <Text style={{color:theme.primary,fontSize:12,fontWeight:'800'}}>{row.start_time}–{row.merged_end_time} · {row.kind}{row.mergedCount>1?' · '+row.mergedCount+' adjacent sessions':''}</Text>
      <Text style={{color:theme.muted,fontSize:12}}>{[row.room,row.professor].filter(Boolean).join(' · ')||'No room assigned'}</Text>
      {row.notes?<Text style={{color:theme.muted,fontSize:12}}>{row.notes}</Text>:null}
     </View>
     <Pressable onPress={()=>open(row)}><Text style={{color:theme.primary,padding:5}}>Edit</Text></Pressable>
     <Pressable onPress={()=>del(row)}><Text style={{color:theme.danger,padding:5}}>×</Text></Pressable>
    </View>):<Text style={{color:theme.muted,fontSize:12}}>No classes</Text>}
   </Card>;
  })}
  <Editor theme={theme} title={edit?'Edit class':'Add class'} visible={visible} onClose={()=>setVisible(false)}>
   <Field theme={theme} label="Subject name" value={title} onChangeText={setTitle}/>
   <Field theme={theme} label="Subject code (optional)" value={code} onChangeText={setCode}/>
   <Text style={{color:theme.muted,fontWeight:'700'}}>Class day</Text>
   <View style={s.chips}>{days.map((label,index)=><Pressable key={label} onPress={()=>setDay(index)} style={[s.chip,{backgroundColor:day===index?theme.primary:theme.surfaceAlt}]}><Text style={{color:day===index?'#FFF':theme.text,fontSize:12}}>{label.slice(0,3)}</Text></Pressable>)}</View>
   <Text style={{color:theme.muted,fontWeight:'700'}}>Type</Text>
   <View style={s.chips}>{['Lecture','Laboratory'].map(value=><Pressable key={value} onPress={()=>setKind(value)} style={[s.chip,{backgroundColor:kind===value?theme.primary:theme.surfaceAlt}]}><Text style={{color:kind===value?'#FFF':theme.text}}>{value}</Text></Pressable>)}</View>
   <Field theme={theme} label="Start time (HH:MM)" value={start} onChangeText={setStart}/>
   <Field theme={theme} label="End time (HH:MM)" value={end} onChangeText={setEnd}/>
   <Field theme={theme} label="Room" value={room} onChangeText={setRoom}/>
   <Field theme={theme} label="Professor" value={prof} onChangeText={setProf}/>
   <Field theme={theme} label="Class notes" value={notes} onChangeText={setNotes} multiline/>
   <Button theme={theme} label="Save class" onPress={save}/>
  </Editor>
  <Editor theme={theme} title="Timetable settings" visible={settingsVisible} onClose={()=>setSettingsVisible(false)}>
   <Text style={{color:theme.muted,fontWeight:'700'}}>Class days</Text>
   <View style={s.chips}>{days.map((label,index)=><Pressable key={label} onPress={()=>toggleDay(index)} style={[s.chip,{backgroundColor:selectedDays.includes(index)?theme.primary:theme.surfaceAlt}]}><Text style={{color:selectedDays.includes(index)?'#FFF':theme.text,fontSize:12}}>{label.slice(0,3)}</Text></Pressable>)}</View>
   <Field theme={theme} label="Timetable start hour (00–23)" value={startHour} onChangeText={setStartHour} keyboardType="numeric"/>
   <Field theme={theme} label="Timetable end hour (01–24)" value={endHour} onChangeText={setEndHour} keyboardType="numeric"/>
   <Text style={{color:theme.muted,fontSize:11}}>These settings control which days are shown and the timetable time range. Existing classes are kept if you hide a day.</Text>
   <Button theme={theme} label="Save timetable settings" onPress={saveSettings}/>
  </Editor>
 </>;
}

type ChecklistItem = { id:string; title:string; done:boolean };
function parseChecklist(raw:string):ChecklistItem[]{
 try { const parsed=JSON.parse(raw||'[]'); return Array.isArray(parsed)?parsed.map((item:any,index:number)=>({id:String(item.id||'subtask-'+index),title:String(item.title||item.text||''),done:Boolean(item.done)})).filter(item=>item.title.trim()):[]; }
 catch { return []; }
}
function formatMonth(value:string):string{
 const [year,month]=value.split('-').map(Number);
 return new Date(year,month-1,1).toLocaleDateString(undefined,{month:'long',year:'numeric'});
}
function calendarDays(monthValue:string):string[]{
 const [year,month]=monthValue.split('-').map(Number);
 const first=new Date(year,month-1,1);
 const daysInMonth=new Date(year,month,0).getDate();
 const leading=first.getDay();
 const count=Math.ceil((leading+daysInMonth)/7)*7;
 return Array.from({length:count},(_,index)=>{
  const day=index-leading+1;
  return day>0&&day<=daysInMonth?dateKey(new Date(year,month-1,day)):'';
 });
}

export function TasksScreen({theme}:P){
 const [rows,setRows]=useState<TaskRow[]>([]);
 const [visible,setVisible]=useState(false);
 const [edit,setEdit]=useState<TaskRow|null>(null);
 const [title,setTitle]=useState(''),[details,setDetails]=useState(''),[checklistText,setChecklistText]=useState('');
 const [date,setDate]=useState(today()),[time,setTime]=useState('23:59');
 const [priority,setPriority]=useState('Medium'),[status,setStatus]=useState('Not started');
 const [selectedDate,setSelectedDate]=useState(today());
 const [shownMonth,setShownMonth]=useState(today().slice(0,7));
 const [showCompleted,setShowCompleted]=useState(true);
 const refresh=useCallback(async()=>{const db=await database;setRows(await db.getAllAsync<TaskRow>('SELECT * FROM tasks ORDER BY done,pinned DESC,due_date,due_time'));},[]);
 useEffect(()=>{refresh();},[refresh]);
 const visibleRows=rows.filter(row=>showCompleted||!row.done);
 const selectedTasks=visibleRows.filter(row=>row.due_date===selectedDate);
 const monthDays=calendarDays(shownMonth);
 const monthCounts=(key:string)=>visibleRows.filter(row=>row.due_date===key).length;
 const open=(row?:TaskRow)=>{
  setEdit(row||null);setTitle(row?.title||'');setDetails(row?.details||'');
  setChecklistText(parseChecklist(row?.checklist||'[]').map(item=>item.title).join('\n'));
  setDate(row?.due_date||selectedDate);setTime(row?.due_time||'23:59');
  setPriority(row?.priority||'Medium');setStatus(row?.done?'Completed':row?.status||'Not started');setVisible(true);
 };
 const save=async()=>{
  if(!title.trim())return Alert.alert('Task title required');
  if(date&&!/^\\d{4}-\\d{2}-\\d{2}$/.test(date))return Alert.alert('Invalid due date','Use YYYY-MM-DD.');
  if(time&&!/^([01]\\d|2[0-3]):[0-5]\\d$/.test(time))return Alert.alert('Invalid reminder time','Use HH:MM.');
  const oldItems=parseChecklist(edit?.checklist||'[]');
  const checklist=checklistText.split(/\\r?\\n/).map(value=>value.trim()).filter(Boolean).map(value=>{
   const previous=oldItems.find(item=>item.title.toLocaleLowerCase()===value.toLocaleLowerCase());
   return {id:previous?.id||uid('subtask'),title:value,done:previous?.done||false};
  });
  const db=await database;const id=edit?.id||uid('task');const done=status==='Completed'?1:0;
  await db.runAsync('INSERT OR REPLACE INTO tasks (id,title,details,checklist,due_date,due_time,done,pinned,priority,status,subject_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)',
   id,title.trim(),details.trim(),JSON.stringify(checklist),date,time,done,edit?.pinned||0,priority,status,edit?.subject_id||null,edit?.created_at||Date.now());
  setVisible(false);await refresh();rescheduleCampusReminders().catch(error=>console.warn('CampusOS reminders could not be refreshed',error));
 };
 const toggleDone=async(row:TaskRow)=>{
  const done=row.done?0:1;const db=await database;
  await db.runAsync('UPDATE tasks SET done=?,status=? WHERE id=?',done,done?'Completed':(row.status==='Completed'?'Not started':row.status),row.id);
  await refresh();rescheduleCampusReminders().catch(error=>console.warn('CampusOS reminders could not be refreshed',error));
 };
 const togglePinned=async(row:TaskRow)=>{const db=await database;await db.runAsync('UPDATE tasks SET pinned=? WHERE id=?',row.pinned?0:1,row.id);refresh();};
 const toggleChecklist=async(row:TaskRow,item:ChecklistItem)=>{
  const items=parseChecklist(row.checklist).map(value=>value.id===item.id?{...value,done:!value.done}:value);
  const db=await database;await db.runAsync('UPDATE tasks SET checklist=? WHERE id=?',JSON.stringify(items),row.id);refresh();
 };
 const del=(row:TaskRow)=>Alert.alert('Delete task?',row.title,[{text:'Cancel'},{text:'Delete',style:'destructive',onPress:async()=>{const db=await database;await db.runAsync('DELETE FROM tasks WHERE id=?',row.id);await refresh();rescheduleCampusReminders().catch(error=>console.warn('CampusOS reminders could not be refreshed',error));}}]);
 const changeMonth=(amount:number)=>{
  const [year,month]=shownMonth.split('-').map(Number);const next=new Date(year,month-1+amount,1);
  setShownMonth(dateKey(next).slice(0,7));
 };
 return <>
  <Card theme={theme} title="Tasks & calendar">
   <Text style={{color:theme.muted}}>Plan deadlines and personal reminders by date. Tasks stay on this device.</Text>
   <View style={[s.listRow,{borderBottomWidth:0}]}>
    <Pressable onPress={()=>changeMonth(-1)} style={[s.chip,{backgroundColor:theme.surfaceAlt}]}><Text style={{color:theme.primary,fontWeight:'800'}}>‹</Text></Pressable>
    <View style={{flex:1,alignItems:'center'}}><Text style={{color:theme.text,fontSize:17,fontWeight:'800'}}>{formatMonth(shownMonth)}</Text></View>
    <Pressable onPress={()=>changeMonth(1)} style={[s.chip,{backgroundColor:theme.surfaceAlt}]}><Text style={{color:theme.primary,fontWeight:'800'}}>›</Text></Pressable>
    <Pressable onPress={()=>{setShownMonth(today().slice(0,7));setSelectedDate(today());}}><Text style={{color:theme.primary,fontWeight:'700',paddingHorizontal:5}}>Today</Text></Pressable>
   </View>
   <View style={{flexDirection:'row',gap:3}}>
    {['Sun','Mon','Tue','Wed','Thu','Fri','Sat'].map(day=><View key={day} style={{flex:1,alignItems:'center',paddingVertical:5}}><Text style={{color:theme.muted,fontSize:10,fontWeight:'700'}}>{day}</Text></View>)}
   </View>
   {Array.from({length:Math.ceil(monthDays.length/7)},(_,week)=>monthDays.slice(week*7,week*7+7)).map((daysInWeek,index)=><View key={index} style={{flexDirection:'row',gap:3}}>
    {daysInWeek.map((key,column)=>{
     const count=key?monthCounts(key):0;const day=key?Number(key.slice(-2)):0;const selected=key===selectedDate;
     return <Pressable key={key||'empty-'+column} disabled={!key} onPress={()=>{setSelectedDate(key);setDate(key);}} style={{flex:1,minHeight:47,borderRadius:11,alignItems:'center',justifyContent:'center',backgroundColor:selected?theme.primary:theme.surfaceAlt,borderWidth:key===today()?1:0,borderColor:theme.primary,marginBottom:3}}>
      <Text style={{color:selected?'#FFF':key?theme.text:theme.muted,fontWeight:key===today()?'900':'600',fontSize:12}}>{day||''}</Text>
      {count>0?<Text style={{color:selected?'#FFF':theme.primary,fontSize:9,fontWeight:'800'}}>{count} task{count===1?'':'s'}</Text>:null}
     </Pressable>;
    })}
   </View>)}
   <View style={[s.listRow,{borderBottomWidth:0,justifyContent:'space-between'}]}>
    <Text style={{color:theme.text,fontWeight:'800'}}>{new Date(Number(selectedDate.slice(0,4)),Number(selectedDate.slice(5,7))-1,Number(selectedDate.slice(8,10))).toLocaleDateString(undefined,{weekday:'long',month:'long',day:'numeric',year:'numeric'})}</Text>
    <Button theme={theme} label="＋ Add task" onPress={()=>open()}/>
   </View>
   <View style={s.chips}>
    <Pressable onPress={()=>setShowCompleted(value=>!value)} style={[s.chip,{backgroundColor:showCompleted?theme.primary:theme.surfaceAlt}]}><Text style={{color:showCompleted?'#FFF':theme.text,fontSize:12}}>{showCompleted?'✓ Showing completed':'Show completed'}</Text></Pressable>
    <Text style={{color:theme.muted,fontSize:11,alignSelf:'center'}}>{selectedTasks.length} task{selectedTasks.length===1?'':'s'}</Text>
   </View>
  </Card>
  {selectedTasks.length===0?<Empty theme={theme} text="No tasks for this date. Select another day or add a new task."/>:selectedTasks.map(row=>{
   const items=parseChecklist(row.checklist);const completed=items.filter(item=>item.done).length;
   return <Card key={row.id} theme={theme}>
    <View style={s.listRow}>
     <Pressable onPress={()=>toggleDone(row)} style={[s.check,{borderColor:theme.primary,backgroundColor:row.done?theme.primary:'transparent'}]}><Text style={{color:'#FFF'}}>{row.done?'✓':''}</Text></Pressable>
     <View style={{flex:1,gap:3}}>
      <Text style={[s.itemTitle,{color:row.done?theme.muted:theme.text,textDecorationLine:row.done?'line-through':'none'}]}>{row.title}</Text>
      <Text style={{color:theme.muted,fontSize:12}}>{row.due_date||'No due date'}{row.due_time?' · '+row.due_time:''} · {row.status|| (row.done?'Completed':'Not started')}</Text>
     </View>
     <Pressable onPress={()=>togglePinned(row)}><Text style={{color:row.pinned?theme.primary:theme.muted,fontSize:18,padding:4}}>{row.pinned?'★':'☆'}</Text></Pressable>
     <Pressable onPress={()=>open(row)}><Text style={{color:theme.primary,padding:4}}>Edit</Text></Pressable>
     <Pressable onPress={()=>del(row)}><Text style={{color:theme.danger,padding:4}}>×</Text></Pressable>
    </View>
    <View style={s.chips}>
     <View style={[s.chip,{backgroundColor:theme.surfaceAlt}]}><Text style={{color:theme.primary,fontSize:11,fontWeight:'800'}}>{row.priority||'Medium'} priority</Text></View>
     {items.length>0?<View style={[s.chip,{backgroundColor:theme.surfaceAlt}]}><Text style={{color:theme.muted,fontSize:11}}>{completed}/{items.length} checklist</Text></View>:null}
    </View>
    {row.details?<Text style={{color:theme.muted,lineHeight:19}}>{row.details}</Text>:null}
    {items.map(item=><Pressable key={item.id} onPress={()=>toggleChecklist(row,item)} style={[s.listRow,{borderBottomColor:theme.border}]}>
     <View style={[s.check,{width:22,height:22,borderColor:theme.primary,backgroundColor:item.done?theme.primary:'transparent'}]}><Text style={{color:'#FFF',fontSize:11}}>{item.done?'✓':''}</Text></View>
     <Text style={{color:item.done?theme.muted:theme.text,textDecorationLine:item.done?'line-through':'none',flex:1,fontSize:13}}>{item.title}</Text>
    </Pressable>)}
   </Card>;
  })}
  <Editor theme={theme} title={edit?'Edit task':'New task'} visible={visible} onClose={()=>setVisible(false)}>
   <Field theme={theme} label="Task title" value={title} onChangeText={setTitle}/>
   <Field theme={theme} label="Details" value={details} onChangeText={setDetails} multiline/>
   <Text style={{color:theme.muted,fontWeight:'700'}}>Priority</Text>
   <View style={s.chips}>{['Low','Medium','High','Urgent'].map(value=><Pressable key={value} onPress={()=>setPriority(value)} style={[s.chip,{backgroundColor:priority===value?theme.primary:theme.surfaceAlt}]}><Text style={{color:priority===value?'#FFF':theme.text,fontSize:12}}>{value}</Text></Pressable>)}</View>
   <Text style={{color:theme.muted,fontWeight:'700'}}>Status</Text>
   <View style={s.chips}>{['Not started','In progress','Completed'].map(value=><Pressable key={value} onPress={()=>setStatus(value)} style={[s.chip,{backgroundColor:status===value?theme.primary:theme.surfaceAlt}]}><Text style={{color:status===value?'#FFF':theme.text,fontSize:12}}>{value}</Text></Pressable>)}</View>
   <Field theme={theme} label="Checklist (one item per line)" value={checklistText} onChangeText={setChecklistText} multiline/>
   <Field theme={theme} label="Due date (YYYY-MM-DD)" value={date} onChangeText={setDate}/>
   <Field theme={theme} label="Reminder time (HH:MM)" value={time} onChangeText={setTime}/>
   <Button theme={theme} label="Save task" onPress={save}/>
  </Editor>
 </>;
}

export function NotesScreen({theme}:P){
 const [rows,setRows]=useState<NoteRow[]>([]);const [visible,setVisible]=useState(false);const [edit,setEdit]=useState<NoteRow|null>(null);const [title,setTitle]=useState(''),[body,setBody]=useState(''),[date,setDate]=useState(today());
 const refresh=useCallback(async()=>{const db=await database;setRows(await db.getAllAsync<NoteRow>('SELECT * FROM notes WHERE subject_id IS NULL ORDER BY favorite DESC,updated_at DESC'));},[]);useEffect(()=>{refresh();},[refresh]);
 const open=(r?:NoteRow)=>{setEdit(r||null);setTitle(r?.title||'');setBody(r?.body||'');setDate(r?.note_date||today());setVisible(true);};
 const save=async()=>{if(!title.trim()&&!body.trim())return Alert.alert('Note is empty');const db=await database;const id=edit?.id||uid('note');const now=Date.now();await db.runAsync('INSERT OR REPLACE INTO notes (id,title,body,subject_id,favorite,note_date,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)',id,title.trim()||'Untitled note',body,edit?.subject_id||null,edit?.favorite||0,date,edit?.created_at||now,now);setVisible(false);refresh();};
 const favorite=async(r:NoteRow)=>{const db=await database;await db.runAsync('UPDATE notes SET favorite=? WHERE id=?',r.favorite?0:1,r.id);refresh();};
 const del=(r:NoteRow)=>Alert.alert('Delete note?',r.title,[{text:'Cancel'},{text:'Delete',style:'destructive',onPress:async()=>{const db=await database;await db.runAsync('DELETE FROM notes WHERE id=?',r.id);refresh();}}]);
 return <><Card theme={theme} title="Notes calendar"><Text style={{color:theme.muted}}>Write, edit, date, and favorite personal notes.</Text><Button theme={theme} label="＋ New note" onPress={()=>open()}/></Card>{rows.length===0?<Empty theme={theme} text="Your notes will appear here."/>:rows.map(r=><Card theme={theme} key={r.id}><View style={s.listRow}><View style={{flex:1,gap:5}}><Text style={[s.itemTitle,{color:theme.text}]}>{r.title}</Text><Text style={{color:theme.muted,fontSize:11}}>{r.note_date||'No date'}{r.favorite?' · Favorite':''}</Text><Text style={{color:theme.text,lineHeight:20}} numberOfLines={4}>{r.body}</Text></View><View style={{gap:4}}><Pressable onPress={()=>favorite(r)}><Text style={{color:theme.primary,padding:4}}>{r.favorite?'★':'☆'}</Text></Pressable><Pressable onPress={()=>open(r)}><Text style={{color:theme.primary,padding:4}}>Edit</Text></Pressable><Pressable onPress={()=>del(r)}><Text style={{color:theme.danger,padding:4}}>×</Text></Pressable></View></View></Card>)}
 <Editor theme={theme} title={edit?'Edit note':'New note'} visible={visible} onClose={()=>setVisible(false)}><Field theme={theme} label="Title" value={title} onChangeText={setTitle}/><Field theme={theme} label="Date (YYYY-MM-DD)" value={date} onChangeText={setDate}/><Field theme={theme} label="Note content" value={body} onChangeText={setBody} multiline/><Button theme={theme} label="Save note" onPress={save}/></Editor></>;
}

function money(value:number):string{return new Intl.NumberFormat('en-PH',{style:'currency',currency:'PHP',maximumFractionDigits:2}).format(value);}
function isInBudgetPeriod(value:string,period:string):boolean{
 const parts=value.split('-').map(Number);if(parts.length!==3||parts.some(x=>!Number.isFinite(x)))return false;
 const entry=new Date(parts[0],parts[1]-1,parts[2]);const now=new Date();
 if(period==='Daily')return dateKey(entry)===dateKey(now);
 if(period==='Monthly')return entry.getFullYear()===now.getFullYear()&&entry.getMonth()===now.getMonth();
 const weekStart=(date:Date)=>{const d=new Date(date.getFullYear(),date.getMonth(),date.getDate());d.setDate(d.getDate()-((d.getDay()+6)%7));return dateKey(d);};
 return weekStart(entry)===weekStart(now);
}
export function BudgetScreen({theme}:P){
 const [rows,setRows]=useState<BudgetRow[]>([]);
 const [goals,setGoals]=useState<SavingsGoal[]>([]);
 const [period,setPeriod]=useState('Weekly');
 const [allowance,setAllowance]=useState('0');
 const [planVisible,setPlanVisible]=useState(false);
 const [planPeriod,setPlanPeriod]=useState('Weekly');
 const [planAmount,setPlanAmount]=useState('');
 const [editorVisible,setEditorVisible]=useState(false);
 const [editing,setEditing]=useState<BudgetRow|null>(null);
 const [type,setType]=useState('expense');
 const [amount,setAmount]=useState('');
 const [category,setCategory]=useState('Food');
 const [note,setNote]=useState('');
 const [entryDate,setEntryDate]=useState(today());
 const [targetId,setTargetId]=useState('');
 const [goalVisible,setGoalVisible]=useState(false);
 const [goalEdit,setGoalEdit]=useState<SavingsGoal|null>(null);
 const [goalTitle,setGoalTitle]=useState('');
 const [goalTarget,setGoalTarget]=useState('');
 const [goalSaved,setGoalSaved]=useState('0');
 const [goalDueDate,setGoalDueDate]=useState('');
 const categories=['Food','Transportation','School','Projects','Bills','Personal','Other'];
 const refresh=useCallback(async()=>{
  const db=await database;
  const [transactions,savingGoals,periodRow,allowanceRow]=await Promise.all([
   db.getAllAsync<BudgetRow>('SELECT * FROM budget_entries ORDER BY date DESC,created_at DESC'),
   db.getAllAsync<SavingsGoal>('SELECT * FROM savings_goals ORDER BY created_at DESC'),
   db.getFirstAsync<{value:string}>("SELECT value FROM app_settings WHERE key='budget_period'"),
   db.getFirstAsync<{value:string}>("SELECT value FROM app_settings WHERE key='budget_allowance'")
  ]);
  setRows(transactions);setGoals(savingGoals);setPeriod(periodRow?.value||'Weekly');setAllowance(allowanceRow?.value||'0');
 },[]);
 useEffect(()=>{refresh();},[refresh]);
 const current=rows.filter(row=>isInBudgetPeriod(row.date,period));
 const totalIncome=current.filter(row=>row.type.toLowerCase()==='income').reduce((sum,row)=>sum+row.amount,0);
 const spent=current.filter(row=>row.type.toLowerCase()==='expense').reduce((sum,row)=>sum+row.amount,0);
 const saved=current.filter(row=>row.type.toLowerCase()==='saving').reduce((sum,row)=>sum+row.amount,0);
 const allowanceValue=Math.max(0,Number(allowance)||0);
 const available=allowanceValue+totalIncome-spent-saved;
 const categoryTotals=categories.map(name=>({name,total:current.filter(row=>row.type.toLowerCase()==='expense'&&row.category===name).reduce((sum,row)=>sum+row.amount,0)})).filter(row=>row.total>0).sort((a,b)=>b.total-a.total);
 const maximumCategory=Math.max(1,...categoryTotals.map(row=>row.total));
 const openPlan=()=>{setPlanPeriod(period);setPlanAmount(allowance);setPlanVisible(true);};
 const savePlan=async()=>{
  const value=Number(planAmount);if(!Number.isFinite(value)||value<0)return Alert.alert('Enter a valid allowance');
  const db=await database;
  await db.runAsync("INSERT OR REPLACE INTO app_settings (key,value) VALUES ('budget_period',?)",planPeriod);
  await db.runAsync("INSERT OR REPLACE INTO app_settings (key,value) VALUES ('budget_allowance',?)",String(value));
  setPlanVisible(false);await refresh();
 };
 const openEntry=(row?:BudgetRow,nextType='expense')=>{
  setEditing(row||null);setType(row?.type?.toLowerCase()||nextType);
  setAmount(row?String(row.amount):'');setCategory(row?.category||'Food');
  setNote(row?.note||'');setEntryDate(row?.date||today());setTargetId(row?.target||'');
  setEditorVisible(true);
 };
 const saveEntry=async()=>{
  const value=Number(amount);if(!Number.isFinite(value)||value<=0)return Alert.alert('Enter a valid amount');
  if(!/^\\d{4}-\\d{2}-\\d{2}$/.test(entryDate))return Alert.alert('Invalid transaction date','Use YYYY-MM-DD.');
  if(type==='saving'&&!targetId)return Alert.alert('Choose a savings goal','Create or select a savings goal for this contribution.');
  const db=await database;const id=editing?.id||uid('budget');const created=editing?.created_at||Date.now();
  await db.withTransactionAsync(async()=>{
   if(editing&&editing.type.toLowerCase()==='saving'&&editing.target)await db.runAsync('UPDATE savings_goals SET saved=MAX(0,saved-?) WHERE id=?',editing.amount,editing.target);
   await db.runAsync('INSERT OR REPLACE INTO budget_entries (id,type,amount,category,note,date,target,created_at) VALUES (?,?,?,?,?,?,?,?)',id,type,value,category,note.trim(),entryDate,type==='saving'?targetId:'',created);
   if(type==='saving')await db.runAsync('UPDATE savings_goals SET saved=saved+? WHERE id=?',value,targetId);
  });
  setEditorVisible(false);await refresh();
 };
 const deleteEntry=(row:BudgetRow)=>Alert.alert('Delete transaction?',row.note||row.category||row.type,[{text:'Cancel'},{text:'Delete',style:'destructive',onPress:async()=>{
  const db=await database;await db.withTransactionAsync(async()=>{
   if(row.type.toLowerCase()==='saving'&&row.target)await db.runAsync('UPDATE savings_goals SET saved=MAX(0,saved-?) WHERE id=?',row.amount,row.target);
   await db.runAsync('DELETE FROM budget_entries WHERE id=?',row.id);
  });await refresh();
 }}]);
 const openGoal=(goal?:SavingsGoal)=>{
  setGoalEdit(goal||null);setGoalTitle(goal?.title||'');setGoalTarget(goal?String(goal.target):'');
  setGoalSaved(goal?String(goal.saved):'0');setGoalDueDate(goal?.due_date||'');setGoalVisible(true);
 };
 const saveGoal=async()=>{
  if(!goalTitle.trim())return Alert.alert('Goal name required');
  const target=Number(goalTarget),savedValue=Number(goalSaved);
  if(!Number.isFinite(target)||target<=0||!Number.isFinite(savedValue)||savedValue<0)return Alert.alert('Enter valid savings amounts');
  const db=await database;const id=goalEdit?.id||uid('goal');
  await db.runAsync('INSERT OR REPLACE INTO savings_goals (id,title,target,saved,due_date,created_at) VALUES (?,?,?,?,?,?)',id,goalTitle.trim(),target,savedValue,goalDueDate,goalEdit?.created_at||Date.now());
  setGoalVisible(false);await refresh();
 };
 const deleteGoal=(goal:SavingsGoal)=>Alert.alert('Delete savings goal?',goal.title,[{text:'Cancel'},{text:'Delete',style:'destructive',onPress:async()=>{
  const db=await database;await db.withTransactionAsync(async()=>{
   await db.runAsync("UPDATE budget_entries SET target='' WHERE target=?",goal.id);
   await db.runAsync('DELETE FROM savings_goals WHERE id=?',goal.id);
  });await refresh();
 }}]);
 const topCategory=categoryTotals[0];
 return <>
  <Card theme={theme} title="Student budget">
   <View style={s.listRow}><View style={{flex:1}}><Text style={{color:theme.muted,fontSize:12}}>{period} allowance · local only</Text><Text style={{color:theme.text,fontSize:30,fontWeight:'900'}}>{money(available)}</Text><Text style={{color:theme.muted,fontSize:12}}>Available balance</Text></View><Pressable onPress={openPlan}><Text style={{color:theme.primary,fontWeight:'800',padding:7}}>Edit plan</Text></Pressable></View>
   <View style={s.moneyRow}>
    <View style={[s.moneyCard,{backgroundColor:theme.surfaceAlt,borderColor:theme.border}]}><Text style={{color:theme.muted,fontSize:11}}>Allowance</Text><Text style={{color:theme.text,fontSize:16,fontWeight:'800'}}>{money(allowanceValue)}</Text></View>
    <View style={[s.moneyCard,{backgroundColor:theme.surfaceAlt,borderColor:theme.border}]}><Text style={{color:theme.muted,fontSize:11}}>Income</Text><Text style={{color:theme.primary,fontSize:16,fontWeight:'800'}}>{money(totalIncome)}</Text></View>
   </View>
   <View style={s.moneyRow}>
    <View style={[s.moneyCard,{backgroundColor:theme.surfaceAlt,borderColor:theme.border}]}><Text style={{color:theme.muted,fontSize:11}}>Spent</Text><Text style={{color:theme.danger,fontSize:16,fontWeight:'800'}}>{money(spent)}</Text></View>
    <View style={[s.moneyCard,{backgroundColor:theme.surfaceAlt,borderColor:theme.border}]}><Text style={{color:theme.muted,fontSize:11}}>Saved</Text><Text style={{color:theme.primary,fontSize:16,fontWeight:'800'}}>{money(saved)}</Text></View>
   </View>
   {allowanceValue+totalIncome>0?<View style={{height:9,backgroundColor:theme.surfaceAlt,borderRadius:10,overflow:'hidden'}}><View style={{height:9,width:((Math.max(0,available)/(allowanceValue+totalIncome))*100)+'%',backgroundColor:theme.primary,borderRadius:10}}/></View>:null}
   <Text style={{color:theme.muted,fontSize:11}}>Remaining amount after expenses and savings for the current {period.toLowerCase()}.</Text>
  </Card>
  <View style={s.quickGrid}>
   {[
    {label:'Add expense',type:'expense',symbol:'−'},
    {label:'Add income',type:'income',symbol:'+'},
    {label:'Add saving',type:'saving',symbol:'₱'}
   ].map(action=><Pressable key={action.type} onPress={()=>openEntry(undefined,action.type)} style={[s.quick,{backgroundColor:theme.surface,borderColor:theme.border}]}>
    <Text style={{color:theme.primary,fontSize:21,fontWeight:'900'}}>{action.symbol}</Text><Text style={{color:theme.text,fontWeight:'800'}}>{action.label}</Text>
   </Pressable>)}
  </View>
  <Card theme={theme} title="Where your money goes">
   {categoryTotals.length?categoryTotals.map(row=><View key={row.name} style={{gap:5}}>
    <View style={s.listRow}><Text style={{color:theme.text,fontWeight:'700',flex:1}}>{row.name}</Text><Text style={{color:theme.muted,fontSize:12}}>{money(row.total)} · {Math.round(row.total/Math.max(1,spent)*100)}%</Text></View>
    <View style={{height:7,backgroundColor:theme.surfaceAlt,borderRadius:8,overflow:'hidden'}}><View style={{height:7,width:(row.total/maximumCategory*100)+'%',backgroundColor:theme.primary,borderRadius:8}}/></View>
   </View>):<Empty theme={theme} text="Record expenses to see your category breakdown."/>}
  </Card>
  <Card theme={theme} title="Quick insight">
   <Text style={{color:theme.text,lineHeight:21}}>{allowanceValue<=0&&totalIncome<=0?'Set an allowance or add income to calculate your available balance.':current.length===0?'No transactions recorded in this '+period.toLowerCase()+' period yet.':topCategory?'Most spent: '+topCategory.name+' ('+money(topCategory.total)+'). You have '+Math.max(0,Math.round(available/Math.max(1,allowanceValue+totalIncome)*100))+'% of your planned money left.':'Keep recording transactions to see an insight.'}</Text>
  </Card>
  <Card theme={theme} title="Savings goals">
   <Button theme={theme} label="＋ Add savings goal" onPress={()=>openGoal()}/>
   {goals.length?goals.map(goal=>{
    const progress=Math.min(1,goal.saved/Math.max(1,goal.target));
    return <View key={goal.id} style={{gap:6,borderTopWidth:1,borderTopColor:theme.border,paddingTop:10}}>
     <View style={s.listRow}><View style={{flex:1,gap:3}}><Text style={{color:theme.text,fontWeight:'800'}}>{goal.title}</Text><Text style={{color:theme.muted,fontSize:11}}>{money(goal.saved)} of {money(goal.target)}{goal.due_date?' · due '+goal.due_date:''}</Text></View><Pressable onPress={()=>openGoal(goal)}><Text style={{color:theme.primary,padding:5}}>Edit</Text></Pressable><Pressable onPress={()=>deleteGoal(goal)}><Text style={{color:theme.danger,padding:5}}>×</Text></Pressable></View>
     <View style={{height:8,backgroundColor:theme.surfaceAlt,borderRadius:8,overflow:'hidden'}}><View style={{height:8,width:(progress*100)+'%',backgroundColor:theme.primary,borderRadius:8}}/></View>
     <Text style={{color:theme.muted,fontSize:11}}>{progress>=1?'Goal reached':money(Math.max(0,goal.target-goal.saved))+' left to reach this goal'}</Text>
    </View>;
   }):<Empty theme={theme} text="Create a goal such as tuition, a new device, or school supplies."/>}
  </Card>
  <Card theme={theme} title="Recent transactions">
   {rows.length?rows.slice(0,12).map(row=><View key={row.id} style={[s.listRow,{borderBottomColor:theme.border}]}>
    <View style={{flex:1,gap:3}}><Text style={{color:theme.text,fontWeight:'800'}}>{row.note||row.category||row.type}</Text><Text style={{color:theme.muted,fontSize:11}}>{row.date} · {row.category||row.type}</Text></View>
    <Text style={{color:row.type.toLowerCase()==='expense'?theme.danger:theme.primary,fontWeight:'800'}}>{row.type.toLowerCase()==='expense'?'−':'+'}{money(row.amount)}</Text>
    <Pressable onPress={()=>openEntry(row)}><Text style={{color:theme.primary,padding:5}}>Edit</Text></Pressable>
    <Pressable onPress={()=>deleteEntry(row)}><Text style={{color:theme.danger,padding:5}}>×</Text></Pressable>
   </View>):<Empty theme={theme} text="No transactions recorded yet."/>}
  </Card>
  <Editor theme={theme} title="Allowance plan" visible={planVisible} onClose={()=>setPlanVisible(false)}>
   <Text style={{color:theme.muted,fontWeight:'700'}}>Period</Text>
   <View style={s.chips}>{['Daily','Weekly','Monthly'].map(value=><Pressable key={value} onPress={()=>setPlanPeriod(value)} style={[s.chip,{backgroundColor:planPeriod===value?theme.primary:theme.surfaceAlt}]}><Text style={{color:planPeriod===value?'#FFF':theme.text}}>{value}</Text></Pressable>)}</View>
   <Field theme={theme} label="Allowance amount (PHP)" value={planAmount} onChangeText={setPlanAmount} keyboardType="decimal-pad"/>
   <Button theme={theme} label="Save allowance" onPress={savePlan}/>
  </Editor>
  <Editor theme={theme} title={editing?'Edit transaction':'New transaction'} visible={editorVisible} onClose={()=>setEditorVisible(false)}>
   <View style={s.chips}>{[['expense','Expense'],['income','Income'],['saving','Saving']].map(([value,label])=><Pressable key={value} onPress={()=>setType(value)} style={[s.chip,{backgroundColor:type===value?theme.primary:theme.surfaceAlt}]}><Text style={{color:type===value?'#FFF':theme.text}}>{label}</Text></Pressable>)}</View>
   <Field theme={theme} label="Amount (PHP)" value={amount} onChangeText={setAmount} keyboardType="decimal-pad"/>
   <Field theme={theme} label="Category" value={category} onChangeText={setCategory}/>
   <Field theme={theme} label="Description" value={note} onChangeText={setNote}/>
   <Field theme={theme} label="Date (YYYY-MM-DD)" value={entryDate} onChangeText={setEntryDate}/>
   {type==='saving'?<><Text style={{color:theme.muted,fontWeight:'700'}}>Savings goal</Text><View style={s.chips}>{goals.map(goal=><Pressable key={goal.id} onPress={()=>setTargetId(goal.id)} style={[s.chip,{backgroundColor:targetId===goal.id?theme.primary:theme.surfaceAlt}]}><Text style={{color:targetId===goal.id?'#FFF':theme.text}}>{goal.title}</Text></Pressable>)}</View></>:null}
   <Button theme={theme} label="Save transaction" onPress={saveEntry}/>
  </Editor>
  <Editor theme={theme} title={goalEdit?'Edit savings goal':'New savings goal'} visible={goalVisible} onClose={()=>setGoalVisible(false)}>
   <Field theme={theme} label="Goal name" value={goalTitle} onChangeText={setGoalTitle}/>
   <Field theme={theme} label="Target amount (PHP)" value={goalTarget} onChangeText={setGoalTarget} keyboardType="decimal-pad"/>
   <Field theme={theme} label="Already saved (PHP)" value={goalSaved} onChangeText={setGoalSaved} keyboardType="decimal-pad"/>
   <Field theme={theme} label="Target date (YYYY-MM-DD, optional)" value={goalDueDate} onChangeText={setGoalDueDate}/>
   <Button theme={theme} label="Save goal" onPress={saveGoal}/>
  </Editor>
 </>;
}

const TEXT_FILE_EXTENSIONS=new Set(['txt','md','markdown','csv','json','log','xml','html','htm','yaml','yml','ini','properties']);
function fileExtension(name:string):string{return name.includes('.')?name.split('.').pop()!.toLowerCase():'';}
function isTextFile(row:FileRow):boolean{return (row.mime_type||'').toLowerCase().startsWith('text/')||TEXT_FILE_EXTENSIONS.has(fileExtension(row.name));}
export function FilesScreen({theme}:P){
 const [rows,setRows]=useState<FileRow[]>([]);
 const [query,setQuery]=useState('');
 const [favoritesOnly,setFavoritesOnly]=useState(false);
 const [previewVisible,setPreviewVisible]=useState(false);
 const [previewTitle,setPreviewTitle]=useState('');
 const [previewContent,setPreviewContent]=useState('');
 const [previewBusy,setPreviewBusy]=useState(false);
 const refresh=useCallback(async()=>{
  const db=await database;
  setRows(await db.getAllAsync<FileRow>('SELECT * FROM lecture_files ORDER BY favorite DESC,added_at DESC'));
 },[]);
 useEffect(()=>{refresh();},[refresh]);
 const add=async()=>{
  try{
   const result=await DocumentPicker.getDocumentAsync({copyToCacheDirectory:true,multiple:false});
   if(result.canceled||!result.assets?.[0])return;
   const asset=result.assets[0],id=uid('file');
   const localUri=await persistPickedFile(asset.uri,asset.name,id);
   const db=await database;
   await db.runAsync('INSERT INTO lecture_files (id,subject_id,name,mime_type,local_uri,size,added_at,folder_name,favorite) VALUES (?,?,?,?,?,?,?,?,?)',id,null,asset.name,asset.mimeType||'',localUri,asset.size||0,Date.now(),'General',0);
   await refresh();
  }catch(error){Alert.alert('Unable to import file',error instanceof Error?error.message:'Try again.');}
 };
 const toggleFavorite=async(row:FileRow)=>{
  const db=await database;
  await db.runAsync('UPDATE lecture_files SET favorite=? WHERE id=?',row.favorite?0:1,row.id);
  await refresh();
 };
 const preview=async(row:FileRow)=>{
  setPreviewBusy(true);
  try{
   if(isTextFile(row)){
    if(row.size>2_000_000){Alert.alert('File is too large for text preview','Open or share this file with a compatible app instead.');return;}
    let content=await FileSystem.readAsStringAsync(row.local_uri,{encoding:FileSystem.EncodingType.UTF8});
    if(fileExtension(row.name)==='json'){
     try{content=JSON.stringify(JSON.parse(content),null,2);}catch{}
    }
    const limit=120_000;
    setPreviewContent(content.length>limit?content.slice(0,limit)+'\n\n[Preview shortened. Open the original file to see the remaining content.]':content);
    setPreviewTitle(row.name);setPreviewVisible(true);
    return;
   }
   const file=new ExpoFile(row.local_uri);
   const options={title:row.name,...(row.mime_type?{mimeType:row.mime_type}:{})};
   if(await file.canPreview(options)){
    await file.preview(options);
   }else{
    if(await Sharing.isAvailableAsync())await Sharing.shareAsync(row.local_uri,{mimeType:row.mime_type||'application/octet-stream',dialogTitle:'Open '+row.name});
    else Alert.alert('No compatible viewer','Install a PDF or Office viewer app, then try opening this file again.');
   }
  }catch(error){
   try{
    if(await Sharing.isAvailableAsync())await Sharing.shareAsync(row.local_uri,{mimeType:row.mime_type||'application/octet-stream',dialogTitle:'Open '+row.name});
    else throw error;
   }catch{Alert.alert('Unable to preview file',error instanceof Error?error.message:'No compatible app could open this document.');}
  }finally{setPreviewBusy(false);}
 };
 const del=(row:FileRow)=>Alert.alert('Remove file?',row.name,[{text:'Cancel'},{text:'Remove',style:'destructive',onPress:async()=>{
  const db=await database;
  await db.runAsync('DELETE FROM lecture_files WHERE id=?',row.id);
  await removeStoredFile(row.local_uri);
  await refresh();
 }}]);
 const filtered=rows.filter(row=>(!favoritesOnly||Boolean(row.favorite))&&row.name.toLowerCase().includes(query.trim().toLowerCase()));
 return <>
  <Card theme={theme} title="Lecture files">
   <Text style={{color:theme.muted,lineHeight:19}}>Keep class documents on this device. Text files open inside CampusOS; PDFs and Office files use an installed compatible viewer.</Text>
   <Button theme={theme} label="＋ Import document" onPress={add}/>
   <Field theme={theme} label="Search files" value={query} onChangeText={setQuery}/>
   <View style={s.listRow}>
    <Text style={{color:theme.muted,fontSize:11,flex:1}}>{filtered.length} of {rows.length} files</Text>
    <Pressable onPress={()=>setFavoritesOnly(value=>!value)} style={[s.chip,{backgroundColor:favoritesOnly?theme.primary:theme.surfaceAlt}]}>
     <Text style={{color:favoritesOnly?'#FFF':theme.text,fontSize:11,fontWeight:'700'}}>{favoritesOnly?'★ Favorites':'☆ All files'}</Text>
    </Pressable>
   </View>
  </Card>
  {filtered.length?filtered.map(row=><Card key={row.id} theme={theme}>
   <View style={s.listRow}>
    <View style={{flex:1,gap:4}}>
     <Text style={{color:theme.text,fontWeight:'800'}}>{row.name}</Text>
     <Text style={{color:theme.muted,fontSize:11}}>{row.mime_type||'Document'} · {Math.round(row.size/1024)} KB · {isTextFile(row)?'In-app text preview':'Native document preview'}</Text>
    </View>
    <Pressable onPress={()=>toggleFavorite(row)}><Text style={{color:theme.primary,padding:5,fontSize:17}}>{row.favorite?'★':'☆'}</Text></Pressable>
    <Pressable disabled={previewBusy} onPress={()=>preview(row)}><Text style={{color:theme.primary,padding:5,fontWeight:'700'}}>{previewBusy?'…':'Open'}</Text></Pressable>
    <Pressable onPress={()=>del(row)}><Text style={{color:theme.danger,padding:5}}>×</Text></Pressable>
   </View>
  </Card>):<Empty theme={theme} text={rows.length?(favoritesOnly?'No favorite files yet.':'No files match this search.'):'No lecture files added yet.'}/>}
  <Editor theme={theme} title={previewTitle||'Document preview'} visible={previewVisible} onClose={()=>setPreviewVisible(false)}>
   <ScrollView style={{maxHeight:520}} contentContainerStyle={{padding:12}}>
    <Text selectable style={{color:theme.text,fontFamily:'monospace',fontSize:12,lineHeight:18}}>{previewContent}</Text>
   </ScrollView>
   <Button theme={theme} secondary label="Share original file" onPress={()=>Sharing.shareAsync(rows.find(row=>row.name===previewTitle)?.local_uri||'',{dialogTitle:'Share '+previewTitle}).catch(()=>Alert.alert('Unable to share file'))}/>
  </Editor>
 </>;
}

function BackupManager({theme}:{theme:Theme}){
 const [visible,setVisible]=useState(false);
 const [selected,setSelected]=useState<Set<BackupModule>>(new Set(backupModules.map(x=>x.key)));
 const [busy,setBusy]=useState(false);
 const toggle=(key:BackupModule,value:boolean)=>setSelected(current=>{const next=new Set(current);if(value)next.add(key);else next.delete(key);return next;});
 const exportBackup=async()=>{if(selected.size===0)return Alert.alert('Select at least one module');setBusy(true);try{await shareCampusBackup(selected);}catch(e){Alert.alert('Backup failed',e instanceof Error?e.message:'Unable to create the backup.');}finally{setBusy(false);}};
 const importBackup=async()=>{if(selected.size===0)return Alert.alert('Select at least one module');try{const picked=await DocumentPicker.getDocumentAsync({type:'application/json',copyToCacheDirectory:true,multiple:false});if(picked.canceled||!picked.assets?.[0])return;setBusy(true);const json=await FileSystem.readAsStringAsync(picked.assets[0].uri,{encoding:FileSystem.EncodingType.UTF8});await restoreCampusBackup(json,selected);Alert.alert('Restore completed','Selected CampusOS modules were restored to this device.');setVisible(false);}catch(e){Alert.alert('Restore failed',e instanceof Error?e.message:'This backup could not be restored.');}finally{setBusy(false);}};
 return <><Card theme={theme} title="Backup & recovery"><Text style={{color:theme.muted,lineHeight:20}}>Export local planner data or restore a CampusOS JSON backup. You can restore selected modules without replacing the rest of your planner.</Text><Button theme={theme} label="Open Backup & Recovery" onPress={()=>setVisible(true)}/></Card><Editor theme={theme} title="Backup & Recovery" visible={visible} onClose={()=>setVisible(false)}><Text style={{color:theme.muted}}>Choose which modules to include when exporting or restoring.</Text>{backupModules.map(item=><View key={item.key} style={[s.listRow,{borderBottomColor:theme.border}]}><View style={{flex:1,gap:3}}><Text style={{color:theme.text,fontWeight:'800'}}>{item.label}</Text><Text style={{color:theme.muted,fontSize:11,lineHeight:16}}>{item.description}</Text></View><Switch value={selected.has(item.key)} onValueChange={value=>toggle(item.key,value)} trackColor={{true:theme.primary}}/></View>)}<Button theme={theme} label={busy?'Working…':'Export selected modules'} onPress={exportBackup}/><Button theme={theme} secondary label={busy?'Working…':'Restore selected modules'} onPress={importBackup}/><Text style={{color:theme.muted,fontSize:11}}>Legacy CampusOS JSON backups are supported. File attachments are included in the Academics module.</Text></Editor></>;
}

export function SettingsScreen({theme,palette,onPalette,dark,onDark}:P&{palette:PaletteName;onPalette:(p:PaletteName)=>void;dark:boolean;onDark:(v:boolean)=>void}){
 const [name,setName]=useState(''),[student,setStudent]=useState(''),[section,setSection]=useState('');
 const [homeTiles,setHomeTiles]=useState<HomeTileRow[]>(DEFAULT_HOME_TILES);
 const [remindersEnabled,setRemindersEnabled]=useState(false),[notificationPermission,setNotificationPermission]=useState(false),[reminderBusy,setReminderBusy]=useState(false);
 const loadHomeTiles=useCallback(async()=>{
  const db=await database;let rows=await db.getAllAsync<HomeTileRow>('SELECT * FROM home_tiles ORDER BY position');
  if(rows.length===0){for(const tile of DEFAULT_HOME_TILES)await db.runAsync('INSERT OR IGNORE INTO home_tiles (tile_key,visible,position) VALUES (?,?,?)',tile.tile_key,tile.visible,tile.position);rows=await db.getAllAsync<HomeTileRow>('SELECT * FROM home_tiles ORDER BY position');}
  setHomeTiles(rows.sort((a,b)=>a.position-b.position));
 },[]);
 const persistHomeTiles=async(next:HomeTileRow[])=>{
  const db=await database;await db.withTransactionAsync(async()=>{for(let index=0;index<next.length;index++){const tile=next[index];await db.runAsync('INSERT OR REPLACE INTO home_tiles (tile_key,visible,position) VALUES (?,?,?)',tile.tile_key,tile.visible,index);}});
  setHomeTiles(next.map((tile,index)=>({...tile,position:index})));
 };
 const toggleHomeTile=async(tile:HomeTileRow,visible:boolean)=>{await persistHomeTiles(homeTiles.map(row=>row.tile_key===tile.tile_key?{...row,visible:visible?1:0}:row));};
 const moveHomeTile=async(index:number,direction:number)=>{
  const next=[...homeTiles];const target=index+direction;if(target<0||target>=next.length)return;
  const temp=next[index];next[index]=next[target];next[target]=temp;await persistHomeTiles(next);
 };
 const resetHomeTiles=async()=>{await persistHomeTiles(DEFAULT_HOME_TILES.map(tile=>({...tile})));};
 useEffect(()=>{loadHomeTiles();},[loadHomeTiles]);
 useEffect(()=>{(async()=>{
  const db=await database;
  const p=await db.getFirstAsync<{full_name:string;student_number:string;year_section:string}>('SELECT * FROM profile WHERE id=1');
  if(p){setName(p.full_name);setStudent(p.student_number);setSection(p.year_section);}
  const value=await db.getFirstAsync<{value:string}>("SELECT value FROM app_settings WHERE key='reminders_enabled'");
  setRemindersEnabled(value?.value==='true');
  setNotificationPermission(await getCampusNotificationPermission());
 })();},[]);
 const save=async()=>{const db=await database;await db.runAsync('UPDATE profile SET full_name=?,student_number=?,year_section=? WHERE id=1',name.trim(),student.trim(),section.trim());Alert.alert('Profile saved','Your information is stored on this device.');};
 const toggleReminders=async(enabled:boolean)=>{
  if(reminderBusy)return;setReminderBusy(true);
  try{
   if(enabled){
    const granted=await requestCampusNotificationPermission();setNotificationPermission(granted);
    if(!granted){Alert.alert('Notifications not allowed','Enable notifications for CampusOS in Android Settings to receive class and task reminders.');return;}
   }
   const db=await database;
   await db.runAsync("INSERT OR REPLACE INTO app_settings (key,value) VALUES ('reminders_enabled',?)",enabled?'true':'false');
   setRemindersEnabled(enabled);
   await rescheduleCampusReminders();
  }catch(error){Alert.alert('Reminder setting failed',error instanceof Error?error.message:'Please try again.');}
  finally{setReminderBusy(false);}
 };
 const homeTileLabels:Record<string,string>={profile:'Profile / welcome',stats:'Statistics',classes:"Today's classes",pinned:'Pinned notes',tasks:'Upcoming tasks'};
 return <><BackupManager theme={theme}/><Card theme={theme} title="Dashboard layout"><Text style={{color:theme.muted,lineHeight:19}}>Choose which Home tiles appear and change their order. These settings stay on this device.</Text>{homeTiles.map((tile,index)=><View key={tile.tile_key} style={[s.listRow,{borderBottomColor:theme.border}]}><View style={{flex:1}}><Text style={{color:theme.text,fontWeight:'700'}}>{homeTileLabels[tile.tile_key]||tile.tile_key}</Text><Text style={{color:theme.muted,fontSize:10}}>Position {index+1}</Text></View><Pressable onPress={()=>moveHomeTile(index,-1)} disabled={index===0}><Text style={{color:index===0?theme.muted:theme.primary,fontSize:17,padding:5}}>↑</Text></Pressable><Pressable onPress={()=>moveHomeTile(index,1)} disabled={index===homeTiles.length-1}><Text style={{color:index===homeTiles.length-1?theme.muted:theme.primary,fontSize:17,padding:5}}>↓</Text></Pressable><Switch value={tile.visible===1} onValueChange={value=>toggleHomeTile(tile,value)} trackColor={{true:theme.primary}}/></View>)}<Button theme={theme} secondary label="Reset dashboard layout" onPress={resetHomeTiles}/></Card><Card theme={theme} title="Profile"><Field theme={theme} label="Name" value={name} onChangeText={setName}/><Field theme={theme} label="Student number" value={student} onChangeText={setStudent}/><Field theme={theme} label="Year & section" value={section} onChangeText={setSection}/><Button theme={theme} label="Save profile" onPress={save}/></Card><Card theme={theme} title="Appearance & design"><Text style={{color:theme.muted}}>Choose your CampusOS color combination.</Text><View style={s.paletteGrid}>{(Object.keys(palettes) as PaletteName[]).map(k=><Pressable key={k} onPress={()=>onPalette(k)} style={[s.palette,{borderColor:palette===k?theme.primary:theme.border,borderWidth:palette===k?2:1}]}><View style={[s.swatch,{backgroundColor:palettes[k].primary}]}/><Text style={{color:theme.text,fontSize:12}}>{palettes[k].label}</Text></Pressable>)}</View><Text style={{color:theme.muted,fontWeight:'700'}}>Display mode</Text><View style={s.chips}>{[['light','Light'],['dark','Dark']].map(([v,l])=><Pressable key={v} onPress={()=>onDark(v==='dark')} style={[s.chip,{backgroundColor:dark===(v==='dark')?theme.primary:theme.surfaceAlt}]}><Text style={{color:dark===(v==='dark')?'#FFF':theme.text}}>{l}</Text></Pressable>)}</View></Card><Card theme={theme} title="Home-screen widgets"><Text style={{color:theme.muted,lineHeight:20}}>Add CampusOS widgets from your Android home screen: touch and hold an empty area, open Widgets, then choose CampusOS · Next Class or CampusOS · Tasks. They refresh when CampusOS opens and when schedule or task data changes.</Text></Card><Card theme={theme} title="Reminders & notifications"><Text style={{color:theme.muted,lineHeight:20}}>Receive reminders before scheduled classes and before task deadlines. Reminders are scheduled locally on this phone; no account or server is used.</Text><View style={[s.listRow,{borderBottomWidth:0}]}><View style={{flex:1,gap:3}}><Text style={{color:theme.text,fontWeight:'800'}}>Class and task reminders</Text><Text style={{color:theme.muted,fontSize:11}}>{notificationPermission?'Android notification permission is granted.': 'Android notification permission is not granted.'}</Text></View><Switch value={remindersEnabled} disabled={reminderBusy} onValueChange={toggleReminders} trackColor={{true:theme.primary}}/></View><Text style={{color:theme.muted,fontSize:11}}>Class alerts are scheduled 30 and 10 minutes before each class. Task alerts are scheduled 1 day and 1 hour before the due time.</Text></Card><Card theme={theme} title="Offline & privacy"><Text style={{color:theme.muted,lineHeight:20}}>This CampusOS version is a personal offline planner. It has no sign-in, admin/member accounts, announcements, group chat, or online campus/community functions.</Text><Text style={{color:theme.muted,fontSize:12}}>App lock and Android home-screen widgets remain in migration.</Text></Card></>;
}


export function AcademicsScreen({theme}:P){
 const [subjects,setSubjects]=useState<SubjectRow[]>([]);
 const [selected,setSelected]=useState<SubjectRow|null>(null);
 const [classes,setClasses]=useState<ClassRow[]>([]);
 const [notes,setNotes]=useState<NoteRow[]>([]);
 const [files,setFiles]=useState<FileRow[]>([]);
 const [folders,setFolders]=useState<string[]>(['Lecture 1','Lecture 2','Lecture 3']);
 const [currentFolder,setCurrentFolder]=useState('Lecture 1');
 const [fileSearch,setFileSearch]=useState('');
 const [subjectEditor,setSubjectEditor]=useState(false);
 const [subjectEdit,setSubjectEdit]=useState<SubjectRow|null>(null);
 const [subjectCode,setSubjectCode]=useState('');
 const [subjectTitle,setSubjectTitle]=useState('');
 const [noteEditor,setNoteEditor]=useState(false);
 const [noteEdit,setNoteEdit]=useState<NoteRow|null>(null);
 const [noteTitle,setNoteTitle]=useState('');
 const [noteBody,setNoteBody]=useState('');
 const [noteDate,setNoteDate]=useState(today());
 const [folderEditor,setFolderEditor]=useState(false);
 const [folderName,setFolderName]=useState('');
 const refreshSubjects=useCallback(async()=>{const db=await database;setSubjects(await db.getAllAsync<SubjectRow>('SELECT * FROM subjects ORDER BY favorite DESC,title COLLATE NOCASE'));},[]);
 const refreshSelected=useCallback(async()=>{
  if(!selected){setClasses([]);setNotes([]);setFiles([]);setFolders(['Lecture 1','Lecture 2','Lecture 3']);return;}
  const db=await database;
  for(const name of ['Lecture 1','Lecture 2','Lecture 3']) await db.runAsync('INSERT OR IGNORE INTO lecture_folders (id,subject_id,name,created_at) VALUES (?,?,?,?)',selected.id+'-'+name.toLowerCase().replace(/[^a-z0-9]+/g,'-'),selected.id,name,Date.now());
  const [c,n,f,folderRows]=await Promise.all([
   db.getAllAsync<ClassRow>('SELECT * FROM schedule_entries WHERE subject_id=? ORDER BY day,start_time',selected.id),
   db.getAllAsync<NoteRow>('SELECT * FROM notes WHERE subject_id=? ORDER BY favorite DESC,updated_at DESC',selected.id),
   db.getAllAsync<FileRow>('SELECT * FROM lecture_files WHERE subject_id=? ORDER BY favorite DESC,added_at DESC',selected.id),
   db.getAllAsync<{name:string}>('SELECT name FROM lecture_folders WHERE subject_id=? ORDER BY created_at,name',selected.id)
  ]);
  setClasses(c);setNotes(n);setFiles(f);setFolders(folderRows.map(row=>row.name));
  if(!folderRows.some(row=>row.name===currentFolder))setCurrentFolder(folderRows[0]?.name||'Lecture 1');
 },[selected,currentFolder]);
 useEffect(()=>{refreshSubjects();},[refreshSubjects]);
 useEffect(()=>{refreshSelected();},[refreshSelected]);
 const openSubject=(row:SubjectRow)=>{setCurrentFolder('Lecture 1');setFileSearch('');setSelected(row);};
 const openSubjectEditor=(row?:SubjectRow)=>{setSubjectEdit(row||null);setSubjectCode(row?.code||'');setSubjectTitle(row?.title||'');setSubjectEditor(true);};
 const saveSubject=async()=>{if(!subjectTitle.trim())return Alert.alert('Subject name required');const db=await database;const id=subjectEdit?.id||uid('subject');const row:SubjectRow={id,legacy_id:subjectEdit?.legacy_id||null,code:subjectCode.trim(),title:subjectTitle.trim(),color:subjectEdit?.color||theme.primary,favorite:subjectEdit?.favorite||0,created_at:subjectEdit?.created_at||Date.now()};await db.runAsync('INSERT OR REPLACE INTO subjects (id,legacy_id,code,title,color,favorite,created_at) VALUES (?,?,?,?,?,?,?)',row.id,row.legacy_id??null,row.code,row.title,row.color,row.favorite??0,row.created_at);setSubjectEditor(false);setSelected(row);await refreshSubjects();};
 const deleteSubject=(row:SubjectRow)=>Alert.alert('Delete subject?',row.title,[{text:'Cancel'},{text:'Delete',style:'destructive',onPress:async()=>{const db=await database;await db.runAsync('DELETE FROM subjects WHERE id=?',row.id);if(selected?.id===row.id)setSelected(null);await refreshSubjects();}}]);
 const toggleSubjectFavorite=async(row:SubjectRow)=>{const db=await database;await db.runAsync('UPDATE subjects SET favorite=? WHERE id=?',row.favorite?0:1,row.id);await refreshSubjects();};
 const openNote=(row?:NoteRow)=>{setNoteEdit(row||null);setNoteTitle(row?.title||'');setNoteBody(row?.body||'');setNoteDate(row?.note_date||today());setNoteEditor(true);};
 const saveNote=async()=>{if(!selected)return;if(!noteTitle.trim()&&!noteBody.trim())return Alert.alert('Note is empty');const db=await database;const id=noteEdit?.id||uid('note');const now=Date.now();await db.runAsync('INSERT OR REPLACE INTO notes (id,title,body,subject_id,favorite,note_date,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)',id,noteTitle.trim()||'Untitled note',noteBody,selected.id,noteEdit?.favorite||0,noteDate,noteEdit?.created_at||now,now);setNoteEditor(false);await refreshSelected();};
 const toggleFavorite=async(row:NoteRow)=>{const db=await database;await db.runAsync('UPDATE notes SET favorite=? WHERE id=?',row.favorite?0:1,row.id);await refreshSelected();};
 const deleteNote=(row:NoteRow)=>Alert.alert('Delete note?',row.title,[{text:'Cancel'},{text:'Delete',style:'destructive',onPress:async()=>{const db=await database;await db.runAsync('DELETE FROM notes WHERE id=?',row.id);await refreshSelected();}}]);
 const importFile=async()=>{if(!selected)return;try{const picked=await DocumentPicker.getDocumentAsync({copyToCacheDirectory:true,multiple:false});if(picked.canceled||!picked.assets?.[0])return;const asset=picked.assets[0];const id=uid('file');const localUri=await persistPickedFile(asset.uri,asset.name,id);const db=await database;await db.runAsync('INSERT INTO lecture_files (id,subject_id,name,mime_type,local_uri,size,added_at,folder_name,favorite) VALUES (?,?,?,?,?,?,?,?,?)',id,selected.id,asset.name,asset.mimeType||'',localUri,asset.size||0,Date.now(),currentFolder,0);await refreshSelected();}catch(e){Alert.alert('Unable to import file',e instanceof Error?e.message:'Try again.');}};
 const toggleFileFavorite=async(row:FileRow)=>{const db=await database;await db.runAsync('UPDATE lecture_files SET favorite=? WHERE id=?',row.favorite?0:1,row.id);await refreshSelected();};
 const deleteFile=(row:FileRow)=>Alert.alert('Remove lecture file?',row.name,[{text:'Cancel'},{text:'Remove',style:'destructive',onPress:async()=>{const db=await database;await db.runAsync('DELETE FROM lecture_files WHERE id=?',row.id);await removeStoredFile(row.local_uri);await refreshSelected();}}]);
 const createFolder=async()=>{const name=folderName.trim();if(!selected||!name)return Alert.alert('Folder name required');const db=await database;try{await db.runAsync('INSERT INTO lecture_folders (id,subject_id,name,created_at) VALUES (?,?,?,?)',uid('folder'),selected.id,name,Date.now());setFolderName('');setFolderEditor(false);setCurrentFolder(name);await refreshSelected();}catch{Alert.alert('Folder already exists','Choose a different folder name.');}};
 const visibleFiles=files.filter(row=>(row.folder_name||'Lecture 1')===currentFolder&&(fileSearch.trim()===''||row.name.toLowerCase().includes(fileSearch.toLowerCase())));
 if(selected)return <>
  <Card theme={theme}><View style={s.listRow}><Button theme={theme} secondary label="‹ Subjects" onPress={()=>setSelected(null)}/><View style={{flex:1}}/><Pressable onPress={()=>openSubjectEditor(selected)}><Text style={{color:theme.primary,fontWeight:'700'}}>Edit</Text></Pressable></View><Text style={{color:theme.text,fontSize:24,fontWeight:'800'}}>{selected.code?selected.code+' · ':''}{selected.title}</Text><Text style={{color:theme.muted}}>Subject workspace · stored offline</Text></Card>
  <Card theme={theme} title="Class sessions">{classes.length?classes.map(row=><View key={row.id} style={[s.listRow,{borderBottomColor:theme.border}]}><View style={{flex:1}}><Text style={{color:theme.text,fontWeight:'700'}}>{days[row.day]||'Class'} · {row.kind}</Text><Text style={{color:theme.primary,fontSize:12,fontWeight:'700'}}>{row.start_time}–{row.end_time}</Text><Text style={{color:theme.muted,fontSize:12}}>{[row.room,row.professor].filter(Boolean).join(' · ')}</Text></View></View>):<Empty theme={theme} text="No schedule sessions linked to this subject yet."/>}</Card>
  <Card theme={theme} title="Subject Notepad"><Text style={{color:theme.muted}}>Notes added here are linked to this subject and also appear in Notepad search.</Text><Button theme={theme} label="＋ Add subject note" onPress={()=>openNote()}/>{notes.length?notes.map(row=><View key={row.id} style={[s.listRow,{borderBottomColor:theme.border,alignItems:'flex-start'}]}><View style={{flex:1,gap:4}}><Text style={{color:theme.text,fontWeight:'800'}}>{row.title}</Text><Text style={{color:theme.muted,fontSize:11}}>{row.note_date||'No date'}{row.favorite?' · Favorite':''}</Text><Text style={{color:theme.text,lineHeight:19}} numberOfLines={3}>{row.body}</Text></View><View style={{gap:4}}><Pressable onPress={()=>toggleFavorite(row)}><Text style={{color:theme.primary,padding:4}}>{row.favorite?'★':'☆'}</Text></Pressable><Pressable onPress={()=>openNote(row)}><Text style={{color:theme.primary,padding:4}}>Edit</Text></Pressable><Pressable onPress={()=>deleteNote(row)}><Text style={{color:theme.danger,padding:4}}>×</Text></Pressable></View></View>):<Empty theme={theme} text="No subject notes yet."/>}</Card>
  <Card theme={theme} title="Lecture Files"><Text style={{color:theme.muted}}>Files are copied into private app storage so they remain available offline.</Text><Button theme={theme} label="＋ Add lecture file" onPress={importFile}/><View style={s.chips}>{folders.map(name=><Pressable key={name} onPress={()=>setCurrentFolder(name)} style={[s.chip,{backgroundColor:currentFolder===name?theme.primary:theme.surfaceAlt}]}><Text style={{color:currentFolder===name?'#FFF':theme.text,fontSize:12}}>{name}</Text></Pressable>)}<Pressable onPress={()=>setFolderEditor(true)} style={[s.chip,{backgroundColor:theme.surfaceAlt}]}><Text style={{color:theme.primary,fontWeight:'700'}}>＋ Folder</Text></Pressable></View><Field theme={theme} label="Search files in this folder" value={fileSearch} onChangeText={setFileSearch}/>{visibleFiles.length?visibleFiles.map(row=><View key={row.id} style={[s.listRow,{borderBottomColor:theme.border}]}><View style={{flex:1}}><Text style={{color:theme.text,fontWeight:'700'}}>{row.name}</Text><Text style={{color:theme.muted,fontSize:11}}>{row.mime_type||'Document'} · {Math.round(row.size/1024)} KB</Text></View><Pressable onPress={()=>toggleFileFavorite(row)}><Text style={{color:theme.primary,padding:5}}>{row.favorite?'★':'☆'}</Text></Pressable><Pressable onPress={()=>Sharing.shareAsync(row.local_uri,{mimeType:row.mime_type||'application/octet-stream',dialogTitle:row.name}).catch(()=>Alert.alert('File unavailable','No compatible app could open this document.'))}><Text style={{color:theme.primary,padding:5}}>Open</Text></Pressable><Pressable onPress={()=>deleteFile(row)}><Text style={{color:theme.danger,padding:5}}>×</Text></Pressable></View>):<Empty theme={theme} text={fileSearch?'No files match this search.':'No lecture files in this folder.'}/>}</Card>
  <Editor theme={theme} title="Edit subject" visible={subjectEditor} onClose={()=>setSubjectEditor(false)}><Field theme={theme} label="Subject code" value={subjectCode} onChangeText={setSubjectCode}/><Field theme={theme} label="Subject name" value={subjectTitle} onChangeText={setSubjectTitle}/><Button theme={theme} label="Save subject" onPress={saveSubject}/></Editor>
  <Editor theme={theme} title={noteEdit?'Edit subject note':'New subject note'} visible={noteEditor} onClose={()=>setNoteEditor(false)}><Field theme={theme} label="Note title" value={noteTitle} onChangeText={setNoteTitle}/><Field theme={theme} label="Date (YYYY-MM-DD)" value={noteDate} onChangeText={setNoteDate}/><Field theme={theme} label="Note content" value={noteBody} onChangeText={setNoteBody} multiline/><Button theme={theme} label="Save note" onPress={saveNote}/></Editor>
  <Editor theme={theme} title="Create lecture folder" visible={folderEditor} onClose={()=>setFolderEditor(false)}><Field theme={theme} label="Folder name" value={folderName} onChangeText={setFolderName}/><Button theme={theme} label="Create folder" onPress={createFolder}/></Editor>
 </>;
 return <><Card theme={theme} title="Academics"><Text style={{color:theme.muted}}>Organize subjects, subject Notepads, class sessions, and lecture materials in one offline workspace.</Text><Button theme={theme} label="＋ Add subject" onPress={()=>openSubjectEditor()}/></Card>{subjects.length?subjects.map(row=><Card theme={theme} key={row.id}><Pressable onPress={()=>openSubject(row)} style={s.listRow}><View style={{flex:1,gap:4}}><Text style={{color:theme.text,fontSize:15,fontWeight:'800'}}>{row.code?row.code+' · ':''}{row.title}</Text><Text style={{color:theme.muted,fontSize:12}}>Open subject workspace</Text></View><Text style={{color:theme.primary,fontSize:20}}>›</Text></Pressable><View style={s.listRow}><Pressable onPress={()=>toggleSubjectFavorite(row)}><Text style={{color:theme.primary,padding:8,fontWeight:'700'}}>{row.favorite?'★':'☆'}</Text></Pressable><Button theme={theme} secondary label="Open" onPress={()=>openSubject(row)}/><Button theme={theme} secondary label="Edit" onPress={()=>openSubjectEditor(row)}/><Pressable onPress={()=>deleteSubject(row)}><Text style={{color:theme.danger,padding:10,fontWeight:'700'}}>Delete</Text></Pressable></View></Card>):<Empty theme={theme} text="No subjects yet. Add a subject or create a class in Schedule; it will appear here."/>}<Editor theme={theme} title={subjectEdit?'Edit subject':'Add subject'} visible={subjectEditor} onClose={()=>setSubjectEditor(false)}><Field theme={theme} label="Subject code" value={subjectCode} onChangeText={setSubjectCode}/><Field theme={theme} label="Subject name" value={subjectTitle} onChangeText={setSubjectTitle}/><Button theme={theme} label="Save subject" onPress={saveSubject}/></Editor></>;
}


const s=StyleSheet.create({
 card:{borderRadius:20,borderWidth:1,padding:16,gap:11,marginBottom:12},heading:{fontSize:16,fontWeight:'800'},input:{minHeight:46,borderWidth:1,borderRadius:12,paddingHorizontal:12,paddingVertical:10,fontSize:14},button:{minHeight:44,borderRadius:13,paddingHorizontal:16,paddingVertical:12,alignItems:'center',justifyContent:'center'},
 hero:{borderRadius:24,padding:20,marginBottom:12},eyebrow:{color:'#D8F0DF',fontSize:10,letterSpacing:1.5,fontWeight:'700'},heroTitle:{color:'#FFF',fontSize:30,fontWeight:'800',marginTop:8},heroSub:{color:'#E4F4E8',fontSize:13,marginTop:4},statLine:{flexDirection:'row',gap:8,marginTop:18},stat:{flex:1,backgroundColor:'#FFFFFF20',borderRadius:14,padding:10},statNumber:{color:'#FFF',fontSize:20,fontWeight:'800'},statLabel:{color:'#E4F4E8',fontSize:10,marginTop:3},
 quickGrid:{flexDirection:'row',flexWrap:'wrap',gap:10,marginBottom:12},quick:{width:'48%',minHeight:84,borderRadius:17,borderWidth:1,padding:14,gap:5},empty:{minHeight:74,borderRadius:14,padding:18,alignItems:'center',justifyContent:'center'},itemTitle:{fontSize:14,fontWeight:'800'},listRow:{flexDirection:'row',alignItems:'center',gap:8,paddingVertical:6,borderBottomWidth:StyleSheet.hairlineWidth},check:{width:25,height:25,borderRadius:8,borderWidth:1.5,alignItems:'center',justifyContent:'center'},
 moneyRow:{flexDirection:'row',gap:10,marginBottom:12},moneyCard:{flex:1,borderRadius:18,borderWidth:1,padding:14,gap:8},chips:{flexDirection:'row',flexWrap:'wrap',gap:7},chip:{paddingHorizontal:12,paddingVertical:9,borderRadius:20},
 shade:{flex:1,justifyContent:'flex-end',backgroundColor:'#0008'},editor:{maxHeight:'92%',borderTopLeftRadius:24,borderTopRightRadius:24,padding:18,gap:14},editorHead:{flexDirection:'row',alignItems:'center',paddingBottom:4},paletteGrid:{flexDirection:'row',flexWrap:'wrap',gap:10},palette:{width:'30%',borderRadius:12,padding:8,gap:6},swatch:{height:28,borderRadius:8}
});
