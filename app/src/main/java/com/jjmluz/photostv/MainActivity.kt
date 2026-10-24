package com.jjmluz.photostv

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.jjmluz.photostv.ui.HomeScreen
import com.jjmluz.photostv.ui.LoadingScreen
import com.jjmluz.photostv.ui.MessageScreen
import com.jjmluz.photostv.ui.SignInScreen
import com.jjmluz.photostv.ui.ViewerScreen
import com.jjmluz.photostv.ui.theme.PhotosTvTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    // Resources (including the video player's controls) follow the app language, not the TV's.
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply { setLocale(AppLocale) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotosTvTheme {
                // Root surface supplies background and default content color to everything below.
                Surface(
                    Modifier.fillMaxSize(),
                    colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                ) {
                    when (val auth = vm.auth) {
                        AuthState.Checking -> LoadingScreen()
                        AuthState.SignedOut -> SignInScreen(onImported = vm::connect)
                        is AuthState.Failed -> {
                            MessageScreen(
                                title = "Não foi possível aceder ao Google Fotos",
                                message = (auth.error.message ?: auth.error.toString()) +
                                    "\n\nPrima Voltar para iniciar sessão novamente.",
                                action = "Tentar novamente",
                                onAction = vm::connect,
                            )
                            BackHandler(onBack = vm::signOut)
                        }
                        is AuthState.SignedIn -> {
                            val viewer = vm.viewer
                            if (viewer != null) {
                                ViewerScreen(viewer, locationOf = vm::locationOf, onClose = vm::closeViewer)
                            } else {
                                HomeScreen(vm)
                                BackHandler { if (!vm.back()) finish() }
                            }
                        }
                    }
                }
            }
        }
    }
}
