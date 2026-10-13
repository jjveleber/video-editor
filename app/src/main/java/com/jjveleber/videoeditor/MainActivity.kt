package com.jjveleber.videoeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jjveleber.videoeditor.ui.editor.EditorScreen
import com.jjveleber.videoeditor.ui.editor.EditorViewModel
import com.jjveleber.videoeditor.ui.home.HomeScreen
import com.jjveleber.videoeditor.ui.home.HomeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as VideoEditorApp).container
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "home") {
                        composable("home") {
                            val vm: HomeViewModel = viewModel(
                                factory = viewModelFactory {
                                    initializer { HomeViewModel(container.repository, container.grants) }
                                },
                            )
                            HomeScreen(vm, onOpen = { id -> nav.navigate("editor/$id") })
                        }
                        composable("editor/{id}") { entry ->
                            val id = entry.arguments?.getString("id")!!
                            val vm: EditorViewModel = viewModel(
                                factory = viewModelFactory {
                                    initializer {
                                        EditorViewModel(id, container.repository, container.prober, container.grants)
                                    }
                                },
                            )
                            EditorScreen(vm, container.thumbnails, onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
