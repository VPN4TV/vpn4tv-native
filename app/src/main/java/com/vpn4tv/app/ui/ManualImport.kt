package com.vpn4tv.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vpn4tv.app.Application
import com.vpn4tv.app.R
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.converter.ProxyParser
import com.vpn4tv.app.lan.ProfileImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the user brings their own key on the current screen shape. */
enum class KeyImport {
    /** The paste dialog — the desktop's Manual Import — in Settings and behind "+" on Home. */
    Paste,

    /** The page served over the local network, in Settings only, as before. */
    LocalNetwork,
}

/**
 * Portrait, a phone held upright: paste. Anything else — a TV, a tablet or a
 * phone on its side, a shape the system does not report — keeps the import
 * over the local network.
 */
internal fun keyImportFor(orientation: Int): KeyImport =
    if (orientation == Configuration.ORIENTATION_PORTRAIT) KeyImport.Paste else KeyImport.LocalNetwork

/** Read from the composition's configuration, so turning the phone switches it at once. */
@Composable
fun currentKeyImport(): KeyImport = keyImportFor(LocalConfiguration.current.orientation)

/**
 * The paste dialog's state and its import. Scoped to the dialog's back stack
 * entry: turning the phone keeps the text, a running import and its result;
 * closing the dialog drops them. The text is not saved instance state on
 * purpose — a pasted subscription body can outgrow what a Bundle carries.
 */
class ManualImportViewModel : ViewModel() {
    sealed interface State {
        data object Editing : State
        data object Importing : State
        data class Failed(val message: String?) : State
        data class Imported(val name: String) : State
    }

    var text by mutableStateOf("")
        private set
    var state by mutableStateOf<State>(State.Editing)
        private set

    fun edit(value: String) {
        text = value
        if (state is State.Failed) state = State.Editing
    }

    fun submit() {
        if (state == State.Importing || state is State.Imported) return
        val pasted = text
        state = State.Importing
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                // vpn4tv:// wraps whatever the user would otherwise paste. Unwrap
                // it first, as a link opened from outside is (MainActivity), so a
                // subscription inside stays a subscription that keeps updating.
                val raw = ProxyParser.decodeVpn4tvLink(pasted) ?: pasted
                // The importer of the LAN page: subscription URLs, server links,
                // configs; it selects the new profile.
                ProfileImporter.importFromText(Application.application, raw).onSuccess {
                    // A running service switches to it the way it takes any
                    // change the config is built from: the command server's reload.
                    BoxService.reloadIfRunning()
                }
            }
            state = result.fold({ State.Imported(it) }, { State.Failed(it.message) })
        }
    }
}

/**
 * Manual import, as on the desktop: paste a subscription link, server links or
 * a config, then Import. On success it says "Added: <name>" for a moment, as
 * the LAN import does, and [onImported] takes the user to Home.
 */
@Composable
fun ManualImportDialog(onDismiss: () -> Unit, onImported: () -> Unit) {
    val model = viewModel { ManualImportViewModel() }
    val state = model.state
    val imported by rememberUpdatedState(onImported)

    LaunchedEffect(state) {
        if (state is ManualImportViewModel.State.Imported) {
            delay(1200)
            imported()
        }
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            if (state is ManualImportViewModel.State.Imported) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("✓", fontSize = 48.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.lan_import_added, state.name),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                ManualImportForm(model, state, onDismiss)
            }
        }
    }
}

@Composable
private fun ManualImportForm(
    model: ManualImportViewModel,
    state: ManualImportViewModel.State,
    onDismiss: () -> Unit,
) {
    val busy = state == ManualImportViewModel.State.Importing
    val focus = remember { FocusRequester() }
    // Focused at once, so the keyboard and its clipboard suggestion are up.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Text(
        stringResource(R.string.manual_import_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        stringResource(R.string.manual_import_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedTextField(
        value = model.text,
        onValueChange = model::edit,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        enabled = !busy,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        label = { Text(stringResource(R.string.manual_import_field)) },
        placeholder = { Text("vless://…") },
        isError = state is ManualImportViewModel.State.Failed,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
        ),
        minLines = 4,
        maxLines = 8,
    )
    if (state is ManualImportViewModel.State.Failed) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            state.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.add_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (busy) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.add_adding),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.cancel))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = model::submit,
            enabled = !busy && model.text.isNotBlank(),
        ) {
            Text(stringResource(R.string.import_action))
        }
    }
}
