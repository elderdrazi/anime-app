package com.kumo.anime.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kumo.anime.Graph
import com.kumo.anime.MainActivity
import com.kumo.anime.data.Session
import com.kumo.anime.ui.screens.AnimeDetailScreen
import com.kumo.anime.ui.screens.DiscoverScreen
import com.kumo.anime.ui.screens.FriendsScreen
import com.kumo.anime.ui.screens.InboxScreen
import com.kumo.anime.ui.screens.LibraryScreen
import com.kumo.anime.ui.screens.ProfileScreen
import com.kumo.anime.ui.theme.Accent
import com.kumo.anime.ui.theme.Bg
import com.kumo.anime.ui.theme.Hairline
import com.kumo.anime.ui.theme.TextDim
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun KumoRoot(pending: StateFlow<Intent?>, onHandled: () -> Unit) {
    val session by Graph.social.session.collectAsStateWithLifecycle()
    when (val s = session) {
        Session.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("kumo", style = MaterialTheme.typography.displaySmall, color = Accent)
        }
        Session.NeedsUsername -> UsernameScreen()
        is Session.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState("Can't connect", s.message) {
                Button(onClick = { Graph.social.start() }) { Text("Try again") }
            }
        }
        Session.Disabled, is Session.Ready -> MainScaffold(pending, onHandled)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("discover", "Discover", Icons.Outlined.Explore),
    Tab("library", "My list", Icons.Outlined.Bookmarks),
    Tab("friends", "Friends", Icons.Outlined.People),
    Tab("inbox", "Inbox", Icons.Outlined.Notifications),
)

@Composable
private fun MainScaffold(pending: StateFlow<Intent?>, onHandled: () -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val social = Graph.social
    val notices by social.notices.collectAsStateWithLifecycle()
    val unread = notices.count { !it.read }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = tabs.any { it.route == route }

    // Ask for notification permission once the account exists (Android 13+).
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(social.enabled) {
        if (social.enabled && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Deep links (kumo://u/name) and notification taps.
    val intent by pending.collectAsStateWithLifecycle()
    LaunchedEffect(intent) {
        val i = intent ?: return@LaunchedEffect
        val data = i.data
        if (i.getStringExtra(MainActivity.EXTRA_OPEN) == MainActivity.OPEN_INBOX) {
            nav.goTab("inbox")
        } else if (data?.scheme == "kumo" && data.host == "u" && social.enabled) {
            val name = data.lastPathSegment
            val profile = name?.let { runCatching { social.findUser(it) }.getOrNull() }
            if (profile != null) nav.navigate("user/${profile.uid}") else snackbar.showSnackbar("Couldn't find @$name")
        }
        onHandled()
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            containerColor = Bg,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (showBar) {
                    Column {
                        HorizontalDivider(color = Hairline, thickness = 0.5.dp)
                        NavigationBar(containerColor = Bg, tonalElevation = 0.dp) {
                            tabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = route == tab.route,
                                    onClick = { nav.goTab(tab.route) },
                                    icon = {
                                        if (tab.route == "inbox" && unread > 0) {
                                            BadgedBox(badge = { Badge(containerColor = Accent) { Text("$unread") } }) {
                                                Icon(tab.icon, tab.label)
                                            }
                                        } else {
                                            Icon(tab.icon, tab.label)
                                        }
                                    },
                                    label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Accent,
                                        selectedTextColor = Accent,
                                        unselectedIconColor = TextDim,
                                        unselectedTextColor = TextDim,
                                        indicatorColor = Bg,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { inner ->
            NavHost(nav, startDestination = "discover", modifier = Modifier.padding(inner)) {
                val openAnime: (Int) -> Unit = { nav.navigate("anime/$it") }
                val openUser: (String) -> Unit = { nav.navigate("user/$it") }
                composable("discover") { DiscoverScreen(onOpen = openAnime) }
                composable("library") { LibraryScreen(onOpen = openAnime) }
                composable("friends") { FriendsScreen(onOpenUser = openUser) }
                composable("inbox") { InboxScreen(onOpen = openAnime, onOpenUser = openUser) }
                composable("anime/{id}", listOf(navArgument("id") { type = NavType.IntType })) {
                    AnimeDetailScreen(
                        id = it.arguments!!.getInt("id"),
                        onBack = { nav.popBackStack() },
                        onOpenUser = openUser,
                    )
                }
                composable("user/{uid}") {
                    ProfileScreen(
                        uid = it.arguments!!.getString("uid")!!,
                        onBack = { nav.popBackStack() },
                        onOpen = openAnime,
                    )
                }
            }
        }
    }
}

private fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun UsernameScreen() {
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("kumo", style = MaterialTheme.typography.displaySmall, color = Accent)
        Spacer(Modifier.height(8.dp))
        Text(
            "Share what you've watched, and pick what's next together.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextDim,
        )
        Spacer(Modifier.height(40.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text("Username") },
            prefix = { Text("@") },
            singleLine = true,
            isError = error != null,
            supportingText = { Text(error ?: "This is how friends find you") },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Hairline),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (busy) return@Button
                busy = true
                scope.launch {
                    try {
                        Graph.social.claimUsername(name)
                    } catch (e: Exception) {
                        error = e.message ?: "Something went wrong. Try again."
                    }
                    busy = false
                }
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("Continue")
        }
    }
}
