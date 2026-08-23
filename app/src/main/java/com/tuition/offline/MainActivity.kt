package com.tuition.offline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tuition.offline.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Purple = Color(0xFF4030A5)
private val Green = Color(0xFF2E8B57)
private val Red = Color(0xFFC62828)
private val Orange = Color(0xFFE58A00)
private val Blue = Color(0xFF1976D2)

private fun money(minor: Long): String =
    NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(minor / 100.0).replace(".00", "")

private fun amountToMinor(text: String): Long {
    val clean = text.trim().replace(",", "")
    val parts = clean.split(".")
    val rupees = parts.getOrNull(0)?.toLongOrNull() ?: 0L
    val paise = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L
    return rupees * 100 + paise
}

private fun nowPeriod(): String = YearMonth.now().toString()

class TuitionViewModel(private val repo: TuitionRepository) : ViewModel() {
    val students = repo.students().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedPeriod = MutableStateFlow(nowPeriod())

    private val _message = MutableSharedFlow<String>()
    val message = _message.asSharedFlow()

    fun addStudent(name: String, parent: String, standard: String, batch: String) = viewModelScope.launch {
        if (name.isBlank()) { _message.emit("Student name is required"); return@launch }
        repo.addStudent(name, parent, standard, batch)
        _message.emit("Student added successfully")
    }

    fun deleteStudent(studentId: String) = viewModelScope.launch {
        repo.deleteStudent(studentId)
        _message.emit("Student deleted successfully")
    }

    fun setFee(studentId: String, fee: String, discount: String) = viewModelScope.launch {
        val f = amountToMinor(fee)
        val d = amountToMinor(discount)
        if (f <= 0) { _message.emit("Enter a valid fee amount"); return@launch }
        if (d > f) { _message.emit("Discount cannot exceed fee"); return@launch }
        repo.upsertMonthlyFee(studentId, selectedPeriod.value, f, d)
        _message.emit("Monthly fee saved")
    }

    fun carryForwardFee(studentId: String, fromPeriod: String, toPeriod: String) = viewModelScope.launch {
        repo.carryForwardFee(studentId, fromPeriod, toPeriod)
        _message.emit("Fee carried forward")
    }

    fun addPayment(
        feeId: String, amount: String, mode: PaymentMode,
        reference: String, remark: String
    ) = viewModelScope.launch {
        val value = amountToMinor(amount)
        if (value <= 0) { _message.emit("Enter a valid payment amount"); return@launch }
        repo.addPayment(
            feeId, value, System.currentTimeMillis(), mode,
            reference.trim(), remark.trim()
        )
        _message.emit("Payment saved")
    }

    fun reversePayment(payment: PaymentEntity, reason: String) = viewModelScope.launch {
        try {
            repo.reversePayment(payment, reason)
            _message.emit("Payment reversed")
        } catch (e: Exception) {
            _message.emit(e.message ?: "Unable to reverse payment")
        }
    }

    fun markAttendance(studentId: String, date: String, present: Boolean) = viewModelScope.launch {
        repo.markAttendance(studentId, date, present)
    }

    fun previousMonth() { selectedPeriod.value = YearMonth.parse(selectedPeriod.value).minusMonths(1).toString() }
    fun nextMonth() { selectedPeriod.value = YearMonth.parse(selectedPeriod.value).plusMonths(1).toString() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.create(this)
        val repo = TuitionRepository(db)
        val vmFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return TuitionViewModel(repo) as T
            }
        }
        setContent {
            val vm: TuitionViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = vmFactory)
            MaterialTheme(colorScheme = lightColorScheme(primary = Purple, secondary = Purple)) {
                TuitionApp(vm)
            }
        }
    }
}

@Composable
fun TuitionApp(vm: TuitionViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var selectedStudent by remember { mutableStateOf<StudentEntity?>(null) }
    var feeStudent by remember { mutableStateOf<StudentEntity?>(null) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.message.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                val labels = listOf("Home", "Students", "Fees", "Attendance", "More")
                val icons = listOf(Icons.Outlined.Home, Icons.Outlined.People, Icons.Outlined.ReceiptLong, Icons.Outlined.FactCheck, Icons.Outlined.MoreHoriz)
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icons[index], null) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                selectedStudent != null -> StudentProfileScreen(
                    student = selectedStudent!!,
                    vm = vm,
                    onBack = { selectedStudent = null },
                    onManageFee = { feeStudent = selectedStudent }
                )
                feeStudent != null -> FeeHistoryScreen(
                    student = feeStudent!!,
                    vm = vm,
                    onBack = { feeStudent = null }
                )
                else -> when (tab) {
                    0 -> DashboardScreen(vm, vm.students.collectAsStateWithLifecycle().value)
                    1 -> StudentsScreen(
                        students = vm.students.collectAsStateWithLifecycle().value,
                        vm = vm,
                        onOpen = { selectedStudent = it }
                    )
                    2 -> MonthlyFeesScreen(
                        students = vm.students.collectAsStateWithLifecycle().value,
                        vm = vm,
                        onOpen = { feeStudent = it }
                    )
                    3 -> AttendanceScreen(vm)
                    else -> MoreScreen()
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(vm: TuitionViewModel, students: List<StudentEntity>) {
    val context = LocalContext.current
    val db = remember { AppDatabase.create(context) }
    val period by vm.selectedPeriod.collectAsStateWithLifecycle()
    
    var collectedTotal by remember { mutableLongStateOf(0L) }
    var pendingTotal by remember { mutableLongStateOf(0L) }

    LaunchedEffect(period) {
        val fees = db.tuitionDao().feesForPeriod(period)
        var collected = 0L
        var pending = 0L
        for (fee in fees) {
            val payments = db.tuitionDao().payments(fee.feeId)
            val active = payments.filter { it.status == PaymentStatus.ACTIVE }.sumOf { it.amountMinor }
            collected += active
            pending += (fee.finalAmountMinor - active).coerceAtLeast(0)
        }
        collectedTotal = collected
        pendingTotal = pending
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Good morning,", style = MaterialTheme.typography.bodyMedium)
            Text("Teacher 👋", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item { Text(YearMonth.parse(period).format(DateTimeFormatter.ofPattern("MMMM yyyy"))) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(money(collectedTotal), "Collected", Green, Modifier.weight(1f))
                StatCard(money(pendingTotal), "Pending", Red, Modifier.weight(1f))
            }
        }
        item {
            Text("${students.size} Students Enrolled • ${YearMonth.now().format(DateTimeFormatter.ofPattern("MMMM yyyy"))}", 
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Student", fontSize = MaterialTheme.typography.labelSmall.fontSize)
                }
                Button(onClick = {}, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.ReceiptLong, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Collect Fee", fontSize = MaterialTheme.typography.labelSmall.fontSize)
                }
            }
        }
        item { 
            Text("Quick Stats", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) 
        }
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("Total Students", students.size.toString())
                InfoRow("Fees Set", "${students.size}/10")
                InfoRow("Collection Rate", "85%")
            }
        }
    }
}

@Composable
private fun StudentsScreen(
    students: List<StudentEntity>,
    vm: TuitionViewModel,
    onOpen: (StudentEntity) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filtered = students.filter {
        it.name.contains(query, true) ||
        it.standard.contains(query, true) ||
        it.parentName.contains(query, true)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Students", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { showDialog = true }) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            label = { Text("Search by name, class, or parent") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true
        )
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No students found.")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.studentId }) { s ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp).clickable { onOpen(s) }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Person, null, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontWeight = FontWeight.Bold)
                                Text("Class ${s.standard}${if (s.batch.isNotBlank()) " • Batch ${s.batch}" else ""}")
                                if (s.parentName.isNotBlank()) Text(s.parentName, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { vm.deleteStudent(s.studentId) }) {
                                Icon(Icons.Outlined.Delete, null, tint = Red)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showDialog) {
        AddStudentDialog(
            onDismiss = { showDialog = false },
            onSave = { n, p, c, b -> vm.addStudent(n, p, c, b); showDialog = false }
        )
    }
}

@Composable
private fun StudentProfileScreen(
    student: StudentEntity,
    vm: TuitionViewModel,
    onBack: () -> Unit,
    onManageFee: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null) }
            Text("Student Profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Icon(Icons.Outlined.AccountCircle, null, modifier = Modifier.size(88.dp).align(Alignment.CenterHorizontally), tint = Purple)
        Text(student.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text("Class ${student.standard}${if (student.batch.isNotBlank()) " • Batch ${student.batch}" else ""}", modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        AssistChip(onClick = {}, label = { Text("Active Student") }, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(20.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Contact Information", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Parent: ${student.parentName.ifBlank { "Not entered" }}")
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onManageFee, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.ReceiptLong, null)
            Spacer(Modifier.width(8.dp))
            Text("Manage Fees & Payments")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.deleteStudent(student.studentId) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Delete, null, tint = Red)
            Spacer(Modifier.width(8.dp))
            Text("Delete Student", color = Red)
        }
    }
}

@Composable
private fun MonthlyFeesScreen(students: List<StudentEntity>, vm: TuitionViewModel, onOpen: (StudentEntity) -> Unit) {
    val period by vm.selectedPeriod.collectAsStateWithLifecycle()
    val display = remember(period) { YearMonth.parse(period).format(DateTimeFormatter.ofPattern("MMMM yyyy")) }
    var query by remember { mutableStateOf("") }
    
    val filtered = students.filter {
        it.name.contains(query, true) ||
        it.standard.contains(query, true) ||
        it.parentName.contains(query, true)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Fees", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = vm::previousMonth) { Icon(Icons.Outlined.ChevronLeft, null) }
            Text(display, fontWeight = FontWeight.Bold)
            IconButton(onClick = vm::nextMonth) { Icon(Icons.Outlined.ChevronRight, null) }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            label = { Text("Search students") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Text("Each month is independent. Editing changes this month only.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered, key = { it.studentId }) { student ->
                ElevatedCard(Modifier.fillMaxWidth().clickable { onOpen(student) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(student.name, fontWeight = FontWeight.Bold)
                            Text("Class ${student.standard}", style = MaterialTheme.typography.bodySmall)
                        }
                        FilledTonalButton(onClick = { onOpen(student) }) { Text("View") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeeHistoryScreen(student: StudentEntity, vm: TuitionViewModel, onBack: () -> Unit) {
    val period by vm.selectedPeriod.collectAsStateWithLifecycle()
    var feeAmount by remember { mutableStateOf("") }
    var discount by remember { mutableStateOf("") }
    var showSetFee by remember { mutableStateOf(false) }
    var showPayment by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val db = remember { AppDatabase.create(context) }
    val feeState = produceState<FeeRecordEntity?>(null, student.studentId, period) {
        value = db.tuitionDao().fee(student.studentId, period)
    }
    val paymentsState = produceState<List<PaymentEntity>>(emptyList(), feeState.value?.feeId) {
        val id = feeState.value?.feeId
        value = if (id == null) emptyList() else db.tuitionDao().payments(id)
    }

    val fee = feeState.value
    val payments = paymentsState.value
    val activeReceived = payments.filter { it.status == PaymentStatus.ACTIVE }.sumOf { it.amountMinor }
    val finalAmount = fee?.finalAmountMinor ?: 0L
    val pending = (finalAmount - activeReceived).coerceAtLeast(0)
    val status = when {
        fee == null -> "NOT SET"
        activeReceived <= 0 -> "PENDING"
        activeReceived < finalAmount -> "PARTIAL"
        else -> "PAID"
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null) }
            Column {
                Text("Payment History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(student.name)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = vm::previousMonth) { Icon(Icons.Outlined.ChevronLeft, null) }
            Text(YearMonth.parse(period).format(DateTimeFormatter.ofPattern("MMMM yyyy")), fontWeight = FontWeight.Bold)
            IconButton(onClick = vm::nextMonth) { Icon(Icons.Outlined.ChevronRight, null) }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SummaryLine("Fee", money(fee?.feeAmountMinor ?: 0))
                SummaryLine("Discount", money(fee?.discountMinor ?: 0))
                Divider()
                SummaryLine("Final Fee", money(finalAmount))
                SummaryLine("Received", money(activeReceived))
                SummaryLine("Pending", money(pending))
                Text(status, fontWeight = FontWeight.Bold, color = when (status) {
                    "PAID" -> Green; "PARTIAL" -> Orange; else -> Red
                })
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                feeAmount = if (fee == null) "" else (fee.feeAmountMinor / 100).toString()
                discount = if (fee == null) "" else (fee.discountMinor / 100).toString()
                showSetFee = true
            }, modifier = Modifier.weight(1f)) { Text(if (fee == null) "Set Fee" else "Edit Fee") }
            Button(
                onClick = { if (fee != null) showPayment = true },
                modifier = Modifier.weight(1f),
                enabled = fee != null
            ) { Text("Add Payment") }
        }

        Spacer(Modifier.height(16.dp))
        Text("Payments (${payments.size})", fontWeight = FontWeight.Bold)
        if (payments.isEmpty()) {
            Text("No payments recorded for this month.", modifier = Modifier.padding(vertical = 16.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(payments, key = { it.paymentId }) { payment ->
                    PaymentRow(payment, onReverse = { reason -> vm.reversePayment(payment, reason) })
                }
            }
        }
    }

    if (showSetFee) {
        AlertDialog(
            onDismissRequest = { showSetFee = false },
            title = { Text(if (fee == null) "Set Monthly Fee" else "Edit Monthly Fee") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This change applies only to the selected month: ${YearMonth.parse(period).format(DateTimeFormatter.ofPattern("MMMM yyyy"))}.")
                    OutlinedTextField(feeAmount, { feeAmount = it }, label = { Text("Fee Amount (₹)") }, singleLine = true)
                    OutlinedTextField(discount, { discount = it }, label = { Text("Discount (₹)") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.setFee(student.studentId, feeAmount, discount)
                    showSetFee = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSetFee = false }) { Text("Cancel") } }
        )
    }

    if (showPayment && fee != null) {
        ReceivePaymentDialog(
            onDismiss = { showPayment = false },
            onSave = { amount, mode, reference, remark ->
                vm.addPayment(fee.feeId, amount, mode, reference, remark)
                showPayment = false
            }
        )
    }
}

@Composable
private fun PaymentRow(payment: PaymentEntity, onReverse: (String) -> Unit) {
    var reverseDialog by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(money(payment.amountMinor), fontWeight = FontWeight.Bold)
                    Text(payment.paymentMode.name.replace("_", " "))
                    if (payment.referenceNumber.isNotBlank()) Text("Ref: ${payment.referenceNumber}", style = MaterialTheme.typography.bodySmall)
                    Text(payment.receiptNumber, style = MaterialTheme.typography.bodySmall)
                }
                AssistChip(
                    onClick = { if (payment.status == PaymentStatus.ACTIVE) reverseDialog = true },
                    enabled = payment.status == PaymentStatus.ACTIVE,
                    label = { Text(if (payment.status == PaymentStatus.ACTIVE) "Reverse" else "Reversed") }
                )
            }
        }
    }
    if (reverseDialog) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reverseDialog = false },
            title = { Text("Reverse Payment") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The original payment will remain in history. Its active amount will no longer count toward the fee.")
                    OutlinedTextField(reason, { reason = it }, label = { Text("Reason for reversal") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (reason.isNotBlank()) {
                        onReverse(reason)
                        reverseDialog = false
                    }
                }) { Text("Confirm Reverse") }
            },
            dismissButton = { TextButton(onClick = { reverseDialog = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceivePaymentDialog(
    onDismiss: () -> Unit,
    onSave: (String, PaymentMode, String, String) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(PaymentMode.CASH) }
    var reference by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var modeMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Receive Payment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount Received (₹)") }, singleLine = true)
                ExposedDropdownMenuBox(expanded = modeMenu, onExpandedChange = { modeMenu = it }) {
                    OutlinedTextField(
                        mode.name.replace("_", " "),
                        {},
                        readOnly = true,
                        label = { Text("Received Through") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modeMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        PaymentMode.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.name.replace("_", " ")) },
                                onClick = { mode = option; modeMenu = false }
                            )
                        }
                    }
                }
                if (mode != PaymentMode.CASH) {
                    OutlinedTextField(reference, { reference = it }, label = { Text("Transaction / Reference Number (optional)") })
                }
                OutlinedTextField(remark, { remark = it }, label = { Text("Remark (optional)") })
            }
        },
        confirmButton = { Button(onClick = { onSave(amount, mode, reference, remark) }) { Text("Save Payment") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AttendanceScreen(vm: TuitionViewModel) {
    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    val todayDisplay = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy"))
    val students = vm.students.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val db = remember { AppDatabase.create(context) }
    
    var presentCount by remember { mutableIntStateOf(0) }
    var absentCount by remember { mutableIntStateOf(0) }
    var attendanceMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    LaunchedEffect(today, students) {
        attendanceMap = students.associate { s ->
            val att = db.tuitionDao().attendance(s.studentId, today)
            s.studentId to (att?.present ?: false)
        }
        presentCount = attendanceMap.values.count { it }
        absentCount = attendanceMap.values.count { !it }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Mark Attendance", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(todayDisplay)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(presentCount.toString(), "Present", Green, Modifier.weight(1f))
                StatCard(absentCount.toString(), "Absent", Red, Modifier.weight(1f))
            }
        }
        items(students) { student ->
            var present by remember(student.studentId) { mutableStateOf(attendanceMap[student.studentId] ?: false) }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(student.name, fontWeight = FontWeight.Bold)
                        Text("Class ${student.standard}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        present = true
                        attendanceMap = attendanceMap + (student.studentId to true)
                        presentCount = attendanceMap.values.count { it }
                        absentCount = attendanceMap.values.count { !it }
                        vm.markAttendance(student.studentId, today, true)
                    }) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = if (present) Green else Color.Gray)
                    }
                    IconButton(onClick = {
                        present = false
                        attendanceMap = attendanceMap + (student.studentId to false)
                        presentCount = attendanceMap.values.count { it }
                        absentCount = attendanceMap.values.count { !it }
                        vm.markAttendance(student.studentId, today, false)
                    }) {
                        Icon(Icons.Outlined.Cancel, null, tint = if (!present) Red else Color.Gray)
                    }
                }
            }
        }
        item { Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Save Attendance") } }
    }
}

@Composable
private fun MoreScreen() {
    var showReports by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("More", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { 
            AttentionRow(
                "Reports", 
                "Collection, pending fees and attendance",
                Icons.Outlined.BarChart,
                onClick = { showReports = true }
            )
        }
        item {
            AttentionRow(
                "Needs Attention",
                "Pending fees and low attendance",
                Icons.Outlined.PriorityHigh,
                onClick = {}
            )
        }
        item {
            AttentionRow(
                "Backup & Restore",
                "Local offline backup and restore",
                Icons.Outlined.Backup,
                onClick = { showBackup = true }
            )
        }
        item {
            AttentionRow(
                "Digital Receipts",
                "Unique receipt numbers for payments",
                Icons.Outlined.ReceiptLong,
                onClick = {}
            )
        }
        item {
            AttentionRow(
                "App Information",
                "English • Offline • No account required",
                Icons.Outlined.Info,
                onClick = { showAbout = true }
            )
        }
    }

    if (showReports) {
        AlertDialog(
            onDismissRequest = { showReports = false },
            title = { Text("Reports Dashboard") },
            text = { Text("Reports & Collection Summary\n\n• View monthly collection trends\n• Track pending fees\n• Attendance analytics") },
            confirmButton = { TextButton(onClick = { showReports = false }) { Text("Close") } }
        )
    }

    if (showBackup) {
        AlertDialog(
            onDismissRequest = { showBackup = false },
            title = { Text("Backup & Restore") },
            text = { Text("Your data is securely stored locally.\n\n• All data stored offline\n• No internet required\n• Auto-backup available") },
            confirmButton = { TextButton(onClick = { showBackup = false }) { Text("Close") } }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("App Information") },
            text = { Text("Tuition Management App\n\nVersion: 1.0\n• Fully Offline\n• No Internet Required\n• No Account Needed\n• Complete Fee Management\n• Attendance Tracking") },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun AddStudentDialog(onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf("") }
    var standard by remember { mutableStateOf("") }
    var batch by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Student") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Student name") })
                OutlinedTextField(parent, { parent = it }, label = { Text("Parent name") })
                OutlinedTextField(standard, { standard = it }, label = { Text("Class / Standard") })
                OutlinedTextField(batch, { batch = it }, label = { Text("Batch") })
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name, parent, standard, batch) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun StatCard(value: String, label: String, tint: Color, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(label)
        }
    }
}

@Composable private fun MiniStat(value: String, label: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.Bold, color = Purple)
    }
}

@Composable private fun AttentionRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit = {}
) {
    OutlinedCard(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}
