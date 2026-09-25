data class Record(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val subtitle: String = "",
    val extra: String = "",
    val value: Double = 0.0,
    val done: Boolean = false,
    val day: String = "", val startTime: String = "", val endTime: String = "",
    val room: String = "", val professor: String = "", val color: Long = 0L, val classType: String = "Lecture", val subjectId: Long = 0L, val dueDate: String = "", val dueTime: String = ""
)