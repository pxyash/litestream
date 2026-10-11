@file:OptIn(ExperimentalMaterial3Api::class)

package app.litestream.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import app.litestream.AppViewModel

@Composable
fun App(vm: AppViewModel) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val showRail = route == "home" || route == "search" || route == "settings"
    val tabs = listOf(
        Triple("home", "Home", Icons.Default.Home),
        Triple("search", "Search", Icons.Default.Search),
        Triple("settings", "Settings", Icons.Default.Settings)
    )

    fun go(r: String) = nav.navigate(r) {
        popUpTo("home") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    fun openDetail(m: app.litestream.data.Meta) =
        nav.navigate("detail/${m.type}/${Uri.encode(m.id)}")

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (showRail) {
            NavigationRail {
                Spacer(Modifier.weight(1f))
                tabs.forEach { (r, label, icon) ->
                    NavigationRailItem(
                        selected = route == r,
                        onClick = { go(r) },
                        icon = { Icon(icon, label) },
                        label = { Text(label) }
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
        NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
            composable("home") { HomeScreen(vm, ::openDetail) }
            composable("search") { SearchScreen(vm, ::openDetail) }
            composable("settings") { SettingsScreen(vm) }
            composable("detail/{type}/{id}") { e ->
                DetailScreen(
                    vm,
                    e.arguments?.getString("type") ?: "movie",
                    e.arguments?.getString("id") ?: ""
                ) { type, id, title ->
                    nav.navigate("streams/$type/${Uri.encode(id)}?title=${Uri.encode(title)}")
                }
            }
            composable(
                "streams/{type}/{id}?title={title}",
                arguments = listOf(navArgument("title") { type = NavType.StringType; defaultValue = "" })
            ) { e ->
                StreamsScreen(
                    vm,
                    e.arguments?.getString("type") ?: "movie",
                    e.arguments?.getString("id") ?: "",
                    e.arguments?.getString("title") ?: "",
                    onPlay = { nav.navigate("player") }
                )
            }
            composable("player") { PlayerScreen(vm) { nav.popBackStack() } }
        }
    }
}
