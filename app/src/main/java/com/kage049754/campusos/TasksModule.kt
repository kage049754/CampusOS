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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private const val TASK_PREFS = "campusos_tasks"
data class TaskSub(val id: Long, val title: String, val done: Boolean)
data class TaskMeta(
    val type: String = "Assignment", val priority: String = "Medium", val status: String = "Not started",
    val reminders: List<Long> = emptyList(), val attachments: List<String> = emptyList(),
    val link: String = "", val location: String = "", val notes: String = "", val pinned: Boolean = false,
    val semester: String = "2026–2027 1st Semester", val subtasks: List<TaskSub> = emptyList()
)

fun taskMeta(r: Record): TaskMeta = runCatching {
    val o = JSONObject(r.extra)
    if (!o.optBoolean("_task", false)) return@runCatching TaskMeta(status = if (r.done) "Completed" else "Not started", notes = r.extra)
    val rem = mutableListOf<Long>(); val ra = o.optJSONArray("reminders") ?: JSONArray()
    for (i in 0 until ra.length()) rem += ra.optLong(i)
    val files = mutableListOf<String>(); val fa = o.optJSONArray("attachments") ?: JSONArray()
    for (i in 0 until fa.length()) files += fa.optString(i)
    val subs = mutableListOf<TaskSub>(); val sa = o.optJSONArray("subtasks") ?: JSONArray()
    for (i in 0 until sa.length()) {
        val x = sa.optJSONObject(i) ?: continue
        subs += TaskSub(x.optLong("id", i.toLong()), x.optString("title"), x.optBoolean("done"))
    }
    TaskMeta(o.optString("type","Assignment"), o.optString("priority","Medium"), o.optString("status","Not started"),
        rem, files, o.optString("link"), o.optString("location"), o.optString("notes"), o.optBoolean("pinned"),
        o.optString("semester","2026–2027 1st Semester"), subs)
}.getOrElse { TaskMeta(status = if (r.done) "Completed" else "Not started", notes = r.extra) }

private fun saveTask(r: Record, m: TaskMeta) = r.copy(
    done = m.status == "Completed",
    extra = JSONObject().apply {
        put("_task", true); put("type",m.type); put("priority",m.priority); put("status",m.status)
        put("link",m.link); put("location",m.location); put("notes",m.notes); put("pinned",m.pinned); put("semester",m.semester)
        put("reminders",JSONArray().apply{m.reminders.forEach(::put)})
        put("attachments",JSONArray().apply{m.attachments.forEach(::put)})
        put("subtasks",JSONArray().apply{m.subtasks.forEach{s->put(JSONObject().apply{put("id",s.id);put("title",s.title);put("done",s.done)})}})
    }.toString()
)

fun taskDue(r: Record): Long {
    if (r.dueDate.isBlank()) return Long.MAX_VALUE
    val d = runCatching { SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).parse(r.dueDate) }.getOrNull() ?: return Long.MAX_VALUE
    val h = r.dueTime.substringBefore(":").toIntOrNull() ?: 23
    val m = r.dueTime.substringAfter(":").toIntOrNull() ?: 59
    return Calendar.getInstance().apply { time=d; set(Calendar.HOUR_OF_DAY,h); set(Calendar.MINUTE,m); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }.timeInMillis
}
fun taskOverdue(r: Record) = !r.done && taskDue(r) < System.currentTimeMillis()
fun taskToday(r: Record) = r.dueDate == SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date())
fun taskTomorrow(r: Record): Boolean {
    val c=Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR,1)
    return r.dueDate == SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(c.time)
}
fun taskLabel(r: Record): String {
    if (taskToday(r)) return "Today • " + r.dueTime
    if (taskTomorrow(r)) return "Tomorrow • " + r.dueTime
    if (r.dueDate.isBlank()) return "No due date"
    val d=runCatching{SimpleDateFormat("MMM d, yyyy",Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).parse(r.dueDate)!!)}.getOrDefault(r.dueDate)
    return d + " • " + r.dueTime
}
private fun pColor(p:String)=when(p){"High"->Color(0xFFE53935);"Low"->Color(0xFF43A047);else->Color(0xFFF9A825)}
private fun pIcon(p:String)=when(p){"High"->"🔴";"Low"->"🟢";else->"🟡"}
private fun activeSemester(c:Context)=c.getSharedPreferences(TASK_PREFS,0).getString("semester","2026–2027 1st Semester")?:"2026–2027 1st Semester"

@Composable
fun TasksScreen(store: LocalStore, query: String, clear: () -> Unit, openSubject: (Long) -> Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val rev=store.revision
    var view by rememberSaveable{mutableStateOf("List")}
    var status by rememberSaveable{mutableStateOf("All")}
    var due by rememberSaveable{mutableStateOf("All")}
    var priority by rememberSaveable{mutableStateOf("All")}
    var subject by rememberSaveable{mutableLongStateOf(0L)}
    var type by rememberSaveable{mutableStateOf("All")}
    var search by rememberSaveable{mutableStateOf("")}
    var semester by rememberSaveable{mutableStateOf(activeSemester(context))}
    var date by rememberSaveable{mutableStateOf(SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date()))}
    var editor by remember{mutableStateOf(false)}
    var filters by remember{mutableStateOf(false)}
    var semesterDialog by remember{mutableStateOf(false)}
    var export by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf<Long?>(null)}
    val subjects=remember(rev){store.get("subjects")}
    val tasks=remember(rev,semester){store.get("tasks").filter{taskMeta(it).semester==semester}}
    val q=if(query=="__ADD__")"" else query.ifBlank{search}
    val shown=tasks.filter{r->
        val m=taskMeta(r)
        (q.isBlank()||listOf(r.title,r.subtitle,m.notes,m.type).joinToString(" ").contains(q,true))&&
        (status=="All"||m.status==status)&&
        (due=="All"||(due=="Today"&&taskToday(r))||(due=="Tomorrow"&&taskTomorrow(r))||(due=="This Week"&&taskDue(r)<=System.currentTimeMillis()+604800000L)||(due=="Overdue"&&taskOverdue(r)))&&
        (priority=="All"||m.priority==priority)&&(subject==0L||r.subjectId==subject)&&(type=="All"||m.type==type)
    }.sortedWith(compareByDescending<Record>{taskMeta(it).pinned}.thenBy{if(taskOverdue(it))0 else 1}.thenBy{taskDue(it)})
    val completed=tasks.count{it.done}; val progress=if(tasks.isEmpty())0 else completed*100/tasks.size
    val soon=tasks.count{!it.done&&(taskToday(it)||taskTomorrow(it))}
    val overdue=tasks.filter(::taskOverdue)

    Scaffold(floatingActionButton={FloatingActionButton({editor=true}){Icon(Icons.Default.Add,"Add Task")}}){pad->
        Column(Modifier.fillMaxSize().padding(pad)){
            Row(Modifier.fillMaxWidth().padding(16.dp,8.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text("Tasks",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Good evening, Taylee 👋",color=MaterialTheme.colorScheme.onSurfaceVariant)}
                IconButton({semesterDialog=true}){Icon(Icons.Default.School,"Semester")}
                IconButton({export=true}){Icon(Icons.Default.FileDownload,"Export")}
            }
            OutlinedTextField(q,{search=it;if(query.isNotBlank())clear()},Modifier.fillMaxWidth().padding(horizontal=16.dp),singleLine=true,placeholder={Text("Search tasks...")},leadingIcon={Icon(Icons.Default.Search,null)},trailingIcon={IconButton({filters=true}){Icon(Icons.Default.Tune,"Filters")}})
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(16.dp,7.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                listOf("All" to "All","Today" to "Today","Upcoming" to "This Week").forEach{(a,b)->FilterChip(due==b,{due=b},label={Text(a)})}
                listOf("List","Calendar","Board").forEach{v->FilterChip(view==v,{view=v},label={Text(v)})}
            }
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
                item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){DashCard(progress.toString()+"%","Progress",Modifier.weight(1f));DashCard(soon.toString(),"Due soon",Modifier.weight(1f));DashCard(overdue.size.toString(),"Overdue",Modifier.weight(1f))}}
                item{Text(semester,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(overdue.isNotEmpty()&&due!="Overdue"){item{Text("OVERDUE",color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold)};items(overdue.take(3),key={it.id+900000000L}){TaskCard(it,store,subjects,{selected=it.id},openSubject)}}
                when(view){
                    "Calendar"->{item{TaskCalendar(tasks,date){date=it}};val day=shown.filter{it.dueDate==date};if(day.isEmpty())item{EmptyCard("No tasks for "+date)}else items(day,key={it.id}){TaskCard(it,store,subjects,{selected=it.id},openSubject)}}
                    "Board"->item{TaskBoard(shown,store,subjects,{selected=it},openSubject)}
                    else->if(shown.isEmpty())item{EmptyCard("No tasks match your filters.")}else items(shown,key={it.id}){TaskCard(it,store,subjects,{selected=it.id},openSubject)}
                }
            }
        }
    }
    if(editor)TaskEditor(store,subjects,semester,null){editor=false}
    selected?.let{id->store.get("tasks").firstOrNull{it.id==id}?.let{r->TaskDetail(r,store,subjects,openSubject){selected=null}}}
    if(filters)TaskFilters(status,{status=it},due,{due=it},priority,{priority=it},subject,{subject=it},type,{type=it},subjects){filters=false}
    if(semesterDialog)TaskSemester(semester,{semester=it;context.getSharedPreferences(TASK_PREFS,0).edit().putString("semester",it).apply();semesterDialog=false}){semesterDialog=false}
    if(export)TaskExport(tasks){export=false}
}

@Composable private fun DashCard(value:String,label:String,modifier:Modifier)=Card(modifier){Column(Modifier.padding(12.dp)){Text(value,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}

@Composable private fun TaskCard(r:Record,store:LocalStore,subjects:List<Record>,open:()->Unit,openSubject:(Long)->Unit){
    val m=taskMeta(r);val s=subjects.firstOrNull{it.id==r.subjectId}
    Card(Modifier.fillMaxWidth().clickable(onClick=open),RoundedCornerShape(16.dp)){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.width(5.dp).height(82.dp).background(pColor(m.priority),RoundedCornerShape(5.dp)));Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text(pIcon(m.priority)+" "+r.title,Modifier.weight(1f),fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis);if(m.pinned)Text("⭐")}
            s?.let{Text("📚 "+it.title,color=MaterialTheme.colorScheme.primary,modifier=Modifier.clickable{openSubject(it.id)})}
            Text("📅 "+taskLabel(r),color=if(taskOverdue(r))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){AssistChip(onClick={},label={Text(m.status)});Text(m.type,style=MaterialTheme.typography.labelSmall)}
            if(m.subtasks.isNotEmpty()){val d=m.subtasks.count{it.done};Text(d.toString()+" / "+m.subtasks.size+" completed • "+(d*100/m.subtasks.size)+"%",style=MaterialTheme.typography.labelSmall)}
        }
        IconButton({store.put("tasks",store.get("tasks").map{if(it.id==r.id)saveTask(it,m.copy(pinned=!m.pinned))else it})}){Icon(if(m.pinned)Icons.Default.Star else Icons.Default.StarBorder,"Pin")}
    }}
}

@Composable private fun TaskBoard(tasks:List<Record>,store:LocalStore,subjects:List<Record>,open:(Long)->Unit,openSubject:(Long)->Unit){
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        for ((state,label) in listOf("Not started" to "TO DO","In progress" to "IN PROGRESS","Completed" to "DONE")) {
            Card(Modifier.width(290.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text(label,fontWeight=FontWeight.Bold)
                    for (r in tasks.filter { taskMeta(it).status==state }) {
                        TaskCard(r,store,subjects,{open(r.id)},openSubject)
                    }
                }
            }
        }
    }
}

@Composable private fun TaskCalendar(tasks:List<Record>,selected:String,onSelect:(String)->Unit){
    val sdf=SimpleDateFormat("yyyy-MM-dd",Locale.getDefault())
    val base=Calendar.getInstance().apply{time=runCatching{sdf.parse(selected)}.getOrNull()?:Date();set(Calendar.DAY_OF_MONTH,1)}
    val off=(base.get(Calendar.DAY_OF_WEEK)-Calendar.MONDAY+7)%7;val max=base.getActualMaximum(Calendar.DAY_OF_MONTH)
    Column{Text(SimpleDateFormat("MMMM yyyy",Locale.getDefault()).format(base.time),fontWeight=FontWeight.Bold);Row{listOf("M","T","W","T","F","S","S").forEach{Text(it,Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}
        repeat(6){row->Row{repeat(7){col->val n=row*7+col-off+1;if(n in 1..max){val d=(base.clone() as Calendar).apply{set(Calendar.DAY_OF_MONTH,n)};val k=sdf.format(d.time);val count=tasks.count{it.dueDate==k};Box(Modifier.weight(1f).height(44.dp).padding(2.dp).background(if(k==selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,RoundedCornerShape(7.dp)).clickable{onSelect(k)},contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(n.toString(),fontWeight=if(k==selected)FontWeight.Bold else FontWeight.Normal);if(count>0)Text("•".repeat(minOf(3,count)),color=MaterialTheme.colorScheme.error)}}}else Box(Modifier.weight(1f).height(44.dp))}}}
    }
}

@Composable private fun TaskFilters(status:String,setStatus:(String)->Unit,due:String,setDue:(String)->Unit,priority:String,setPriority:(String)->Unit,subject:Long,setSubject:(Long)->Unit,type:String,setType:(String)->Unit,subjects:List<Record>,done:()->Unit){
    AlertDialog(onDismissRequest=done,title={Text("Search + Filters")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(5.dp)){
        Text("Status",fontWeight=FontWeight.Bold);listOf("All","Not started","In progress","Completed").forEach{FilterChip(status==it,{setStatus(it)},label={Text(it)})}
        Text("Due",fontWeight=FontWeight.Bold);listOf("All","Today","Tomorrow","This Week","Overdue").forEach{FilterChip(due==it,{setDue(it)},label={Text(it)})}
        Text("Priority",fontWeight=FontWeight.Bold);listOf("All","High","Medium","Low").forEach{FilterChip(priority==it,{setPriority(it)},label={Text(it)})}
        Text("Subject",fontWeight=FontWeight.Bold);FilterChip(subject==0L,{setSubject(0L)},label={Text("All Subjects")});subjects.forEach{s->FilterChip(subject==s.id,{setSubject(s.id)},label={Text(s.title)})}
        Text("Type",fontWeight=FontWeight.Bold);listOf("All","Assignment","Activity","Project","Quiz","Exam","Report","Presentation","Reading","Requirement","Other").forEach{FilterChip(type==it,{setType(it)},label={Text(it)})}
    }},confirmButton={Button(done){Text("Done")}})
}

@Composable private fun TaskSemester(current:String,select:(String)->Unit,cancel:()->Unit){
    var chosen by remember(current){mutableStateOf(current)}
    AlertDialog(onDismissRequest=cancel,title={Text("Semester / Archive")},text={Column{listOf(current,"2026–2027 2nd Semester","2027–2028 1st Semester").distinct().forEach{s->FilterChip(chosen==s,{chosen=s},label={Text(s)})};Text("Old semesters stay archived and are not deleted.",style=MaterialTheme.typography.bodySmall)}},confirmButton={Button({select(chosen)}){Text("Use semester")}},dismissButton={TextButton(cancel){Text("Cancel")}})
}

@Composable private fun TaskEditor(store:LocalStore,subjects:List<Record>,semester:String,existing:Record?,done:()->Unit){
    val c=androidx.compose.ui.platform.LocalContext.current;val old=existing?.let(::taskMeta)?:TaskMeta(semester=semester)
    var title by remember{mutableStateOf(existing?.title?:"")};var description by remember{mutableStateOf(existing?.subtitle?:"")};var sid by remember{mutableLongStateOf(existing?.subjectId?:0L)}
    var type by remember{mutableStateOf(old.type)};var priority by remember{mutableStateOf(old.priority)};var status by remember{mutableStateOf(old.status)}
    var date by remember{mutableStateOf(existing?.dueDate?.ifBlank{null}?:SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date()))};var time by remember{mutableStateOf(existing?.dueTime?.ifBlank{null}?:"23:59")}
    var link by remember{mutableStateOf(old.link)};var location by remember{mutableStateOf(old.location)};var notes by remember{mutableStateOf(old.notes)};var pinned by remember{mutableStateOf(old.pinned)}
    var reminders by remember{mutableStateOf(old.reminders.toSet())};var subs by remember{mutableStateOf(old.subtasks)};var newSub by remember{mutableStateOf("")};var attachments by remember{mutableStateOf(old.attachments)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?->if(uri!=null){val name=c.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else"attachment"}?:"attachment";val dir=File(c.filesDir,"task_files").apply{mkdirs()};val safe=name.replace(Regex("""[\\/:*?"<>|]"""),"_");var f=File(dir,safe);var n=2;while(f.exists()){f=File(dir,n.toString()+"-"+safe);n++};runCatching{c.contentResolver.openInputStream(uri)?.use{input->f.outputStream().use{output->input.copyTo(output)}};attachments=attachments+f.name}}}
    AlertDialog(onDismissRequest=done,title={Text(if(existing==null)"Add Task"else"Edit Task")},text={Column(Modifier.heightIn(max=680.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(7.dp)){
        Text("Basic information",fontWeight=FontWeight.Bold);OutlinedTextField(title,{title=it},Modifier.fillMaxWidth(),label={Text("Task title *")},singleLine=true);OutlinedTextField(description,{description=it},Modifier.fillMaxWidth(),label={Text("Description")})
        Text("Subject *",fontWeight=FontWeight.Bold);Row(Modifier.horizontalScroll(rememberScrollState())){subjects.forEach{s->FilterChip(sid==s.id,{sid=s.id},label={Text(s.title)})}}
        Text("Task type",fontWeight=FontWeight.Bold);Row(Modifier.horizontalScroll(rememberScrollState())){listOf("Assignment","Activity","Project","Quiz","Exam","Report","Presentation","Reading","Requirement","Other").forEach{x->FilterChip(type==x,{type=x},label={Text(x)})}}
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){Button({DatePickerDialog(c,{_,y,m,d->date="%04d-%02d-%02d".format(y,m+1,d)},date.substring(0,4).toInt(),date.substring(5,7).toInt()-1,date.substring(8,10).toInt()).show()},Modifier.weight(1f)){Text("Due "+date)};Button({TimePickerDialog(c,{_,h,m->time="%02d:%02d".format(h,m)},time.substringBefore(":").toInt(),time.substringAfter(":").toInt(),true).show()},Modifier.weight(1f)){Text(time)}}
        Text("Priority",fontWeight=FontWeight.Bold);Row{listOf("High","Medium","Low").forEach{x->FilterChip(priority==x,{priority=x},label={Text(pIcon(x)+" "+x)})}}
        Text("Status",fontWeight=FontWeight.Bold);Row{listOf("Not started","In progress","Completed").forEach{x->FilterChip(status==x,{status=x},label={Text(x)})}}
        Text("Reminders",fontWeight=FontWeight.Bold);Row(Modifier.horizontalScroll(rememberScrollState())){listOf(86400000L to "1 day",43200000L to "12 h",7200000L to "2 h",1800000L to "30 m").forEach{(offset,label)->FilterChip(offset in reminders,{reminders=if(offset in reminders)reminders-offset else reminders+offset},label={Text(label)})}}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(pinned,{pinned=it});Text("⭐ Pin / Important")}
        Text("Attachments",fontWeight=FontWeight.Bold);Button({picker.launch(arrayOf("*/*"))}){Icon(Icons.Default.AttachFile,null);Spacer(Modifier.width(5.dp));Text("Add file")};attachments.forEach{Text("📎 "+it,style=MaterialTheme.typography.bodySmall)}
        OutlinedTextField(link,{link=it},Modifier.fillMaxWidth(),label={Text("Link")},singleLine=true);OutlinedTextField(location,{location=it},Modifier.fillMaxWidth(),label={Text("Location")},singleLine=true);OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth(),label={Text("Notes")})
        Text("Checklist / Subtasks",fontWeight=FontWeight.Bold);subs.forEach{s->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(s.done,{subs=subs.map{if(it.id==s.id)it.copy(done=!s.done)else it}});Text(s.title,Modifier.weight(1f));IconButton({subs=subs.filterNot{it.id==s.id}}){Icon(Icons.Default.Delete,"Delete")}}};Row{OutlinedTextField(newSub,{newSub=it},Modifier.weight(1f),label={Text("Add subtask")},singleLine=true);IconButton({if(newSub.isNotBlank()){subs=subs+TaskSub(System.currentTimeMillis(),newSub.trim(),false);newSub=""}}){Icon(Icons.Default.Add,"Add")}}
    }},confirmButton={Button({if(title.isNotBlank()&&sid!=0L){val m=TaskMeta(type,priority,status,reminders.toList(),attachments,link,location,notes,pinned,semester,subs);val base=existing?:Record(id=nextRecordId(store,"tasks"),title=title);val r=saveTask(base,m).copy(title=title.trim(),subtitle=description.trim(),subjectId=sid,dueDate=date,dueTime=time);val list=store.get("tasks");store.put("tasks",if(existing==null)list+r else list.map{if(it.id==existing.id)r else it});done()}}){Text("Save Task")}},dismissButton={TextButton(done){Text("Cancel")}})
}

@Composable private fun TaskDetail(r:Record,store:LocalStore,subjects:List<Record>,openSubject:(Long)->Unit,done:()->Unit){
    val context=androidx.compose.ui.platform.LocalContext.current
    val m=taskMeta(r);val s=subjects.firstOrNull{it.id==r.subjectId}
    var edit by remember{mutableStateOf(false)}
    var viewerFile by remember{mutableStateOf<File?>(null)}
    if(edit){TaskEditor(store,subjects,m.semester,r){edit=false;done()};return}

    viewerFile?.let { file ->
        Dialog(onDismissRequest={viewerFile=null}) {
            Surface(Modifier.fillMaxSize(), shape=RoundedCornerShape(0.dp)) {
                if(file.exists()) InAppFileViewerPage(file){viewerFile=null}
                else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement=Arrangement.Center, horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("Attachment is no longer available.", fontWeight=FontWeight.Bold)
                    Text(file.name, color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button({viewerFile=null}){Text("Close")}
                }
            }
        }
    }

    AlertDialog(onDismissRequest=done,title={Text(r.title)},text={Column(Modifier.heightIn(max=620.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(7.dp)){
        Text(pIcon(m.priority)+" "+m.priority+" priority • "+m.status,fontWeight=FontWeight.Bold)
        s?.let{Text("📚 "+it.title,color=MaterialTheme.colorScheme.primary,modifier=Modifier.clickable{openSubject(it.id)})}
        Text("📅 "+taskLabel(r))
        if(taskOverdue(r))Text("⚠️ OVERDUE",color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold)
        if(r.subtitle.isNotBlank())Text(r.subtitle)
        if(m.location.isNotBlank())Text("📍 "+m.location)
        if(m.link.isNotBlank())Text("🔗 "+m.link)
        if(m.notes.isNotBlank())Text("📝 "+m.notes)
        if(m.subtasks.isNotEmpty()){
            Text("Checklist",fontWeight=FontWeight.Bold)
            m.subtasks.forEach{sub->
                Row(verticalAlignment=Alignment.CenterVertically){
                    Checkbox(sub.done,{
                        val nm=m.copy(subtasks=m.subtasks.map{if(it.id==sub.id)it.copy(done=!sub.done)else it})
                        store.put("tasks",store.get("tasks").map{if(it.id==r.id)saveTask(it,nm)else it})
                    })
                    Text(sub.title)
                }
            }
        }
        if(m.attachments.isNotEmpty()){
            Text("Attachments",fontWeight=FontWeight.Bold)
            m.attachments.forEach { name ->
                val file=File(context.filesDir,"task_files/$name")
                OutlinedButton(onClick={viewerFile=file},modifier=Modifier.fillMaxWidth()){
                    Icon(if(file.exists())Icons.Default.AttachFile else Icons.Default.ErrorOutline,null)
                    Spacer(Modifier.width(6.dp))
                    Text(if(file.exists())"Open $name" else "$name (missing)",maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    }},confirmButton={Button({edit=true}){Text("Edit")}},dismissButton={Row{
        TextButton({
            val nm=m.copy(status="Completed")
            store.put("tasks",store.get("tasks").map{if(it.id==r.id)saveTask(it,nm)else it})
            done()
        }){Text("Complete")}
        TextButton({store.put("tasks",store.get("tasks").filterNot{it.id==r.id});done()}){Text("Delete")}
    }})
}

@Composable private fun TaskExport(tasks:List<Record>,done:()->Unit){
    val c=androidx.compose.ui.platform.LocalContext.current;var format by remember{mutableStateOf("JSON")}
    val creator=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument()){uri->if(uri!=null)runCatching{c.contentResolver.openOutputStream(uri)?.use{out->when(format){"CSV"->out.write(taskCsv(tasks).toByteArray());"PDF"->out.write(taskPdf(tasks));else->out.write(taskJson(tasks).toByteArray())}}};done()}
    AlertDialog(onDismissRequest=done,title={Text("Export Tasks")},text={Column{Text("Export the current semester.");listOf("PDF","CSV","JSON").forEach{f->FilterChip(format==f,{format=f},label={Text(f)})}}},confirmButton={Button({creator.launch("CampusOS-Tasks."+format.lowercase())}){Text("Export")}},dismissButton={TextButton(done){Text("Cancel")}})
}
private fun taskJson(tasks:List<Record>):String=JSONArray().apply{tasks.forEach{r->val m=taskMeta(r);put(JSONObject().apply{put("id",r.id);put("title",r.title);put("description",r.subtitle);put("subjectId",r.subjectId);put("dueDate",r.dueDate);put("dueTime",r.dueTime);put("type",m.type);put("priority",m.priority);put("status",m.status);put("semester",m.semester);put("pinned",m.pinned);put("link",m.link);put("location",m.location);put("notes",m.notes);put("attachments",JSONArray(m.attachments))})}}.toString(2)
private fun taskCsv(tasks:List<Record>):String{val rows=tasks.joinToString("\n"){r->val m=taskMeta(r);listOf(r.title,r.subjectId,m.type,m.priority,m.status,r.dueDate,r.dueTime,m.semester,m.pinned).joinToString(","){v->"\""+v.toString().replace("\"","\"\"")+"\""}};return "Title,Subject ID,Type,Priority,Status,Due Date,Due Time,Semester,Pinned\n"+rows}
private fun taskPdf(tasks:List<Record>):ByteArray{val d=PdfDocument();val p=d.startPage(PdfDocument.PageInfo.Builder(595,842,1).create());val paint=android.graphics.Paint().apply{textSize=16f};p.canvas.drawText("CampusOS Tasks",36f,48f,paint);paint.textSize=10f;var y=72f;tasks.take(55).forEach{r->val m=taskMeta(r);p.canvas.drawText(pIcon(m.priority)+" "+r.title+" • "+r.dueDate+" "+r.dueTime+" • "+m.status,36f,y,paint);y+=14f};d.finishPage(p);val o=java.io.ByteArrayOutputStream();d.writeTo(o);d.close();return o.toByteArray()}
