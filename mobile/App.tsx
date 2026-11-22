import React,{useEffect,useMemo,useRef,useState} from 'react';
import {ActivityIndicator,Alert,AppState,Pressable,SafeAreaView,ScrollView,StatusBar,StyleSheet,Text,TextInput,View} from 'react-native';
import * as SecureStore from 'expo-secure-store';
import * as LocalAuthentication from 'expo-local-authentication';
import {database,initializeDatabase} from './src/database';
import {configureCampusNotifications,rescheduleCampusReminders} from './src/notifications';
import {updateCampusWidgets} from './src/widgetData';
import {PaletteName,resolveTheme} from './src/theme';
import {AcademicsScreen,BudgetScreen,FilesScreen,HomeScreen,NotesScreen,ScheduleScreen,SettingsScreen,TasksScreen} from './src/screens';

type ModuleName='Home'|'Schedule'|'Tasks'|'Academics'|'Budget'|'Notepad'|'Files'|'Settings';
const APP_PIN_KEY='campusos_local_app_pin';
const modules:{key:ModuleName;symbol:string}[]=[{key:'Home',symbol:'⌂'},{key:'Schedule',symbol:'▦'},{key:'Tasks',symbol:'✓'},{key:'Academics',symbol:'A'},{key:'Budget',symbol:'₱'},{key:'Notepad',symbol:'▤'},{key:'Files',symbol:'▣'},{key:'Settings',symbol:'⚙'}];
function CampusLockScreen({theme,pin,biometricEnabled,onUnlock}:{theme:ReturnType<typeof resolveTheme>;pin:string;biometricEnabled:boolean;onUnlock:()=>void}){
 const [entered,setEntered]=useState('');
 const [error,setError]=useState('');
 const [busy,setBusy]=useState(false);
 const unlock=()=>{if(entered===pin){setError('');setEntered('');onUnlock();}else{setEntered('');setError('Incorrect PIN. Try again.');}};
 const biometric=async()=>{
  if(busy)return;setBusy(true);
  try{
   const result=await LocalAuthentication.authenticateAsync({promptMessage:'Unlock CampusOS',cancelLabel:'Use PIN'});
   if(result.success){setError('');onUnlock();}
   else if(!result.success&&!result.warning)setError('Biometric authentication was not completed. Use your PIN.');
  }catch(e){setError(e instanceof Error?e.message:'Biometric unlock is unavailable.');}
  finally{setBusy(false);}
 };
 return <SafeAreaView style={[styles.lockRoot,{backgroundColor:theme.background}]}>
  <View style={[styles.lockCard,{backgroundColor:theme.surface,borderColor:theme.border}]}>
   <View style={[styles.lockIcon,{backgroundColor:theme.surfaceAlt}]}><Text style={{color:theme.primary,fontSize:28,fontWeight:'900'}}>C</Text></View>
   <Text style={[styles.lockTitle,{color:theme.text}]}>CampusOS is locked</Text>
   <Text style={[styles.lockSub,{color:theme.muted}]}>Enter your PIN to open your offline planner.</Text>
   <TextInput value={entered} onChangeText={value=>setEntered(value.replace(/[^0-9]/g,'').slice(0,8))} keyboardType="number-pad" secureTextEntry maxLength={8} placeholder="4–8 digit PIN" placeholderTextColor={theme.muted} style={[styles.pinInput,{backgroundColor:theme.surfaceAlt,borderColor:theme.border,color:theme.text}]}/>
   {error?<Text style={{color:theme.danger,fontSize:12,textAlign:'center'}}>{error}</Text>:null}
   <Pressable onPress={unlock} disabled={entered.length<4} style={[styles.unlockButton,{backgroundColor:theme.primary,opacity:entered.length<4?.55:1}]}><Text style={{color:'#FFF',fontWeight:'800'}}>Unlock CampusOS</Text></Pressable>
   {biometricEnabled?<Pressable onPress={biometric} disabled={busy} style={[styles.bioButton,{borderColor:theme.border}]}><Text style={{color:theme.primary,fontWeight:'700'}}>{busy?'Checking biometrics…':'Use fingerprint / face'}</Text></Pressable>:null}
  </View>
 </SafeAreaView>;
}

export default function App(){
 const [ready,setReady]=useState(false);const [active,setActive]=useState<ModuleName>('Home');const [palette,setPalette]=useState<PaletteName>('forest');const [dark,setDark]=useState(false);
 const [lockEnabled,setLockEnabled]=useState(false),[locked,setLocked]=useState(false),[storedPin,setStoredPin]=useState(''),[biometricEnabled,setBiometricEnabled]=useState(false);
 const previousAppState=useRef(AppState.currentState);
 const shouldLockOnResume=useRef(false);
 const theme=useMemo(()=>resolveTheme(palette,dark?'dark':'light'),[palette,dark]);
 useEffect(()=>{
  let active=true;
  (async()=>{
   configureCampusNotifications();
   await initializeDatabase();
   rescheduleCampusReminders().catch(error=>console.warn('CampusOS reminders were not restored',error));
   updateCampusWidgets().catch(error=>console.warn('CampusOS widgets were not restored',error));
   const db=await database;
   const settings=await db.getAllAsync<{key:string;value:string}>('SELECT key,value FROM app_settings');
   const values=Object.fromEntries(settings.map(x=>[x.key,x.value]));
   const lockIsEnabled=values.app_lock_enabled==='true';
   const securePin=lockIsEnabled?await SecureStore.getItemAsync(APP_PIN_KEY):null;
   if(active&&lockIsEnabled&&securePin){
    setLockEnabled(true);setLocked(true);setStoredPin(securePin);setBiometricEnabled(values.app_lock_biometrics==='true');
   }else if(active){
    setLockEnabled(false);setLocked(false);setStoredPin('');setBiometricEnabled(false);
   }
   const supported:PaletteName[]=['forest','sunset','monochrome','ocean','mint','sky','lavender','plum','rose','coral','amber','teal'];
   if(active&&supported.includes(values.palette as PaletteName))setPalette(values.palette as PaletteName);
   if(active)setDark(values.display_mode==='dark');
   if(active)setReady(true);
  })().catch(e=>{console.error('CampusOS database initialization failed',e);if(active)setReady(true);});
  return ()=>{active=false;};
 },[]);
 useEffect(()=>{
  const subscription=AppState.addEventListener('change',nextState=>{
   const previous=previousAppState.current;
   if(lockEnabled&&previous==='active'&&(nextState==='inactive'||nextState==='background'))shouldLockOnResume.current=true;
   if(lockEnabled&&nextState==='active'&&shouldLockOnResume.current){setLocked(true);shouldLockOnResume.current=false;}
   previousAppState.current=nextState;
  });
  return ()=>subscription.remove();
 },[lockEnabled]);
 const onLockChange=(enabled:boolean,pin?:string|null,biometric=false)=>{
  setLockEnabled(enabled);setStoredPin(pin||'');setBiometricEnabled(enabled&&biometric);
  if(!enabled){setLocked(false);shouldLockOnResume.current=false;}
 };
 const persistSetting=async(key:string,value:string)=>{
  const db=await database;
  await db.runAsync('INSERT OR REPLACE INTO app_settings (key,value) VALUES (?,?)',key,value);
 };
 const changePalette=(value:PaletteName)=>{setPalette(value);persistSetting('palette',value).catch(e=>console.error('Could not save palette',e));};
 const changeDark=(value:boolean)=>{setDark(value);persistSetting('display_mode',value?'dark':'light').catch(e=>console.error('Could not save display mode',e));};
 if(!ready)return <SafeAreaView style={[styles.loading,{backgroundColor:theme.background}]}><ActivityIndicator color={theme.primary}/><Text style={{color:theme.text}}>Preparing your offline planner…</Text></SafeAreaView>;
 if(locked&&lockEnabled&&storedPin)return <CampusLockScreen theme={theme} pin={storedPin} biometricEnabled={biometricEnabled} onUnlock={()=>setLocked(false)}/>;
 const screen=()=>{switch(active){case'Home':return <HomeScreen theme={theme} go={v=>setActive(v as ModuleName)}/>;case'Schedule':return <ScheduleScreen theme={theme}/>;case'Tasks':return <TasksScreen theme={theme}/>;case'Academics':return <AcademicsScreen theme={theme}/>;case'Budget':return <BudgetScreen theme={theme}/>;case'Notepad':return <NotesScreen theme={theme}/>;case'Files':return <FilesScreen theme={theme}/>;case'Settings':return <SettingsScreen theme={theme} palette={palette} onPalette={changePalette} dark={dark} onDark={changeDark} onLockChange={onLockChange}/>;}};
 return <SafeAreaView style={[styles.root,{backgroundColor:theme.background}]}><StatusBar barStyle={dark?'light-content':'dark-content'}/><View style={styles.topbar}><View><Text style={[styles.brand,{color:theme.primary}]}>CampusOS</Text><Text style={[styles.sub,{color:theme.muted}]}>PERSONAL PLANNER</Text></View><Pressable onPress={()=>changeDark(!dark)} style={[styles.toggle,{backgroundColor:theme.surfaceAlt}]}><Text style={{color:theme.text}}>{dark?'☀':'☾'}</Text></Pressable></View><ScrollView key={active} contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>{screen()}</ScrollView><View style={[styles.nav,{backgroundColor:theme.surface,borderTopColor:theme.border}]}><ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.navContent}>{modules.map(m=><Pressable key={m.key} onPress={()=>setActive(m.key)} style={[styles.navItem,active===m.key&&{backgroundColor:theme.surfaceAlt}]}><Text style={{color:active===m.key?theme.primary:theme.muted,fontSize:18,fontWeight:'700'}}>{m.symbol}</Text><Text numberOfLines={1} style={{color:active===m.key?theme.primary:theme.muted,fontSize:10,fontWeight:active===m.key?'700':'500'}}>{m.key}</Text></Pressable>)}</ScrollView></View></SafeAreaView>;
}
const styles=StyleSheet.create({root:{flex:1},lockRoot:{flex:1,justifyContent:'center',padding:24},lockCard:{borderWidth:1,borderRadius:24,padding:22,gap:14,alignItems:'stretch'},lockIcon:{width:60,height:60,borderRadius:30,alignSelf:'center',alignItems:'center',justifyContent:'center'},lockTitle:{fontSize:23,fontWeight:'900',textAlign:'center'},lockSub:{fontSize:13,textAlign:'center',lineHeight:20},pinInput:{minHeight:54,borderWidth:1,borderRadius:14,textAlign:'center',fontSize:20,letterSpacing:5,paddingHorizontal:16},unlockButton:{minHeight:48,borderRadius:14,alignItems:'center',justifyContent:'center'},bioButton:{minHeight:46,borderWidth:1,borderRadius:14,alignItems:'center',justifyContent:'center'},loading:{flex:1,alignItems:'center',justifyContent:'center',gap:12},topbar:{minHeight:64,paddingHorizontal:18,paddingVertical:10,flexDirection:'row',alignItems:'center',justifyContent:'space-between'},brand:{fontSize:22,fontWeight:'800',letterSpacing:-.5},sub:{fontSize:9,letterSpacing:2,fontWeight:'700'},toggle:{width:38,height:38,borderRadius:19,alignItems:'center',justifyContent:'center'},content:{paddingHorizontal:16,paddingBottom:28},nav:{paddingTop:8,paddingBottom:5,borderTopWidth:1},navContent:{paddingHorizontal:4,gap:2},navItem:{width:72,alignItems:'center',justifyContent:'center',gap:3,paddingVertical:6,borderRadius:12}});
