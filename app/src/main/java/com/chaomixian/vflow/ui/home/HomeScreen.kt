package com.chaomixian.vflow.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionStateBus
import com.chaomixian.vflow.core.execution.WorkflowExecutor
import com.chaomixian.vflow.core.locale.toast
import com.chaomixian.vflow.core.logging.LogEntry
import com.chaomixian.vflow.core.logging.LogManager
import com.chaomixian.vflow.core.logging.LogStatus
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.permissions.PermissionActivity
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.services.VFlowCoreBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class HomeUiState(
    val totalWorkflowCount: Int = 0,
    val autoWorkflowCount: Int = 0,
    val enabledAutoWorkflowCount: Int = 0,
    val coreConnected: Boolean = false,
    val corePrivilegeMode: VFlowCoreBridge.PrivilegeMode = VFlowCoreBridge.PrivilegeMode.NONE,
    val coreNeedsUpdate: Boolean = false,
    val coreRunningVersionName: String? = null,
    val corePackagedVersionName: String? = null,
    val missingPermissionCount: Int = 0,
    val quickWorkflows: List<Workflow> = emptyList(),
    val recentLogs: List<LogEntry> = emptyList(),
    val allLogs: List<LogEntry> = emptyList(),
)

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun HomeScreen(
    isActive: Boolean,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val workflowManager = remember(context) { WorkflowManager(context) }
    val coroutineScope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf(HomeUiState()) }
    var executionStateVersion by remember { mutableIntStateOf(0) }
    var pendingWorkflowId by rememberSaveable { mutableStateOf<String?>(null) }
    var coreStatusRefreshJob by remember { mutableStateOf<Job?>(null) }
    var permissionHealthRefreshJob by remember { mutableStateOf<Job?>(null) }
    var logSheetVisible by rememberSaveable { mutableStateOf(false) }
    var selectedLog by remember { mutableStateOf<LogEntry?>(null) }
    val logSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pendingWorkflow = workflowManager.getAllWorkflows().firstOrNull { it.id == pendingWorkflowId }
        if (result.resultCode == Activity.RESULT_OK && pendingWorkflow != null) {
            executeWorkflow(context, pendingWorkflow, checkPermissions = false)
        }
        pendingWorkflowId = null
    }

    fun refreshStatisticsAndLists() {
        coroutineScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                loadWorkflowSnapshot(workflowManager)
            }
            uiState = uiState.copy(
                totalWorkflowCount = snapshot.totalWorkflowCount,
                autoWorkflowCount = snapshot.autoWorkflowCount,
                enabledAutoWorkflowCount = snapshot.enabledAutoWorkflowCount,
                quickWorkflows = snapshot.quickWorkflows,
                recentLogs = snapshot.recentLogs,
                allLogs = snapshot.allLogs,
            )
        }
    }

    fun refreshCoreStatus() {
        coroutineScope.launch {
            val coreState = withContext(Dispatchers.IO) {
                loadCoreState()
            }
            uiState = uiState.copy(
                coreConnected = coreState.coreConnected,
                corePrivilegeMode = coreState.corePrivilegeMode,
                coreNeedsUpdate = coreState.coreNeedsUpdate,
                coreRunningVersionName = coreState.coreRunningVersionName,
                corePackagedVersionName = coreState.corePackagedVersionName,
            )
        }
    }

    fun refreshPermissionHealth() {
        coroutineScope.launch {
            val missingPermissionCount = withContext(Dispatchers.IO) {
                getMissingPermissionCount(context, workflowManager)
            }
            uiState = uiState.copy(missingPermissionCount = missingPermissionCount)
        }
    }

    fun refreshAll() {
        refreshStatisticsAndLists()
        refreshCoreStatus()
        refreshPermissionHealth()
    }

    fun startCoreStatusAutoRefresh() {
        coreStatusRefreshJob?.cancel()
        if (uiState.coreConnected) return
        coreStatusRefreshJob = coroutineScope.launch {
            repeat(16) {
                delay(500)
                val coreState = withContext(Dispatchers.IO) {
                    loadCoreState()
                }
                uiState = uiState.copy(
                    coreConnected = coreState.coreConnected,
                    corePrivilegeMode = coreState.corePrivilegeMode,
                    coreNeedsUpdate = coreState.coreNeedsUpdate,
                    coreRunningVersionName = coreState.coreRunningVersionName,
                    corePackagedVersionName = coreState.corePackagedVersionName,
                )
                if (coreState.coreConnected) {
                    return@launch
                }
            }
        }
    }

    fun startPermissionHealthAutoRefresh() {
        permissionHealthRefreshJob?.cancel()
        permissionHealthRefreshJob = coroutineScope.launch {
            repeat(20) {
                delay(500)
                val missingPermissionCount = withContext(Dispatchers.IO) {
                    getMissingPermissionCount(context, workflowManager)
                }
                uiState = uiState.copy(missingPermissionCount = missingPermissionCount)
                if (missingPermissionCount == 0) {
                    return@launch
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        ExecutionStateBus.stateFlow.collectLatest {
            executionStateVersion++
            uiState = uiState.copy(
                recentLogs = LogManager.getRecentLogs(5),
                allLogs = LogManager.getAllLogs(),
            )
        }
    }

    LaunchedEffect(isActive) {
        if (isActive) {
            refreshAll()
            startCoreStatusAutoRefresh()
            startPermissionHealthAutoRefresh()
        } else {
            coreStatusRefreshJob?.cancel()
            permissionHealthRefreshJob?.cancel()
        }
    }

    LaunchedEffect(logSheetVisible) {
        if (logSheetVisible && logSheetState.hasPartiallyExpandedState) {
            logSheetState.partialExpand()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            coreStatusRefreshJob?.cancel()
            permissionHealthRefreshJob?.cancel()
        }
    }

    val openCoreManagement = remember(context) {
        {
            context.startActivity(
                Intent(context, com.chaomixian.vflow.ui.settings.CoreManagementActivity::class.java)
            )
        }
    }
    val openPermissionHealth = remember(context) {
        {
            context.startActivity(
                Intent(context, PermissionActivity::class.java).apply {
                    putParcelableArrayListExtra(
                        PermissionActivity.EXTRA_PERMISSIONS,
                        ArrayList(PermissionManager.getAllRegisteredPermissions())
                    )
                }
            )
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = bottomContentPadding + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                HomeSummarySection(
                    uiState = uiState,
                    onOpenCoreManagement = openCoreManagement,
                )
            }

            if (uiState.coreConnected && uiState.coreNeedsUpdate) {
                item {
                    HomeInfoCard(
                        onClick = openCoreManagement,
                        leadingIconRes = R.drawable.rounded_system_update_alt_24,
                        title = stringResource(R.string.home_core_update_title),
                        description = stringResource(
                            R.string.home_core_update_desc,
                            uiState.coreRunningVersionName ?: stringResource(R.string.core_version_unknown),
                            uiState.corePackagedVersionName ?: stringResource(R.string.core_version_unknown)
                        ),
                    )
                }
            }

            item {
                PermissionHealthCard(
                    missingPermissionCount = uiState.missingPermissionCount,
                    onClick = openPermissionHealth,
                )
            }

            if (uiState.recentLogs.isNotEmpty()) {
                item {
                    SectionCard(
                        title = stringResource(R.string.home_recent_logs),
                        onClick = { logSheetVisible = true },
                    ) {
                        RecentLogsList(
                            logs = uiState.recentLogs,
                            onShowDetail = { log -> selectedLog = log }
                        )
                    }
                }
            }

            if (uiState.quickWorkflows.isNotEmpty()) {
                item {
                    SectionCard(
                        title = stringResource(R.string.home_quick_execute),
                    ) {
                        QuickExecuteList(
                            workflows = uiState.quickWorkflows,
                            executionStateVersion = executionStateVersion,
                            onWorkflowClick = { workflow ->
                                if (WorkflowExecutor.isRunning(workflow.id)) {
                                    WorkflowExecutor.stopExecution(workflow.id)
                                    context.toast(context.getString(R.string.home_stopped_execution, workflow.name))
                                } else {
                                    val missingPermissions = PermissionManager.getMissingPermissions(context, workflow)
                                    if (missingPermissions.isNotEmpty()) {
                                        pendingWorkflowId = workflow.id
                                        permissionLauncher.launch(
                                            Intent(context, PermissionActivity::class.java).apply {
                                                putParcelableArrayListExtra(
                                                    PermissionActivity.EXTRA_PERMISSIONS,
                                                    ArrayList(missingPermissions)
                                                )
                                                putExtra(PermissionActivity.EXTRA_WORKFLOW_NAME, workflow.name)
                                            }
                                        )
                                    } else {
                                        executeWorkflow(context, workflow)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        if (logSheetVisible) {
            ModalBottomSheet(
                onDismissRequest = { logSheetVisible = false },
                sheetState = logSheetState,
            ) {
                LogViewerBottomSheet(
                    logs = uiState.allLogs,
                    onLogClick = { selectedLog = it },
                    onClearLogs = {
                        LogManager.clearLogs()
                        uiState = uiState.copy(
                            recentLogs = emptyList(),
                            allLogs = emptyList(),
                        )
                        context.toast(context.getString(R.string.settings_toast_logs_cleared))
                    },
                )
            }
        }

        selectedLog?.let { log ->
            LogDetailDialog(
                log = log,
                onDismiss = { selectedLog = null },
                onDelete = {
                    LogManager.deleteLog(it)
                    selectedLog = null
                    uiState = uiState.copy(
                        recentLogs = LogManager.getRecentLogs(5),
                        allLogs = LogManager.getAllLogs(),
                    )
                    context.toast(context.getString(R.string.toast_module_deleted))
                },
            )
        }
    }
}

private data class WorkflowSnapshot(
    val totalWorkflowCount: Int,
    val autoWorkflowCount: Int,
    val enabledAutoWorkflowCount: Int,
    val quickWorkflows: List<Workflow>,
    val recentLogs: List<LogEntry>,
    val allLogs: List<LogEntry>,
)

private data class CoreStateSnapshot(
    val coreConnected: Boolean,
    val corePrivilegeMode: VFlowCoreBridge.PrivilegeMode,
    val coreNeedsUpdate: Boolean,
    val coreRunningVersionName: String?,
    val corePackagedVersionName: String?,
)

private suspend fun loadWorkflowSnapshot(
    workflowManager: WorkflowManager,
): WorkflowSnapshot {
    val allWorkflows = workflowManager.getAllWorkflows()
    val autoWorkflows = allWorkflows.filter { it.hasAutoTriggers() }
    val quickWorkflows = allWorkflows.filter { it.isFavorite && it.hasManualTrigger() }
    return WorkflowSnapshot(
        totalWorkflowCount = allWorkflows.size,
        autoWorkflowCount = autoWorkflows.size,
        enabledAutoWorkflowCount = autoWorkflows.count { it.isEnabled },
        quickWorkflows = quickWorkflows,
        recentLogs = LogManager.getRecentLogs(5),
        allLogs = LogManager.getAllLogs(),
    )
}

private fun loadCoreState(): CoreStateSnapshot {
    val pingSuccess = VFlowCoreBridge.ping()
    val isConnected = pingSuccess && VFlowCoreBridge.isConnected
    val privilegeMode = if (isConnected) {
        VFlowCoreBridge.privilegeMode
    } else {
        VFlowCoreBridge.PrivilegeMode.NONE
    }
    val versionStatus = VFlowCoreBridge.getCoreVersionStatus()
    return CoreStateSnapshot(
        coreConnected = isConnected,
        corePrivilegeMode = privilegeMode,
        coreNeedsUpdate = isConnected && versionStatus.needsUpdate,
        coreRunningVersionName = versionStatus.running?.versionName,
        corePackagedVersionName = versionStatus.packaged.versionName,
    )
}

private fun getMissingPermissionCount(
    context: Context,
    workflowManager: WorkflowManager,
): Int {
    val allWorkflows = workflowManager.getAllWorkflows()
    val requiredPermissions = allWorkflows
        .flatMap { it.allSteps }
        .mapNotNull { step ->
            ModuleRegistry.getModule(step.moduleId)?.getRequiredPermissions(step)
        }
        .flatten()
        .distinct()

    return requiredPermissions.count { !PermissionManager.isGranted(context, it) }
}

private fun executeWorkflow(
    context: Context,
    workflow: Workflow,
    checkPermissions: Boolean = true,
) {
    if (checkPermissions) {
        val missingPermissions = PermissionManager.getMissingPermissions(context, workflow)
        if (missingPermissions.isNotEmpty()) {
            return
        }
    }
    context.toast(context.getString(R.string.home_starting_execution, workflow.name))
    WorkflowExecutor.execute(
        workflow = workflow,
        context = context,
        triggerStepId = workflow.manualTrigger()?.id
    )
}

@Composable
private fun HomeSummarySection(
    uiState: HomeUiState,
    onOpenCoreManagement: () -> Unit,
) {
    val totalCard: @Composable () -> Unit = {
        StatCard(
            title = stringResource(R.string.home_total_workflows),
            value = uiState.totalWorkflowCount.toString(),
        )
    }
    val autoCard: @Composable () -> Unit = {
        StatCard(
            title = stringResource(R.string.home_auto_tasks),
            value = stringResource(
                R.string.home_auto_tasks_stats,
                uiState.enabledAutoWorkflowCount,
                uiState.autoWorkflowCount
            ),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CoreStatusCard(
            uiState = uiState,
            onClick = onOpenCoreManagement,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) { totalCard() }
            Box(modifier = Modifier.weight(1f)) { autoCard() }
        }
    }
}

@Composable
private fun CoreStatusCard(
    uiState: HomeUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (uiState.coreConnected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (uiState.coreConnected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 160.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                painter = painterResource(
                    if (uiState.coreConnected) {
                        R.drawable.rounded_check_circle_24
                    } else {
                        R.drawable.rounded_cancel_24
                    }
                ),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 40.dp, y = 40.dp)
                    .size(160.dp)
                    .alpha(0.2f)
            )
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_core_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor.copy(alpha = 0.9f)
                )
                Text(
                    text = stringResource(
                        if (uiState.coreConnected) {
                            R.string.home_core_status_working
                        } else {
                            R.string.home_core_status_stopped
                        }
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text(
                    text = stringResource(
                        R.string.home_core_mode,
                        stringResource(
                            when (uiState.corePrivilegeMode) {
                                VFlowCoreBridge.PrivilegeMode.ROOT -> R.string.home_privilege_root
                                VFlowCoreBridge.PrivilegeMode.SHELL -> R.string.home_privilege_shell
                                VFlowCoreBridge.PrivilegeMode.NONE -> R.string.home_privilege_none
                            }
                        )
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .defaultMinSize(minHeight = 74.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HomeInfoCard(
    onClick: () -> Unit,
    leadingIconRes: Int,
    title: String,
    description: String,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(leadingIconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Column(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun PermissionHealthCard(
    missingPermissionCount: Int,
    onClick: () -> Unit,
) {
    HomeInfoCard(
        onClick = onClick,
        leadingIconRes = R.drawable.rounded_security_24,
        title = stringResource(R.string.home_permission_health),
        description = if (missingPermissionCount == 0) {
            stringResource(R.string.home_permission_good)
        } else {
            stringResource(R.string.home_permission_missing, missingPermissionCount)
        },
    )
}

@Composable
private fun SectionCard(
    title: String,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
            content()
        }
    }
}

@Composable
private fun RecentLogsList(
    logs: List<LogEntry>,
    onShowDetail: (LogEntry) -> Unit,
) {
    Column {
        logs.forEachIndexed { index, log ->
            LogRow(
                log = log,
                onClick = { onShowDetail(log) }
            )
            if (index < logs.lastIndex) {
                SectionDivider()
            }
        }
    }
}

@Composable
private fun QuickExecuteList(
    workflows: List<Workflow>,
    executionStateVersion: Int,
    onWorkflowClick: (Workflow) -> Unit,
) {
    Column {
        workflows.forEachIndexed { index, workflow ->
            QuickExecuteRow(
                workflow = workflow,
                executionStateVersion = executionStateVersion,
                onClick = { onWorkflowClick(workflow) }
            )
            if (index < workflows.lastIndex) {
                SectionDivider()
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    Spacer(
        modifier = Modifier
            .padding(start = 64.dp, end = 20.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}

@Composable
private fun LogViewerBottomSheet(
    logs: List<LogEntry>,
    onLogClick: (LogEntry) -> Unit,
    onClearLogs: () -> Unit,
) {
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.label_execution_log),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            FilledTonalButton(
                onClick = { showClearConfirmDialog = true },
                enabled = logs.isNotEmpty(),
            ) {
                Text(text = stringResource(R.string.settings_button_clear_logs))
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(logs, key = { _, log -> "${log.workflowId}-${log.timestamp}" }) { index, log ->
                LogRow(
                    log = log,
                    onClick = { onLogClick(log) }
                )
                if (index < logs.lastIndex) {
                    SectionDivider()
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text(text = stringResource(R.string.home_logs_clear_confirm_title))
            },
            text = {
                Text(text = stringResource(R.string.home_logs_clear_confirm_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearLogs()
                        showClearConfirmDialog = false
                    }
                ) {
                    Text(text = stringResource(R.string.settings_button_clear_logs))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun LogDetailDialog(
    log: LogEntry,
    onDismiss: () -> Unit,
    onDelete: (LogEntry) -> Unit,
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val summaryScrollState = rememberScrollState()
    val detailsScrollState = rememberScrollState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.log_tab_summary),
        stringResource(R.string.log_tab_details)
    )

    // 从详细日志中解析统计信息
    val parsedStats = remember(log.detailedLog) { parseDetailedLog(log.detailedLog) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
        ) {
            Column {
                // ===== 顶部标题区 =====
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val statusIcon = when (log.status) {
                            LogStatus.SUCCESS -> R.drawable.ic_log_success
                            LogStatus.FAILURE, LogStatus.CANCELLED -> R.drawable.ic_log_failure
                        }
                        val statusTint = when (log.status) {
                            LogStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                            LogStatus.FAILURE, LogStatus.CANCELLED -> MaterialTheme.colorScheme.error
                        }
                        Icon(
                            painter = painterResource(statusIcon),
                            contentDescription = null,
                            tint = statusTint,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = log.workflowName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusChip(log.status)
                        Text(
                            text = dateFormat.format(Date(log.timestamp)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }

                // ===== Tab 切换 =====
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    divider = {},
                    edgePadding = 16.dp
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    text = title,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                // ===== Tab 内容 =====
                androidx.compose.animation.AnimatedVisibility(visible = selectedTab == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(summaryScrollState)
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        // 状态消息卡片
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = when (log.status) {
                                    LogStatus.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
                                    LogStatus.FAILURE -> MaterialTheme.colorScheme.errorContainer
                                    LogStatus.CANCELLED -> MaterialTheme.colorScheme.tertiaryContainer
                                }
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = log.resolveMessage(context)
                                    ?: stringResource(R.string.log_no_detail_message),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(16.dp)
                            )
                        }

                        Spacer(Modifier.height(20.dp))

                        // 统计信息网格
                        SummaryGrid(
                            items = buildList {
                                add(
                                    SummaryItem(
                                        label = stringResource(R.string.log_label_workflow),
                                        value = log.workflowName
                                    )
                                )
                                parsedStats.durationMs?.let {
                                    add(
                                        SummaryItem(
                                            label = stringResource(R.string.log_label_duration),
                                            value = stringResource(R.string.log_value_ms, it)
                                        )
                                    )
                                }
                                parsedStats.stepCount?.let {
                                    add(
                                        SummaryItem(
                                            label = stringResource(R.string.log_label_step_count),
                                            value = stringResource(R.string.log_value_steps, it)
                                        )
                                    )
                                }
                                if (log.status == LogStatus.FAILURE && parsedStats.failedStep != null) {
                                    add(
                                        SummaryItem(
                                            label = stringResource(R.string.log_label_failed_step),
                                            value = parsedStats.failedStep,
                                            isError = true
                                        )
                                    )
                                }
                                add(
                                    SummaryItem(
                                        label = "工作流 ID",
                                        value = log.workflowId,
                                        mono = true
                                    )
                                )
                            }
                        )
                    }
                }

                androidx.compose.animation.AnimatedVisibility(visible = selectedTab == 1) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        StyledLogText(
                            text = log.detailedLog
                                ?: stringResource(R.string.text_no_detailed_logs),
                            scrollState = detailsScrollState,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // ===== 底部按钮 =====
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { onDelete(log) }) {
                        Text(
                            stringResource(R.string.common_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.FilledTonalButton(onClick = onDismiss) {
                        Text(stringResource(R.string.button_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: LogStatus) {
    val (text, containerColor, contentColor) = when (status) {
        LogStatus.SUCCESS -> Triple(
            stringResource(R.string.text_execution_success),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        LogStatus.FAILURE -> Triple(
            "执行失败",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        LogStatus.CANCELLED -> Triple(
            stringResource(R.string.log_message_execution_cancelled),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

private data class SummaryItem(
    val label: String,
    val value: String,
    val isError: Boolean = false,
    val mono: Boolean = false
)

private data class ParsedStats(
    val durationMs: Long? = null,
    val stepCount: Int? = null,
    val failedStep: String? = null
)

private fun parseDetailedLog(raw: String?): ParsedStats {
    if (raw.isNullOrEmpty()) return ParsedStats()
    var duration: Long? = null
    var steps: Int? = null
    var failed: String? = null
    val stepLineRegex = Regex("""\[#(\d+)\]\s*->""")
    val stepNumbers = stepLineRegex.findAll(raw).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
    if (stepNumbers.isNotEmpty()) {
        steps = stepNumbers.maxOrNull()
    }
    val failedMatch = Regex("""步骤\s*#?(\d+)|在步骤\s*#?(\d+)|#(\d+)[^\n]*失败""").find(raw)
    if (failedMatch != null) {
        val n = failedMatch.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
        if (n != null) failed = "#$n"
    }
    return ParsedStats(duration, steps, failed)
}

@Composable
private fun SummaryGrid(items: List<SummaryItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(96.dp)
                )
                val valueColor = when {
                    item.isError -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    text = item.value,
                    style = if (item.mono) androidx.compose.material3.Typography().bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ) else MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = valueColor,
                    modifier = Modifier.weight(1f)
                )
            }
            androidx.compose.material3.Divider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
        }
    }
}

@Composable
private fun StyledLogText(
    text: String,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier
) {
    val lines = text.lines()
    val styledLines = remember(text) { lines.map { parseLogLine(it) } }
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        androidx.compose.foundation.text.BasicText(
            text = buildAnnotatedString {
                styledLines.forEachIndexed { idx, line ->
                    withStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = when (line.level) {
                                LogLineLevel.ERROR -> MaterialTheme.colorScheme.error
                                LogLineLevel.WARN -> androidx.compose.ui.graphics.Color(0xFFFFB74D.toInt())
                                LogLineLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                                LogLineLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                LogLineLevel.PLAIN -> MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = if (line.level == LogLineLevel.ERROR) FontWeight.Bold else FontWeight.Normal
                        )
                    ) {
                        append(line.content)
                    }
                    if (idx < styledLines.lastIndex) append('\n')
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
                .verticalScroll(scrollState),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                lineHeight = androidx.compose.ui.unit.TextUnit(1.55f, androidx.compose.ui.unit.TextUnitType.Em)
            )
        )
    }
}

private enum class LogLineLevel { ERROR, WARN, INFO, DEBUG, PLAIN }
private data class ParsedLogLine(val content: String, val level: LogLineLevel)

private fun parseLogLine(raw: String): ParsedLogLine {
    val trimmed = raw.trimEnd()
    val level = when {
        "/E/" in trimmed || " E/" in trimmed ||
            trimmed.contains("错误", ignoreCase = true) ||
            trimmed.contains("失败", ignoreCase = true) ||
            trimmed.contains("Failed", ignoreCase = true) ||
            trimmed.contains("Exception", ignoreCase = true) -> LogLineLevel.ERROR
        "/W/" in trimmed || " W/" in trimmed ||
            trimmed.contains("警告", ignoreCase = true) -> LogLineLevel.WARN
        "/I/" in trimmed || " I/" in trimmed -> LogLineLevel.INFO
        "/D/" in trimmed || " D/" in trimmed || trimmed.startsWith("[进度]") || trimmed.startsWith("[") && "] D/" in trimmed -> LogLineLevel.DEBUG
        else -> LogLineLevel.PLAIN
    }
    return ParsedLogLine(trimmed, level)
}

@Composable
private fun LogDetailCodeBlock(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun LogRow(
    log: LogEntry,
    onClick: () -> Unit,
) {
    val iconRes = when (runCatching { log.status }.getOrNull()) {
        LogStatus.SUCCESS -> R.drawable.ic_log_success
        LogStatus.FAILURE, LogStatus.CANCELLED, null -> R.drawable.ic_log_failure
    }
    val iconTint = when (runCatching { log.status }.getOrNull()) {
        LogStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        LogStatus.FAILURE, LogStatus.CANCELLED, null -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Column(
            modifier = Modifier
                .padding(start = 20.dp)
                .weight(1f)
        ) {
            Text(
                text = log.workflowName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = log.resolveMessage(LocalContext.current).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = DateUtils.getRelativeTimeSpanString(
                log.timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            ).toString(),
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun QuickExecuteRow(
    workflow: Workflow,
    executionStateVersion: Int,
    onClick: () -> Unit,
) {
    val isRunning = remember(executionStateVersion, workflow.id) {
        WorkflowExecutor.isRunning(workflow.id)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(
                        if (isRunning) R.drawable.rounded_pause_24 else R.drawable.rounded_play_arrow_24
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Text(
            text = workflow.name,
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
