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
import androidx.compose.ui.res.stringResource
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

    // Resources (including the video player's controls) follow the app's language, which is English
    // when the TV's isn't one the app is translated into.
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply { setLocale(pickAppLocale(this)) }
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
                        AuthState.SignedOut -> SignInScreen()
                        is AuthState.Failed -> {
                            MessageScreen(
                                title = stringResource(R.string.auth_failed),
                                message = (auth.error.message ?: auth.error.toString()) +
                                    "\n\n" + stringResource(R.string.auth_failed_back),
                                action = stringResource(R.string.retry),
                                onAction = vm::connect,
                            )
                            BackHandler(onBack = vm::signOut)
                        }
                        is AuthState.SignedIn -> {
                            val viewer = vm.viewer
                            if (vm.addingAccount) {
                                SignInScreen(adding = true)
                                BackHandler(onBack = vm::cancelAddAccount)
                            } else if (viewer != null) {
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
