package com.kage049754.campusos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.*

private enum class CalcMode(val title: String) {
    STANDARD("Standard"),
    SCIENTIFIC("Scientific"),
    PERCENTAGE("Percentage"),
    GPA("GPA / Grade"),
    FRACTION("Fraction"),
    STATISTICS("Statistics"),
    GEOMETRY("Geometry"),
    EQUATION("Equation Solver"),
    UNIT("Unit Converter"),
    BASE("Number / Base"),
    DATE("Date Calculator"),
    FINANCE("Finance")
}

private fun basicCalc(s: String): String = runCatching {
    val text=s.replace("×","*").replace("÷","/").replace("−","-")
    var total=0.0
    text.split("+").forEach { part ->
        val q=part.trim()
        if(q.contains("*")) {
            val a=q.split("*")
            total += a[0].trim().toDouble()*a[1].trim().toDouble()
        } else if(q.contains("/")) {
            val a=q.split("/")
            total += a[0].trim().toDouble()/a[1].trim().toDouble()
        } else if(q.isNotBlank()) total += q.toDouble()
    }
    if(total==total.toLong().toDouble()) total.toLong().toString() else "%.8f".format(Locale.US,total)
}.getOrElse { "Error" }

@Composable private fun CalcInput(label:String,value:String,onChange:(String)->Unit) {
    OutlinedTextField(value,onChange,Modifier.fillMaxWidth(),label={Text(label)},singleLine=true)
}

@Composable private fun CalcResult(lines: List<String>) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            lines.forEach { Text(it,color=MaterialTheme.colorScheme.onPrimaryContainer,fontWeight=FontWeight.SemiBold) }
        }
    }
}

@Composable fun CalculatorScreen() {
    var mode by rememberSaveable { mutableStateOf(CalcMode.STANDARD) }
    var menu by remember { mutableStateOf(false) }
    var expression by rememberSaveable { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf("0") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Calculator",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            OutlinedButton({menu=true}) { Text(mode.title); Icon(Icons.Default.ArrowDropDown,null) }
        }
        if(mode==CalcMode.STANDARD || mode==CalcMode.SCIENTIFIC) {
            CalcResult(listOf(if(expression.isBlank()) "0" else expression,answer))
            if(mode==CalcMode.SCIENTIFIC) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("sin","cos","tan","log","ln","sqrt","x²","xʸ","π").forEach { label ->
                        AssistChip({ 
                            val v=answer.toDoubleOrNull()
                            if(v!=null) answer=when(label) {
                                "sin" -> sin(Math.toRadians(v)).toString()
                                "cos" -> cos(Math.toRadians(v)).toString()
                                "tan" -> tan(Math.toRadians(v)).toString()
                                "log" -> log10(v).toString()
                                "ln" -> ln(v).toString()
                                "sqrt" -> sqrt(v).toString()
                                "x²" -> (v*v).toString()
                                else -> answer
                            }
                        },{Text(label)})
                    }
                }
            }
            listOf(listOf("7","8","9","÷","⌫"),listOf("4","5","6","×","("),listOf("1","2","3","−",")"),listOf("0",".","%","+","=")).forEach { row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    row.forEach { key ->
                        Button({when(key) {
                            "=" -> answer=basicCalc(expression)
                            "⌫" -> expression=expression.dropLast(1)
                            else -> expression+=key
                        }},Modifier.weight(1f).height(52.dp)){Text(key)}
                    }
                }
            }
            OutlinedButton({expression="";answer="0"},Modifier.fillMaxWidth()){Text("CLEAR")}
        } else when(mode) {
            CalcMode.PERCENTAGE -> PercentageCalculator()
            CalcMode.GPA -> GpaCalculator()
            CalcMode.FRACTION -> FractionCalculator()
            CalcMode.STATISTICS -> StatisticsCalculator()
            CalcMode.GEOMETRY -> GeometryCalculator()
            CalcMode.EQUATION -> EquationCalculator()
            CalcMode.UNIT -> UnitCalculator()
            CalcMode.BASE -> BaseCalculator()
            CalcMode.DATE -> DateCalculator()
            CalcMode.FINANCE -> FinanceCalculator()
            else -> {}
        }
    }
    if(menu) AlertDialog(onDismissRequest={menu=false},title={Text("Choose calculator")},text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            CalcMode.values().forEach { item ->
                ListItem(headlineContent={Text(item.title)},modifier=Modifier.clickable{mode=item;menu=false})
            }
        }
    },confirmButton={TextButton({menu=false}){Text("Close")}})
}

@Composable private fun PercentageCalculator() {
    var value by rememberSaveable{mutableStateOf("")}
    var percent by rememberSaveable{mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        CalcInput("Value",value){value=it}
        CalcInput("Percent",percent){percent=it}
        val a=value.toDoubleOrNull()
        val b=percent.toDoubleOrNull()
        if(a!=null&&b!=null) CalcResult(listOf(
            b.toString()+"% of "+a+" = "+a*b/100,
            "Increase = "+a*(1+b/100),
            "Decrease = "+a*(1-b/100)
        ))
    }
}

@Composable private fun GpaCalculator() {
    var grades by rememberSaveable{mutableStateOf("")}
    var units by rememberSaveable{mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        CalcInput("Grades, comma separated",grades){grades=it}
        CalcInput("Units, comma separated",units){units=it}
        val g=grades.split(",").mapNotNull{it.trim().toDoubleOrNull()}
        val u=units.split(",").mapNotNull{it.trim().toDoubleOrNull()}
        if(g.isNotEmpty()&&g.size==u.size) {
            val total=u.sum()
            CalcResult(listOf("GPA = "+g.zip(u).sumOf{it.first*it.second}/total,"Total units = "+total))
        }
    }
}

@Composable private fun FractionCalculator() {
    var a by rememberSaveable{mutableStateOf("")}
    var b by rememberSaveable{mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        CalcInput("Fraction A (a/b)",a){a=it}
        CalcInput("Fraction B (a/b)",b){b=it}
        val x=a.split("/").mapNotNull{it.toLongOrNull()}
        val y=b.split("/").mapNotNull{it.toLongOrNull()}
        if(x.size==2&&y.size==2&&y[1]!=0L) {
            val n=x[0]*y[1]+y[0]*x[1]
            val d=x[1]*y[1]
            CalcResult(listOf("A + B = "+n+"/"+d,"Decimal = "+n.toDouble()/d))
        }
    }
}

@Composable private fun StatisticsCalculator() {
    var input by rememberSaveable{mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        CalcInput("Numbers, comma or space separated",input){input=it}
        val n=input.split(","," ").mapNotNull{it.toDoubleOrNull()}
        if(n.isNotEmpty()) {
            val mean=n.average()
            CalcResult(listOf("Count = "+n.size,"Mean = "+mean,"Minimum = "+n.min(),"Maximum = "+n.max(),"Std. deviation = "+sqrt(n.sumOf{(it-mean).pow(2)}/n.size)))
        }
    }
}

@Composable private fun GeometryCalculator() {
    var shape by rememberSaveable{mutableStateOf("Circle")}
    var a by rememberSaveable{mutableStateOf("")}
    var b by rememberSaveable{mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row { listOf("Circle","Rectangle","Triangle").forEach{s->FilterChip(shape==s,{shape=s},label={Text(s)},modifier=Modifier.padding(end=4.dp))} }
        CalcInput(if(shape=="Circle")"Radius" else "Base / Length",a){a=it}
        if(shape!="Circle") CalcInput("Height / Width",b){b=it}
        val x=a.toDoubleOrNull()
        val y=b.toDoubleOrNull()
        if(x!=null&&(shape=="Circle"||y!=null)) {
            val area=if(shape=="Circle") PI*x*x else if(shape=="Rectangle") x*y!! else x*y!!/2
            CalcResult(listOf("Area = "+area))
        }
    }
}

@Composable private fun EquationCalculator() {
    var a by rememberSaveable{mutableStateOf("")}
    var b by rememberSaveable{mutableStateOf("")}
    var c by rememberSaveable{mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Solve ax² + bx + c = 0")
        CalcInput("a",a){a=it};CalcInput("b",b){b=it};CalcInput("c",c){c=it}
        val aa=a.toDoubleOrNull();val bb=b.toDoubleOrNull();val cc=c.toDoubleOrNull()
        if(aa!=null&&bb!=null&&cc!=null&&aa!=0.0) {
            val d=bb*bb-4*aa*cc
            if(d>=0) CalcResult(listOf("x₁ = "+(-bb+sqrt(d))/(2*aa),"x₂ = "+(-bb-sqrt(d))/(2*aa)))
            else CalcResult(listOf("No real roots"))
        }
    }
}

@Composable private fun UnitCalculator() {
    var value by rememberSaveable{mutableStateOf("")}
    var from by rememberSaveable{mutableStateOf("m")}
    var to by rememberSaveable{mutableStateOf("km")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        CalcInput("Value",value){value=it};CalcInput("From unit",from){from=it};CalcInput("To unit",to){to=it}
        value.toDoubleOrNull()?.let { v ->
            val out=when(from+"->"+to){"m->km"->v/1000;"km->m"->v*1000;"kg->g"->v*1000;"g->kg"->v/1000;"C->F"->v*9/5+32;"F->C"->(v-32)*5/9;else->Double.NaN}
            if(out.isFinite()) CalcResult(listOf(v.toString()+" "+from+" = "+out+" "+to))
        }
    }
}

@Composable private fun BaseCalculator() {
    var number by rememberSaveable{mutableStateOf("")}
    var base by rememberSaveable{mutableStateOf("10")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        CalcInput("Number",number){number=it};CalcInput("Input base: 2, 8, 10 or 16",base){base=it}
        base.toIntOrNull()?.let { b -> runCatching {
            val d=number.trim().toLong(b)
            CalcResult(listOf("Decimal = "+d,"Binary = "+d.toString(2),"Octal = "+d.toString(8),"Hex = "+d.toString(16).uppercase()))
        }}
    }
}

@Composable private fun DateCalculator() {
    var start by rememberSaveable{mutableStateOf("")}
    var end by rememberSaveable{mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Format: YYYY-MM-DD")
        CalcInput("Start date",start){start=it};CalcInput("End date",end){end=it}
        runCatching {
            val f=SimpleDateFormat("yyyy-MM-dd",Locale.US);f.isLenient=false
            val days=abs(f.parse(end)!!.time-f.parse(start)!!.time)/86400000L
            CalcResult(listOf("Difference = "+days+" days"))
        }
    }
}

@Composable private fun FinanceCalculator() {
    var principal by rememberSaveable{mutableStateOf("")}
    var rate by rememberSaveable{mutableStateOf("")}
    var years by rememberSaveable{mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        CalcInput("Principal",principal){principal=it};CalcInput("Annual rate %",rate){rate=it};CalcInput("Years",years){years=it}
        val p=principal.toDoubleOrNull();val r=rate.toDoubleOrNull();val y=years.toDoubleOrNull()
        if(p!=null&&r!=null&&y!=null) {
            val total=p*(1+r/100).pow(y)
            CalcResult(listOf("Compound total = "+total,"Interest = "+(total-p)))
        }
    }
}
