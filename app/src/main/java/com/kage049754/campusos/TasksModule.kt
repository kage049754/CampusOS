package com.kage049754.campusos

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val TASK_PREFS="campusos_tasks"
private data class TaskSub(val id:Long,val title:String,val done:Boolean)
private data class TaskMeta(
    val type:String="Assignment",val priority:String="Medium",val status:String="Not started",
    val reminders:List<Long> = emptyList(),val attachments:List<String> = emptyList(),
    val link:String="",val location:String="",val notes:String="",val pinned:Boolean=false,
    val semester:String="2026–2027 1st Semester",val subtasks:List<TaskSub> = emptyList()
)
private fun taskMeta(r:Record)=runCatching{
    val o=JSONObject(r.extra)
    if(!o.has("_task")) return@runCatching TaskMeta(notes=r.extra,status=if(r.done)"Completed" else "Not started")
    val rem=mutableListOf<Long>();val ra=o.optJSONArray("reminders")?:JSONArray();for(i in 0 until ra.length())rem+=ra.optLong(i)
    val files=mutableListOf<String>();val fa=o.optJSONArray("attachments")?:JSONArray();for(i in 0 until fa.length())files+=fa.optString(i)
    val subs=mutableListOf<TaskSub>();val sa=o.optJSONArray("subtasks")?:JSONArray()
    for(i in 0 until sa.length()){val x=sa.optJSONObject(i)?:continue;subs+=TaskSub(x.optLong("id",i.toLong()),x.optString("title"),x.optBoolean("done"))}
    TaskMeta(o.optString("type","Assignment"),o.optString("priority","Medium"),o.optString("status","Not started"),rem,files,o.optString("link"),o.optString("location"),o.optString("notes"),o.optBoolean("pinned"),o.optString("semester","2026–2027 1st Semester"),subs)
}.getOrElse{TaskMeta(notes=r.extra,status=if(r.done)"Completed" else "Not started")}
private fun encodeTask(m:TaskMeta)=JSONObject().apply{
    put("_task",2);put("type",m.type);put("priority",m.priority);put("status",m.status);put("link",m.link);put("location",m.location);put("notes",m.notes);put("pinned",m.pinned);put("semester",m.semester)
    put("reminders",JSONArray().apply{m.reminders.forEach{put(it)}});put("attachments",JSONArray().apply{m.attachments.forEach{put(it)}})
    put("subtasks",JSONArray().apply{m.subtasks.forEach{s->put(JSONObject().apply{put("id",s.id);put("title",s.title);put("done",s.done)})}})
}.toString()
private fun saveTask(r:Record,m:TaskMeta)=r.copy(done=m.status=="Completed",extra=encodeTask(m))
private fun taskDue(r:Record):Long{
    if(r.dueDate.isBlank())return Long.MAX_VALUE
    val d=runCatching{SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).parse(r.dueDate)}.getOrNull()?:return Long.MAX_VALUE
    return Calendar.getInstance().apply{time=d;set(Calendar.HOUR_OF_DAY,r.dueTime.substringBefore(":").toIntOrNull()?:23);set(Calendar.MINUTE,r.dueTime.substringAfter(":").toIntOrNull()?:59);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}.timeInMillis
}
private fun taskOverdue(r:Record)=!r.done&&taskDue(r)<System.currentTimeMillis()
private fun taskToday(r:Record)=r.dueDate==SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date())
private fun taskTomorrow(r:Record):Boolean{val c=Calendar.getInstance();c.add(Calendar.DAY_OF_YEAR,1);return r.dueDate==SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(c.time)}
private fun pColor(p:String)=when(p){"High"->Color(0xFFE53935);"Low"->Color(0xFF43A047);else->Color(0xFFF9A825)}
private fun pIcon(p:String)=when(p){"High"->"🔴";"Low"->"🟢";else->"🟡"}
private fun taskLabel(r:Record)=when{
    taskToday(r)->"Today • "+r.dueTime
    taskTomorrow(r)->"Tomorrow • "+r.dueTime
    else->runCatching{SimpleDateFormat("MMM d, yyyy",Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).parse(r.dueDate)!!)+" • "+r.dueTime}.getOrElse{"No due date"}
}
private fun activeTaskSemester(c:Context)=c.getSharedPreferences(TASK_PREFS,0).getString("semester","2026–2027 1st Semester")?:"2026–2027 1st Semester"
private fun setTaskSemester(c:Context,s:String)=c.getSharedPreferences(TASK_PREFS,0).edit().putString("semester",s).apply()

@Composable
fun TasksScreen(store:LocalStore,query:String,clear:()->Unit,openSubject:(Long)->Unit){
    val context=androidx.compose.ui.platform.LocalContext.current
    val revision=store.revision
    var view by rememberSaveable{mutableStateOf("List")};var status by rememberSaveable{mutableStateOf("All")};var due by rememberSaveable{mutableStateOf("All")}
    var priority by rememberSaveable{mutableStateOf("All")};var subjectFilter by rememberSaveable{mutableLongStateOf(0L)};var type by rememberSaveable{mutableStateOf("All")}
    var search by rememberSaveable{mutableStateOf("")};var semester by rememberSaveable{mutableStateOf(activeTaskSemester(context))}
    var selectedDate by rememberSaveable{mutableStateOf(SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date()))}
    var add by remember{mutableStateOf(false)};var filters by remember{mutableStateOf(false)};var semesterDialog by remember{mutableStateOf(false)};var detail by remember{mutableStateOf<Long?>(null)};var export by remember{mutableStateOf(false)}
    val subjects=remember(revision){store.get("subjects")};val tasks=remember(revision,semester){store.get("tasks").filter{taskMeta(it).semester==semester}}
    val q=if(query=="__ADD__")"" else query.ifBlank{search}
    val shown=tasks.filter{r->
        val m=taskMeta(r)
        (q.isBlank()||(r.title+" "+r.subtitle+" "+m.notes+" "+m.type).contains(q,true)) &&
        (status=="All"||(status=="To Do"&&m.status=="Not started")||(status=="In Progress"&&m.status=="In progress")||(status=="Completed"&&m.status=="Completed")) &&
        (due=="All"||(due=="Today"&&taskToday(r))||(due=="Tomorrow"&&taskTomorrow(r))||(due=="This Week"&&taskDue(r)<=System.currentTimeMillis()+604800000L)||(due=="Overdue"&&taskOverdue(r))) &&
        (priority=="All"||m.priority==priority)&&(subjectFilter==0L||r.subjectId==subjectFilter)&&(type=="All"||m.type==type)
    }.sortedWith(compareByDescending<Record>{taskMeta(it).pinned}.thenBy{taskOverdue(it).not()}.thenBy{taskDue(it)})
    val pending=tasks.count{!it.done};val completed=tasks.count{it.done};val progress=if(tasks.isEmpty())0 else completed*100/tasks.size;val soon=tasks.count{!it.done&&(taskToday(it)||taskTomorrow(it))}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){Text("Tasks",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Good evening, Taylee 👋",color=MaterialTheme.colorScheme.onSurfaceVariant)}
            IconButton({semesterDialog=true}){Icon(Icons.Default.School,"Semester")};IconButton({export=true}){Icon(Icons.Default.MoreVert,"Export")}
        }
        OutlinedTextField(q,{search=it;if(query.isNotBlank())clear()},Modifier.fillMaxWidth().padding(horizontal=16.dp),singleLine=true,placeholder={Text("Search tasks...")},leadingIcon={Icon(Icons.Default.Search,null)},trailingIcon={IconButton({filters=true}){Icon(Icons.Default.Tune,"Filters")}})
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=7.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("All","Today","Upcoming").forEach{x->FilterChip((x=="All"&&due=="All")||(x=="Today"&&due=="Today")||(x=="Upcoming"&&due=="This Week"),{due=if(x=="Today")"Today" else if(x=="Upcoming")"This Week" else "All"},label={Text(x)})}
            listOf("List","Calendar","Board").forEach{x->FilterChip(view==x,{view=x},label={Text(x)})}
        }
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
            item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){SummaryCard(pending.toString(),"Pending",Modifier.weight(1f));SummaryCard(soon.toString(),"Due Soon",Modifier.weight(1f));SummaryCard(progress.toString()+"%","Progress",Modifier.weight(1f))}}
            item{Text(semester,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            val over=tasks.filter(::taskOverdue)
            if(over.isNotEmpty()&&due!="Overdue"){item{Text("⚠️ OVERDUE",color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold)};items(over.take(3),key={it.id+9000000}){TaskCard(it,store,subjects,{detail=it.id},openSubject)}} 
            when(view){
                "Calendar"->{item{TaskCalendarView(tasks,selectedDate){selectedDate=it}};val day=shown.filter{it.dueDate==selectedDate};if(day.isEmpty())item{EmptyCard("No tasks for "+selectedDate)}else items(day,key={it.id}){TaskCard(it,store,subjects,{detail=it.id},openSubject)}}
                "Board"->item{TaskBoard(shown,store,subjects,{detail=it},openSubject)}
                else->if(shown.isEmpty())item{EmptyCard("No tasks match your filters.")}else items(shown,key={it.id}){TaskCard(it,store,subjects,{detail=it.id},openSubject)}
            }
        }
    }
    FloatingActionButton({add=true},Modifier.padding(16.dp)){Icon(Icons.Default.Add,"Add Task")}
    if(add)TaskEditorDialog(store,subjects,semester,null){add=false}
    detail?.let{id->store.get("tasks").firstOrNull{it.id==id}?.let{r->TaskDetailDialog(r,store,subjects,openSubject){detail=null}}}
    if(filters)TaskFiltersDialog(status,{status=it},due,{due=it},priority,{priority=it},subjectFilter,{subjectFilter=it},type,{type=it},subjects){filters=false}
    if(semesterDialog)TaskSemesterDialog(context,semester,{semester=it;setTaskSemester(context,it);semesterDialog=false}){semesterDialog=false}
    if(export)TaskExportDialog(tasks){export=false}
}
@Composable private fun SummaryCard(value:String,label:String,modifier:Modifier)=Card(modifier){Column(Modifier.padding(12.dp)){Text(value,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelSmall)}}
@Composable private fun TaskCard(r:Record,store:LocalStore,subjects:List<Record>,open:()->Unit,openSubject:(Long)->Unit){
    val m=taskMeta(r);val subject=subjects.firstOrNull{it.id==r.subjectId}
    Card(Modifier.fillMaxWidth().clickable{open()},RoundedCornerShape(16.dp)){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.width(5.dp).height(82.dp).background(pColor(m.priority),RoundedCornerShape(5.dp)));Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text(pIcon(m.priority)+" "+r.title,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis);if(m.pinned)Text("⭐")}
            subject?.let{Text("📚 "+it.title,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold,modifier=Modifier.clickable{openSubject(it.id)})}
            Text("📅 "+taskLabel(r),color=if(taskOverdue(r))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){AssistChip({},{label={Text(m.status)}});Text(m.type,style=MaterialTheme.typography.labelSmall)}
            if(m.subtasks.isNotEmpty()){val d=m.subtasks.count{it.done};Text(d.toString()+" / "+m.subtasks.size+" completed — "+(d*100/m.subtasks.size)+"%",style=MaterialTheme.typography.labelSmall)}
        }
        IconButton({store.put("tasks",store.get("tasks").map{if(it.id==r.id)saveTask(it,m.copy(pinned=!m.pinned))else it})}){Icon(if(m.pinned)Icons.Default.Star else Icons.Default.StarBorder,"Pin")}
    }}
}
@Composable private fun TaskBoard(tasks:List<Record>,store:LocalStore,subjects:List<Record>,open:(Long)->Unit,openSubject:(Long)->Unit)=Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Not started" to "TO DO","In progress" to "IN PROGRESS","Completed" to "DONE").forEach{pair->Card(Modifier.width(290.dp)){Column(Modifier.padding(8.dp)){Text(pair.second,fontWeight=FontWeight.Bold);tasks.filter{taskMeta(it).status==pair.first}.forEach{TaskCard(it,store,subjects,{open(it.id)},openSubject)}}}}}
@Composable private fun TaskCalendarView(tasks:List<Record>,selected:String,onSelect:(String)->Unit){
    val sdf=SimpleDateFormat("yyyy-MM-dd",Locale.getDefault());val base=Calendar.getInstance().apply{time=runCatching{sdf.parse(selected)}.getOrNull()?:time;set(Calendar.DAY_OF_MONTH,1)};val off=(base.get(Calendar.DAY_OF_WEEK)-Calendar.MONDAY+7)%7;val max=base.getActualMaximum(Calendar.DAY_OF_MONTH)
    Column{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(SimpleDateFormat("MMMM yyyy",Locale.getDefault()).format(base.time),Modifier.weight(1f),fontWeight=FontWeight.Bold);Text("Select a date",style=MaterialTheme.typography.labelSmall)};Row{listOf("M","T","W","T","F","S","S").forEach{Text(it,Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}
        for(row in 0..5)Row{for(col in 0..6){val n=row*7+col-off+1;if(n in 1..max){val d=(base.clone() as Calendar).apply{set(Calendar.DAY_OF_MONTH,n)};val k=sdf.format(d.time);val count=tasks.count{it.dueDate==k};Box(Modifier.weight(1f).height(44.dp).padding(2.dp).background(if(k==selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,RoundedCornerShape(7.dp)).clickable{onSelect(k)},contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(n.toString(),fontWeight=if(k==selected)FontWeight.Bold else FontWeight.Normal);if(count>0)Text("•".repeat(minOf(3,count)),color=MaterialTheme.colorScheme.error)}}}else Box(Modifier.weight(1f).height(44.dp))}}}
}
@Composable private fun TaskFiltersDialog(status:String,setStatus:(String)->Unit,due:String,setDue:(String)->Unit,priority:String,setPriority:(String)->Unit,subject:Long,setSubject:(Long)->Unit,type:String,setType:(String)->Unit,subjects:List<Record>,done:()->Unit)=AlertDialog(onDismissRequest=done,title={Text("Search + Filters")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(7.dp)){Text("Status",fontWeight=FontWeight.Bold);listOf("All","To Do","In Progress","Completed").forEach{FilterChip(status==it,{setStatus(it)},label={Text(it)})};Text("Due",fontWeight=FontWeight.Bold);listOf("All","Today","Tomorrow","This Week","Overdue").forEach{FilterChip(due==it,{setDue(it)},label={Text(it)})};Text("Priority",fontWeight=FontWeight.Bold);listOf("All","High","Medium","Low").forEach{FilterChip(priority==it,{setPriority(it)},label={Text(it)})};Text("Subject",fontWeight=FontWeight.Bold);FilterChip(subject==0L,{setSubject(0L)},label={Text("All Subjects")});subjects.forEach{s->FilterChip(subject==s.id,{setSubject(s.id)},label={Text(s.title)})};Text("Type",fontWeight=FontWeight.Bold);listOf("All","Assignment","Exam","Quiz","Project","Activity","Report","Presentation","Reading","Requirement","Other").forEach{FilterChip(type==it,{setType(it)},label={Text(it)})}},confirmButton={Button(done){Text("Done")}})
@Composable private fun TaskSemesterDialog(context:Context,current:String,select:(String)->Unit,cancel:()->Unit){var chosen by remember{mutableStateOf(current)};AlertDialog(onDismissRequest=cancel,title={Text("Semester")},text={Column{listOf(current,"2026–2027 2nd Semester","2027–2028 1st Semester").distinct().forEach{s->FilterChip(chosen==s,{chosen=s},label={Text(s)})};Text("Archiving keeps old tasks; tasks are never automatically deleted.",style=MaterialTheme.typography.bodySmall)}},confirmButton={Button({select(chosen)}){Text("Use semester")}},dismissButton={TextButton(cancel){Text("Cancel")}})}
@Composable private fun TaskEditorDialog(store:LocalStore,subjects:List<Record>,semester:String,existing:Record?,done:()->Unit){
    val c=androidx.compose.ui.platform.LocalContext.current;val old=existing?.let(::taskMeta)?:TaskMeta(semester=semester)
    var title by remember{mutableStateOf(existing?.title?:"")};var desc by remember{mutableStateOf(existing?.subtitle?:"")};var sid by remember{mutableLongStateOf(existing?.subjectId?:0L)}
    var type by remember{mutableStateOf(old.type)};var priority by remember{mutableStateOf(old.priority)};var status by remember{mutableStateOf(old.status)}
    var date by remember{mutableStateOf(existing?.dueDate?:SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date()))};var time by remember{mutableStateOf(existing?.dueTime?:"23:59")}
    var link by remember{mutableStateOf(old.link)};var location by remember{mutableStateOf(old.location)};var notes by remember{mutableStateOf(old.notes)};var pinned by remember{mutableStateOf(old.pinned)}
    var reminders by remember{mutableStateOf(old.reminders.toSet())};var subtasks by remember{mutableStateOf(old.subtasks)};var newSub by remember{mutableStateOf("")};var attachments by remember{mutableStateOf(old.attachments)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?->uri?:return@rememberLauncherForActivityResult;val name=c.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0) else null}?: "attachment";val dir=File(c.filesDir,"task_files").apply{mkdirs()};var f=File(dir,name.replace(Regex("[\\\\/:*?\\\"<>|]"),"_"));var n=2;while(f.exists()){f=File(dir,n.toString()+"-"+f.name);n++};runCatching{c.contentResolver.openInputStream(uri)?.use{i->f.outputStream().use{o->i.copyTo(o)}};attachments=attachments+f.name}}
    AlertDialog(onDismissRequest=done,title={Text(if(existing==null)"Add Task" else "Edit Task")},text={Column(Modifier.heightIn(max=700.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Basic",fontWeight=FontWeight.Bold);OutlinedTextField(title,{title=it},Modifier.fillMaxWidth(),label={Text("Task title *")},singleLine=true);OutlinedTextField(desc,{desc=it},Modifier.fillMaxWidth(),label={Text("Description")})
        Text("Subject *",fontWeight=FontWeight.Bold);Row(Modifier.horizontalScroll(rememberScrollState())){subjects.forEach{s->FilterChip(sid==s.id,{sid=s.id},label={Text(s.title)})}}
        Text("Task type",fontWeight=FontWeight.Bold);Row(Modifier.horizontalScroll(rememberScrollState())){listOf("Assignment","Activity","Project","Quiz","Exam","Report","Presentation","Reading","Requirement","Other").forEach{x->FilterChip(type==x,{type=x},label={Text(x)})}}
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){Button({DatePickerDialog(c,{_,y,m,d->date="%04d-%02d-%02d".format(y,m+1,d)},date.substring(0,4).toInt(),date.substring(5,7).toInt()-1,date.substring(8).toInt()).show()},Modifier.weight(1f)){Text("Due "+date)};Button({TimePickerDialog(c,{_,h,m->time="%02d:%02d".format(h,m)},time.substringBefore(":").toInt(),time.substringAfter(":").toInt(),true).show()},Modifier.weight(1f)){Text(time)}}
        Text("Priority",fontWeight=FontWeight.Bold);Row{listOf("High","Medium","Low").forEach{x->FilterChip(priority==x,{priority=x},label={Text(pIcon(x)+" "+x)})}}
        Text("Status",fontWeight=FontWeight.Bold);Row(Modifier.horizontalScroll(rememberScrollState())){listOf("Not started","In progress","Completed").forEach{x->FilterChip(status==x,{status=x},label={Text(x)})}}
        Text("Reminder",fontWeight=FontWeight.Bold);Row(Modifier.horizontalScroll(rememberScrollState())){listOf(86400000L to "1 day",43200000L to "12 hours",7200000L to "2 hours",1800000L to "30 min").forEach{pair->FilterChip(pair.first in reminders,{reminders=if(pair.first in reminders)reminders-pair.first else reminders+pair.first},label={Text(pair.second)})}}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(pinned,{pinned=it});Text("⭐ Pin / Important")}
        Text("Attachments",fontWeight=FontWeight.Bold);Button({picker.launch(arrayOf("*/*"))}){Icon(Icons.Default.AttachFile,null);Spacer(Modifier.width(5.dp));Text("Add file")};attachments.forEach{Text("📎 "+it)}
        OutlinedTextField(link,{link=it},Modifier.fillMaxWidth(),label={Text("Link")},singleLine=true);OutlinedTextField(location,{location=it},Modifier.fillMaxWidth(),label={Text("Location")},singleLine=true);OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth(),label={Text("Notes")})
        Text("Checklist / Subtasks",fontWeight=FontWeight.Bold);subtasks.forEach{s->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(s.done,{subtasks=subtasks.map{if(it.id==s.id)it.copy(done=!s.done)else it}});Text(s.title,Modifier.weight(1f));IconButton({subtasks=subtasks.filterNot{it.id==s.id}}){Icon(Icons.Default.Delete,null)}}};Row{OutlinedTextField(newSub,{newSub=it},Modifier.weight(1f),label={Text("Add subtask")},singleLine=true);IconButton({if(newSub.isNotBlank()){subtasks=subtasks+TaskSub(System.currentTimeMillis(),newSub.trim(),false);newSub=""}}){Icon(Icons.Default.Add,null)}}
    }},confirmButton={Button({if(title.isNotBlank()&&sid!=0L){val m=TaskMeta(type,priority,status,reminders.toList(),attachments,link,location,notes,pinned,semester,subtasks);val base=existing?:Record(id=nextId(store),title=title,subjectId=sid);val r=saveTask(base,m).copy(title=title.trim(),subtitle=desc.trim(),subjectId=sid,dueDate=date,dueTime=time);store.put("tasks",if(existing==null)store.get("tasks")+r else store.get("tasks").map{if(it.id==existing.id)r else it});done()}}){Text("Save Task")}},dismissButton={TextButton(done){Text("Cancel")}})
}
@Composable private fun TaskDetailDialog(r:Record,store:LocalStore,subjects:List<Record>,openSubject:(Long)->Unit,done:()->Unit){
    val c=androidx.compose.ui.platform.LocalContext.current;var edit by remember{mutableStateOf(false)};var file by remember{mutableStateOf<File?>(null)};val m=taskMeta(r);val subject=subjects.firstOrNull{it.id==r.subjectId}
    if(file!=null){Dialog(onDismissRequest={file=null}){Surface(Modifier.fillMaxSize()){InAppFileViewerPage(file!!){file=null}}};return}
    if(edit){TaskEditorDialog(store,subjects,m.semester,r){edit=false;done()};return}
    val attachments=m.attachments.map{File(c,"task_files/"+it)}.filter{it.exists()}
    AlertDialog(onDismissRequest=done,title={Text(r.title)},text={Column(Modifier.heightIn(max=620.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(7.dp)){
        Text(pIcon(m.priority)+" "+m.priority+" priority • "+m.status,fontWeight=FontWeight.Bold);subject?.let{Text("📚 "+it.title,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold,modifier=Modifier.clickable{openSubject(it.id)})};Text("📅 "+taskLabel(r));if(taskOverdue(r))Text("⚠️ OVERDUE",color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold)
        if(r.subtitle.isNotBlank())Text(r.subtitle);if(m.location.isNotBlank())Text("📍 "+m.location);if(m.link.isNotBlank())Text("🔗 "+m.link,color=MaterialTheme.colorScheme.primary);if(m.notes.isNotBlank())Text("📝 "+m.notes)
        if(m.subtasks.isNotEmpty()){Text("Checklist",fontWeight=FontWeight.Bold);m.subtasks.forEach{s->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(s.done,{val nm=m.copy(subtasks=m.subtasks.map{if(it.id==s.id)it.copy(done=!s.done)else it});store.put("tasks",store.get("tasks").map{if(it.id==r.id)saveTask(it,nm)else it})});Text(s.title)}}}
        if(attachments.isNotEmpty()){Text("Attachments",fontWeight=FontWeight.Bold);attachments.forEach{Text("📎 "+it.name,color=MaterialTheme.colorScheme.primary,modifier=Modifier.clickable{file=it})}}
    }},confirmButton={Button({edit=true}){Text("Edit")}},dismissButton={TextButton({store.put("tasks",store.get("tasks").filterNot{it.id==r.id});done()}){Text("Delete")}})
}
@Composable private fun TaskExportDialog(tasks:List<Record>,done:()->Unit){
    val c=androidx.compose.ui.platform.LocalContext.current;var kind by remember{mutableStateOf("JSON")}
    val creator=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument()){uri->uri?:return@rememberLauncherForActivityResult;runCatching{c.contentResolver.openOutputStream(uri)?.use{o->when(kind){"CSV"->o.write(taskCsv(tasks).toByteArray());"PDF"->o.write(taskPdf(tasks));else->o.write(taskJson(tasks).toByteArray())}}};done()}
    AlertDialog(onDismissRequest=done,title={Text("Export Tasks")},text={Column{Text("Export the current semester.");listOf("PDF","CSV","JSON").forEach{x->FilterChip(kind==x,{kind=x},label={Text(x)})}},confirmButton={Button({creator.launch("CampusOS-Tasks."+kind.lowercase())}){Text("Export")}},dismissButton={TextButton(done){Text("Cancel")}})
}
private fun taskJson(ts:List<Record>)=JSONArray().apply{ts.forEach{r->val m=taskMeta(r);put(JSONObject().apply{put("id",r.id);put("title",r.title);put("description",r.subtitle);put("subjectId",r.subjectId);put("dueDate",r.dueDate);put("dueTime",r.dueTime);put("type",m.type);put("priority",m.priority);put("status",m.status);put("semester",m.semester);put("pinned",m.pinned);put("link",m.link);put("location",m.location);put("notes",m.notes);put("attachments",JSONArray(m.attachments))})}}.toString(2)
private fun taskCsv(ts:List<Record>)="Title,Subject,Type,Priority,Status,Due Date,Due Time,Semester,Pinned\n"+ts.joinToString("\n"){r->{val m=taskMeta(r);listOf(r.title,r.subjectId,m.type,m.priority,m.status,r.dueDate,r.dueTime,m.semester,m.pinned).joinToString(","){v->"\""+v.toString().replace("\"","\"\"")+"\""}}}
private fun taskPdf(ts:List<Record>):ByteArray{val d=PdfDocument();val p=d.startPage(PdfDocument.PageInfo.Builder(595,842,1).create());val paint=android.graphics.Paint().apply{textSize=16f};p.canvas.drawText("CampusOS Tasks",36f,48f,paint);paint.textSize=10f;var y=72f;ts.take(55).forEach{r->val m=taskMeta(r);p.canvas.drawText(pIcon(m.priority)+" "+r.title+" • "+r.dueDate+" "+r.dueTime+" • "+m.status,36f,y,paint);y+=14f};d.finishPage(p);val o=java.io.ByteArrayOutputStream();d.writeTo(o);d.close();return o.toByteArray()}
