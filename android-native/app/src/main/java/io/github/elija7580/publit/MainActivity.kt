package io.github.elija7580.publit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.elija7580.publit.ui.NearbyViewModel
import io.github.elija7580.publit.ui.PublitApp

class MainActivity : ComponentActivity() {
    private val nearby: NearbyViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as PublitApplication
                return NearbyViewModel(app.client, app.location, app.crashLog, getSharedPreferences("publit", MODE_PRIVATE)) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PublitApplication
        val crash = if (savedInstanceState == null) app.crashLog.take() else null
        setContent { PublitApp(nearby, crash) }
    }
}
