package com.umavpn.checker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.umavpn.checker.ui.UmaVpnRootApp
import com.umavpn.checker.ui.theme.UmaVpnCheckerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UmaVpnCheckerTheme {
                UmaVpnRootApp()
            }
        }
    }
}
