package com.blacklab.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.blacklab.app.MainActivity
import com.blacklab.app.R
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Profile
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.ui.camera.CameraScreen
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Shell
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.documents.DocumentEditorScreen
import com.blacklab.app.ui.documents.DocumentsScreen
import com.blacklab.app.ui.education.CourseDetailScreen
import com.blacklab.app.ui.education.DeckScreen
import com.blacklab.app.ui.education.EducationScreen
import com.blacklab.app.ui.education.ReviewScreen
import com.blacklab.app.ui.home.HomeScreen
import com.blacklab.app.ui.journal.JournalEditorScreen
import com.blacklab.app.ui.journal.JournalScreen
import com.blacklab.app.ui.library.LibraryScreen
import com.blacklab.app.ui.library.MediaViewerScreen
import com.blacklab.app.ui.onboarding.LockScreen
import com.blacklab.app.ui.onboarding.OnboardingScreen
import com.blacklab.app.ui.recorder.RecorderScreen
import com.blacklab.app.ui.recorder.RecordingDetailScreen
import com.blacklab.app.ui.schedule.EventEditorScreen
import com.blacklab.app.ui.schedule.ScheduleScreen
import com.blacklab.app.ui.settings.SettingsScreen
import com.blacklab.app.ui.tasks.TaskDetailScreen
import com.blacklab.app.ui.tasks.TasksScreen
import com.blacklab.app.ui.theme.profileAccent
import com.blacklab.app.ui.theme.Motion
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AppRoot(activity: MainActivity) {
    val settings = settingsState()
    val locked by MainActivity.locked.collectAsState()
    when {
        !settings.onboarded -> OnboardingScreen()
        locked -> LockScreen(activity)
        else -> AppShell(activity)
    }
}

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val bottomDests = listOf(
    Dest("home", "Home", Icons.Outlined.Home),
    Dest("tasks", "Tasks", Icons.Outlined.CheckCircle),
    Dest("schedule", "Schedule", Icons.Outlined.CalendarMonth),
    Dest("education", "Learn", Icons.Outlined.School),
    Dest("library", "Library", Icons.Outlined.FolderOpen),
)

private val drawerDests = listOf(
    Dest("home", "Home", Icons.Default.Dashboard),
    Dest("camera", "Camera", Icons.Default.PhotoCamera),
    Dest("recorder", "Recorder", Icons.Default.Mic),
    Dest("dictation", "Dictation", Icons.Default.RecordVoiceOver),
    Dest("library", "Library & Files", Icons.Default.Folder),
    Dest("documents", "Documents", Icons.Default.Description),
    Dest("journal", "Journal", Icons.AutoMirrored.Filled.MenuBook),
    Dest("tasks", "To-Do", Icons.Default.TaskAlt),
    Dest("schedule", "Schedule", Icons.Default.CalendarMonth),
    Dest("education", "Education", Icons.Default.School),
)

@Composable
private fun AppShell(activity: MainActivity) {
    val app = rememberApp()
    val settings = settingsState()
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val ctx = LocalContext.current

    val switchProfile: (Profile) -> Unit = { target ->
        if (target != settings.activeProfile) {
            if (target == Profile.PROFESSIONAL && settings.lockProfessional) {
                activity.authenticate("Unlock ${app.settings.profileName(target)}", "Confirm it's you") { ok -> if (ok) app.settings.setProfile(target) }
            } else app.settings.setProfile(target)
        }
    }
    val shell = remember(nav) {
        Shell(nav, openDrawer = { scope.launch { drawer.open() } }, snackbar = snackbar, scope = scope, switchProfile = { switchProfile(it) })
    }

    // Back closes the drawer before popping screens. The callback is (re)added each time the drawer
    // opens so it sits above the NavHost's own handler in the dispatcher's priority order.
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val drawerOpen = drawer.isOpen
    androidx.compose.runtime.DisposableEffect(drawerOpen, backDispatcher) {
        val callback = object : androidx.activity.OnBackPressedCallback(drawerOpen) {
            override fun handleOnBackPressed() { scope.launch { drawer.close() } }
        }
        if (drawerOpen) backDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route?.substringBefore('?')?.substringBefore('/') ?: "home"
    val showBottom = bottomDests.any { it.route == route }

    // Deep links from notifications
    val pending by MainActivity.pendingRoute.collectAsState()
    LaunchedEffect(pending) {
        val r = pending ?: return@LaunchedEffect
        MainActivity.pendingRoute.value = null
        if (drawer.isOpen) scope.launch { drawer.close() }
        when {
            r == "journal/new" -> nav.navigate("journal/0")
            else -> runCatching { nav.navigate(r) { launchSingleTop = true } }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalShell provides shell) {
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = drawer.isOpen || route == "home",
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {

                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                        Row(Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(52.dp))
                            Column {
                                Text("BlackLab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text(listOf(settings.userName, settings.jobTitle).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Personal & professional workspace" },
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("ACCOUNT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                            Profile.entries.forEach { p ->
                                val sel = p == settings.activeProfile
                                val c = profileAccent(p)
                                Surface(
                                    onClick = { switchProfile(p); scope.launch { drawer.close() } },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (sel) c.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) c else MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Icon(if (p == Profile.PERSONAL) Icons.Default.Person else Icons.Default.Work, null, tint = c)
                                        Spacer(Modifier.height(6.dp))
                                        Text(app.settings.profileName(p), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                        Text(if (sel) "Active" else "Switch", style = MaterialTheme.typography.labelSmall, color = if (sel) c else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(8.dp))
                        drawerDests.forEach { d ->
                            NavigationDrawerItem(
                                label = { Text(d.label) },
                                icon = { Icon(d.icon, null) },
                                selected = route == d.route,
                                onClick = {
                                    scope.launch { drawer.close() }
                                    nav.navigate(d.route) { popUpTo("home"); launchSingleTop = true }
                                },
                                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        NavigationDrawerItem(
                            label = { Text("Settings") }, icon = { Icon(Icons.Default.Settings, null) }, selected = route == "settings",
                            onClick = { scope.launch { drawer.close() }; nav.navigate("settings") { launchSingleTop = true } },
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
                        )
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FolderSpecial, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (app.storage.isPublic) "Internal storage › BlackLab" else "App storage (grant All-files access in Settings)",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    AnimatedVisibility(showBottom, enter = slideInVertically(Motion.enter()) { it } + fadeIn(Motion.enter()), exit = slideOutVertically(Motion.exit()) { it } + fadeOut(Motion.exit())) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                            bottomDests.forEach { d ->
                                NavigationBarItem(
                                    selected = route == d.route,
                                    onClick = { nav.navigate(d.route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } },
                                    icon = { Icon(d.icon, d.label) },
                                    label = { Text(d.label) },
                                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                )
                            }
                        }
                    }
                },
                contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            ) { pad ->
                Box(Modifier.fillMaxSize().padding(bottom = if (showBottom) pad.calculateBottomPadding() else 0.dp).background(MaterialTheme.colorScheme.background)) {
                    NavGraph(nav)
                }
            }
        }
        ShareIntake()
    }
}

@Composable
private fun NavGraph(nav: androidx.navigation.NavHostController) {
    NavHost(
        nav, startDestination = "home",
        enterTransition = { fadeIn(Motion.enter(320)) + androidx.compose.animation.slideInHorizontally(Motion.enter()) { it / 12 } },
        exitTransition = { fadeOut(Motion.exit(200)) + androidx.compose.animation.slideOutHorizontally(Motion.enter()) { -it / 24 } },
        popEnterTransition = { fadeIn(Motion.enter(320)) + androidx.compose.animation.slideInHorizontally(Motion.enter()) { -it / 24 } },
        popExitTransition = { fadeOut(Motion.exit(200)) + androidx.compose.animation.slideOutHorizontally(Motion.enter()) { it / 12 } },
    ) {
        composable("home") { HomeScreen() }
        composable("camera?mode={mode}", arguments = listOf(navArgument("mode") { defaultValue = "photo" })) {
            CameraScreen(it.arguments?.getString("mode") ?: "photo")
        }
        composable("recorder") { RecorderScreen() }
        composable("dictation") { com.blacklab.app.ui.dictation.DictationScreen() }
        composable("dictation/session?path={path}", arguments = listOf(navArgument("path") { defaultValue = "" })) {
            com.blacklab.app.ui.dictation.DictationDetailScreen(Uri.decode(it.arguments?.getString("path") ?: ""))
        }
        composable("imgedit?path={path}&tab={tab}", arguments = listOf(navArgument("path") { defaultValue = "" }, navArgument("tab") { defaultValue = "draw" })) {
            com.blacklab.app.ui.editor.ImageEditorScreen(Uri.decode(it.arguments?.getString("path") ?: ""), it.arguments?.getString("tab") ?: "draw")
        }
        composable("recording/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            RecordingDetailScreen(it.arguments?.getLong("id") ?: 0)
        }
        composable("library?tab={tab}", arguments = listOf(navArgument("tab") { defaultValue = "PHOTOS" })) {
            LibraryScreen(it.arguments?.getString("tab") ?: "PHOTOS")
        }
        composable("media?path={path}", arguments = listOf(navArgument("path") { defaultValue = "" })) {
            MediaViewerScreen(Uri.decode(it.arguments?.getString("path") ?: ""))
        }
        composable("documents") { DocumentsScreen() }
        composable(
            "doc/{id}?template={template}&course={course}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("template") { defaultValue = "" },
                navArgument("course") { type = NavType.LongType; defaultValue = 0L },
            ),
        ) {
            DocumentEditorScreen(
                it.arguments?.getLong("id") ?: 0,
                it.arguments?.getString("template")?.ifBlank { null },
                it.arguments?.getLong("course")?.takeIf { c -> c > 0 },
            )
        }
        composable("journal") { JournalScreen() }
        composable(
            "journal/{id}?day={day}&template={template}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("day") { type = NavType.LongType; defaultValue = Long.MIN_VALUE },
                navArgument("template") { defaultValue = "" },
            ),
        ) {
            JournalEditorScreen(
                it.arguments?.getLong("id") ?: 0,
                it.arguments?.getLong("day")?.takeIf { d -> d != Long.MIN_VALUE },
                it.arguments?.getString("template")?.ifBlank { null },
            )
        }
        composable("tasks") { TasksScreen() }
        composable("task/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            TaskDetailScreen(it.arguments?.getLong("id") ?: 0)
        }
        composable("schedule") { ScheduleScreen() }
        composable(
            "event/{id}?start={start}&allDay={allDay}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("start") { type = NavType.LongType; defaultValue = 0L },
                navArgument("allDay") { type = NavType.BoolType; defaultValue = false },
            ),
        ) {
            EventEditorScreen(it.arguments?.getLong("id") ?: 0, it.arguments?.getLong("start")?.takeIf { s -> s > 0 }, it.arguments?.getBoolean("allDay") ?: false)
        }
        composable("education?tab={tab}", arguments = listOf(navArgument("tab") { defaultValue = "" })) {
            EducationScreen(it.arguments?.getString("tab")?.ifBlank { null })
        }
        composable("course/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            CourseDetailScreen(it.arguments?.getLong("id") ?: 0)
        }
        composable("deck/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            DeckScreen(it.arguments?.getLong("id") ?: 0)
        }
        composable("review/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            ReviewScreen(it.arguments?.getLong("id") ?: 0)
        }
        composable("settings") { SettingsScreen() }
    }
}

/** Handles files/text shared into BlackLab from other apps. */
@Composable
private fun ShareIntake() {
    val payload by MainActivity.pendingShare.collectAsState()
    val p = payload ?: return
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val profile = settingsState().activeProfile
    val dismiss = { MainActivity.pendingShare.value = null }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Save to BlackLab") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (p.uris.isNotEmpty()) "${p.uris.size} file(s) shared with BlackLab (${app.settings.profileName(profile)})."
                    else (p.text ?: "").take(200),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (p.uris.isNotEmpty()) {
                    Button(onClick = {
                        app.io {
                            p.uris.forEach { uri ->
                                val name = Files.displayName(ctx, uri)
                                val mime = ctx.contentResolver.getType(uri) ?: ""
                                val folder = when {
                                    mime.startsWith("image/") -> Folder.PHOTOS
                                    mime.startsWith("video/") -> Folder.VIDEOS
                                    mime.startsWith("audio/") -> Folder.RECORDINGS
                                    else -> Folder.IMPORTS
                                }
                                val dest = Files.unique(app.storage.dir(profile, folder), name.substringBeforeLast('.'), name.substringAfterLast('.', "bin"))
                                if (Files.copyFromUri(ctx, uri, dest)) app.storage.scan(dest)
                            }
                        }
                        shell.toast("Saved to BlackLab"); dismiss()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save files to BlackLab") }
                }
                if (!p.text.isNullOrBlank()) {
                    Button(onClick = {
                        app.io {
                            TaskOps.save(app, TaskEntity(profile = profile.name, title = (p.subject ?: p.text).take(140), notes = p.text))
                        }
                        shell.toast("Task created"); dismiss()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Create task") }
                    Button(onClick = {
                        val html = ContentStore.textToHtml(p.text)
                        app.io {
                            val title = p.subject ?: p.text.lineSequence().first().take(60)
                            val rel = app.content.writeDocument(profile, title, "", "", html)
                            val text = ContentStore.plainText(html)
                            app.db.documents().insert(com.blacklab.app.data.DocumentEntity(profile = profile.name, title = title, filePath = rel,
                                preview = ContentStore.preview(text), wordCount = ContentStore.wordCount(text)))
                        }
                        shell.toast("Document created"); dismiss()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Create document") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Suppress("unused")
private fun File.rel(root: File) = relativeTo(root).path
