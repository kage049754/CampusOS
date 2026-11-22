import React,{useEffect,useMemo,useState} from 'react';
import {ActivityIndicator,Pressable,SafeAreaView,ScrollView,StatusBar,StyleSheet,Text,View} from 'react-native';
import {database,initializeDatabase} from './src/database';
import {PaletteName,resolveTheme} from './src/theme';
import {BudgetScreen,FilesScreen,HomeScreen,NotesScreen,ScheduleScreen,SettingsScreen,TasksScreen} from './src/screens';

type ModuleName='Home'|'Schedule'|'Tasks'|'Budget'|'Notes'|'Files'|'Settings';
const modules:{key:ModuleName;symbol:string}[]=[{key:'Home',symbol:'⌂'},{key:'Schedule',symbol:'▦'},{key:'Tasks',symbol:'✓'},{key:'Budget',symbol:'₱'},{key:'Notes',symbol:'▤'},{key:'Files',symbol:'▣'},{key:'Settings',symbol:'⚙'}];
export default function App(){
 const [ready,setReady]=useState(false);const [active,setActive]=useState<ModuleName>('Home');const [palette,setPalette]=useState<PaletteName>('forest');const [dark,setDark]=useState(false);
 const theme=useMemo(()=>resolveTheme(palette,dark?'dark':'light'),[palette,dark]);
 useEffect(()=>{
  let active=true;
  (async()=>{
   await initializeDatabase();
   const db=await database;
   const settings=await db.getAllAsync<{key:string;value:string}>('SELECT key,value FROM app_settings');
   const values=Object.fromEntries(settings.map(x=>[x.key,x.value]));
   const supported:PaletteName[]=['forest','sunset','monochrome','ocean','mint','sky','lavender','plum','rose','coral','amber','teal'];
   if(active&&supported.includes(values.palette as PaletteName))setPalette(values.palette as PaletteName);
   if(active)setDark(values.display_mode==='dark');
   if(active)setReady(true);
  })().catch(e=>{console.error('CampusOS database initialization failed',e);if(active)setReady(true);});
  return ()=>{active=false;};
 },[]);
 const persistSetting=async(key:string,value:string)=>{
  const db=await database;
  await db.runAsync('INSERT OR REPLACE INTO app_settings (key,value) VALUES (?,?)',key,value);
 };
 const changePalette=(value:PaletteName)=>{setPalette(value);persistSetting('palette',value).catch(e=>console.error('Could not save palette',e));};
 const changeDark=(value:boolean)=>{setDark(value);persistSetting('display_mode',value?'dark':'light').catch(e=>console.error('Could not save display mode',e));};
 if(!ready)return <SafeAreaView style={[styles.loading,{backgroundColor:theme.background}]}><ActivityIndicator color={theme.primary}/><Text style={{color:theme.text}}>Preparing your offline planner…</Text></SafeAreaView>;
 const screen=()=>{switch(active){case'Home':return <HomeScreen theme={theme} go={v=>setActive(v as ModuleName)}/>;case'Schedule':return <ScheduleScreen theme={theme}/>;case'Tasks':return <TasksScreen theme={theme}/>;case'Budget':return <BudgetScreen theme={theme}/>;case'Notes':return <NotesScreen theme={theme}/>;case'Files':return <FilesScreen theme={theme}/>;case'Settings':return <SettingsScreen theme={theme} palette={palette} onPalette={changePalette} dark={dark} onDark={changeDark}/>;}};
 return <SafeAreaView style={[styles.root,{backgroundColor:theme.background}]}><StatusBar barStyle={dark?'light-content':'dark-content'}/><View style={styles.topbar}><View><Text style={[styles.brand,{color:theme.primary}]}>CampusOS</Text><Text style={[styles.sub,{color:theme.muted}]}>PERSONAL PLANNER</Text></View><Pressable onPress={()=>changeDark(!dark)} style={[styles.toggle,{backgroundColor:theme.surfaceAlt}]}><Text style={{color:theme.text}}>{dark?'☀':'☾'}</Text></Pressable></View><ScrollView key={active} contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>{screen()}</ScrollView><View style={[styles.nav,{backgroundColor:theme.surface,borderTopColor:theme.border}]}>{modules.map(m=><Pressable key={m.key} onPress={()=>setActive(m.key)} style={[styles.navItem,active===m.key&&{backgroundColor:theme.surfaceAlt}]}><Text style={{color:active===m.key?theme.primary:theme.muted,fontSize:18,fontWeight:'700'}}>{m.symbol}</Text><Text numberOfLines={1} style={{color:active===m.key?theme.primary:theme.muted,fontSize:10,fontWeight:active===m.key?'700':'500'}}>{m.key}</Text></Pressable>)}</View></SafeAreaView>;
}
const styles=StyleSheet.create({root:{flex:1},loading:{flex:1,alignItems:'center',justifyContent:'center',gap:12},topbar:{minHeight:64,paddingHorizontal:18,paddingVertical:10,flexDirection:'row',alignItems:'center',justifyContent:'space-between'},brand:{fontSize:22,fontWeight:'800',letterSpacing:-.5},sub:{fontSize:9,letterSpacing:2,fontWeight:'700'},toggle:{width:38,height:38,borderRadius:19,alignItems:'center',justifyContent:'center'},content:{paddingHorizontal:16,paddingBottom:28},nav:{flexDirection:'row',justifyContent:'space-around',paddingHorizontal:4,paddingTop:8,paddingBottom:5,borderTopWidth:1},navItem:{flex:1,alignItems:'center',justifyContent:'center',gap:3,paddingVertical:6,borderRadius:12}});
