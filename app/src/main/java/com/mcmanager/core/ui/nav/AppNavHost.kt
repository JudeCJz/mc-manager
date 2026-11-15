package com.mcmanager.core.ui.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mcmanager.R
import com.mcmanager.feature.backups.BackupsScreen
import com.mcmanager.feature.config.PropertiesScreen
import com.mcmanager.feature.config.YamlConfigScreen
import com.mcmanager.feature.console.ConsoleScreen
import com.mcmanager.feature.create.CreateServerScreen
import com.mcmanager.feature.dashboard.DashboardScreen
import com.mcmanager.feature.files.FilesScreen
import com.mcmanager.feature.files.TextEditorScreen
import com.mcmanager.feature.logs.LogsScreen
import com.mcmanager.feature.network.NetworkScreen
import com.mcmanager.feature.players.PlayersScreen
import com.mcmanager.feature.plugins.PluginsScreen
import com.mcmanager.feature.server.ServerHubScreen
import com.mcmanager.feature.server.ServerSettingsScreen
import com.mcmanager.feature.settings.SettingsScreen
import com.mcmanager.feature.versions.VersionsScreen

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val tabs = listOf(
  Tab(Routes.DASHBOARD, R.string.nav_servers, Icons.Filled.Dns),
  Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

private val idArg = navArgument(Routes.ARG_ID) { type = NavType.LongType }

private fun NavBackStackEntry.serverId(): Long = arguments?.getLong(Routes.ARG_ID) ?: 0L

private fun NavBackStackEntry.stringArg(key: String): String = arguments?.getString(key).orEmpty()

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
  val backStack by navController.currentBackStackEntryAsState()
  val destination = backStack?.destination
  val showBar = tabs.any { tab -> destination?.hierarchy?.any { it.route == tab.route } == true }
  val back: () -> Unit = { navController.popBackStack() }
  val go: (String) -> Unit = { navController.navigate(it) }

  Scaffold(
    contentWindowInsets = WindowInsets(0),
    bottomBar = {
      if (showBar) {
        NavigationBar {
          tabs.forEach { tab ->
            NavigationBarItem(
              selected = destination?.hierarchy?.any { it.route == tab.route } == true,
              onClick = {
                navController.navigate(tab.route) {
                  popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                  launchSingleTop = true
                  restoreState = true
                }
              },
              icon = { Icon(tab.icon, contentDescription = null) },
              label = { Text(stringResource(tab.labelRes)) },
            )
          }
        }
      }
    },
  ) { inner ->
    Box(Modifier.fillMaxSize().padding(bottom = inner.calculateBottomPadding())) {
      NavHost(navController = navController, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) {
          DashboardScreen(
            onOpenServer = { go(Routes.server(it)) },
            onCreate = { go(Routes.CREATE) },
          )
        }
        composable(Routes.SETTINGS) { SettingsScreen() }
        composable(Routes.CREATE) {
          CreateServerScreen(
            onDone = { id ->
              navController.navigate(Routes.server(id)) { popUpTo(Routes.CREATE) { inclusive = true } }
            },
            onBack = back,
          )
        }
        composable(Routes.SERVER, listOf(idArg)) {
          ServerHubScreen(it.serverId(), navigate = go, onBack = back)
        }
        composable(Routes.CONSOLE, listOf(idArg)) { ConsoleScreen(it.serverId(), back) }
        composable(Routes.LOGS, listOf(idArg)) { LogsScreen(it.serverId(), back) }
        composable(
          Routes.FILES,
          listOf(idArg, navArgument(Routes.ARG_PATH) { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
          val id = entry.serverId()
          FilesScreen(
            serverId = id,
            startPath = entry.stringArg(Routes.ARG_PATH),
            onBack = back,
            onEditFile = { go(Routes.edit(id, it)) },
          )
        }
        composable(
          Routes.EDIT,
          listOf(idArg, navArgument(Routes.ARG_PATH) { type = NavType.StringType; defaultValue = "" }),
        ) { TextEditorScreen(it.serverId(), it.stringArg(Routes.ARG_PATH), back) }
        composable(Routes.PROPERTIES, listOf(idArg)) { PropertiesScreen(it.serverId(), back) }
        composable(
          Routes.YAML,
          listOf(idArg, navArgument(Routes.ARG_FILE) { type = NavType.StringType; defaultValue = "" }),
        ) { YamlConfigScreen(it.serverId(), it.stringArg(Routes.ARG_FILE), back) }
        composable(Routes.PLAYERS, listOf(idArg)) { PlayersScreen(it.serverId(), back) }
        composable(Routes.PLUGINS, listOf(idArg)) { PluginsScreen(it.serverId(), back) }
        composable(Routes.VERSIONS, listOf(idArg)) { VersionsScreen(it.serverId(), back) }
        composable(Routes.BACKUPS, listOf(idArg)) { BackupsScreen(it.serverId(), back) }
        composable(Routes.NETWORK, listOf(idArg)) { NetworkScreen(it.serverId(), back) }
        composable(Routes.SERVER_SETTINGS, listOf(idArg)) { ServerSettingsScreen(it.serverId(), back) }
      }
    }
  }
}
