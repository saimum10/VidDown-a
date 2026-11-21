package com.viddown.app.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.viddown.app.R
import com.viddown.app.download.StorageAccess
import com.viddown.app.ui.UiTags

/** Whether the app may write to the VidDown folder in the main storage, and how to ask for it. Re-checked every time the app is resumed. */
class StorageAccessState(val granted: Boolean, val request: () -> Unit)

@Composable
fun rememberStorageAccess(): StorageAccessState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(StorageAccess.hasRootAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = StorageAccess.hasRootAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = StorageAccess.hasRootAccess(context)
    }
    val request: () -> Unit = {
        if (StorageAccess.usesSystemSettings()) {
            try {
                context.startActivity(StorageAccess.settingsIntent(context))
            } catch (e: ActivityNotFoundException) {
                try {
                    context.startActivity(StorageAccess.settingsFallbackIntent())
                } catch (e2: ActivityNotFoundException) {
                    // no Settings screen to open: the app keeps saving to Download/VidDown
                }
            }
        } else {
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    return StorageAccessState(granted, request)
}

/** Asked once per start of the app, only while no folder was chosen and the access is missing. */
@Composable
fun StorageAccessDialog(access: StorageAccessState, needed: Boolean) {
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (!needed || access.granted || dismissed) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text(stringResource(R.string.storage_access_title)) },
        text = { Text(stringResource(R.string.storage_access_message)) },
        confirmButton = {
            TextButton(
                onClick = {
                    dismissed = true
                    access.request()
                },
                modifier = Modifier.testTag(UiTags.STORAGE_ACCESS_ALLOW),
            ) { Text(stringResource(R.string.storage_access_allow)) }
        },
        dismissButton = {
            TextButton(onClick = { dismissed = true }, modifier = Modifier.testTag(UiTags.STORAGE_ACCESS_LATER)) {
                Text(stringResource(R.string.storage_access_later))
            }
        },
    )
}
