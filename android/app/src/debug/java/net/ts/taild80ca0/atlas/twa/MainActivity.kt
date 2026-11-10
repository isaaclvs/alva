package net.ts.taild80ca0.atlas.twa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Native development entry point.
 *
 * It only exists in the debug variant (see `src/debug`) and is intentionally
 * empty: the offline client is built in the following issues. The production
 * launcher remains the Trusted Web Activity until the explicit transition.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DevelopmentHome()
                }
            }
        }
    }
}

@Composable
private fun DevelopmentHome(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.appName),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DevelopmentHomePreview() {
    MaterialTheme {
        Surface { DevelopmentHome() }
    }
}
