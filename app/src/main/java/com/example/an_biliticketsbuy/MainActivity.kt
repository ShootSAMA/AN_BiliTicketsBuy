package com.example.an_biliticketsbuy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.an_biliticketsbuy.ui.screens.HomeScreen
import com.example.an_biliticketsbuy.ui.screens.PermissionGuideScreen
import com.example.an_biliticketsbuy.ui.screens.SettingsScreen
import com.example.an_biliticketsbuy.ui.theme.AnBiliTicketsBuyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnBiliTicketsBuyTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    Scaffold { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    onNavigateToPermissions = { navController.navigate("permissions") },
                    onNavigateToSettings = { navController.navigate("settings") }
                )
            }
            composable("permissions") {
                PermissionGuideScreen()
            }
            composable("settings") {
                SettingsScreen()
            }
        }
    }
}
