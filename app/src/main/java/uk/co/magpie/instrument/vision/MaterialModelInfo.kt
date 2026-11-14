package uk.co.magpie.instrument.vision

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Offline access to model attribution and the licenses redistributed with the APK. */
@Composable
fun MaterialModelInfo() {
    var open by remember { mutableStateOf(false) }
    var sections by remember { mutableStateOf<List<String>>(emptyList()) }
    val assets = LocalContext.current.applicationContext.assets
    if (open) {
        LaunchedEffect(Unit) {
            sections = withContext(Dispatchers.IO) {
                listOf("material-model-NOTICE.txt", "material-model-APACHE-2.0.txt", "onnxruntime-MIT.txt", "onnxruntime-ThirdPartyNotices.txt")
                    .flatMap { name ->
                        val text = runCatching { assets.open("licenses/$name").bufferedReader().use { it.readText() } }
                            .getOrElse { "Notice unavailable: $name" }
                        listOf(name) + text.chunked(2000)
                    }
            }
        }
        AlertDialog(onDismissRequest = { open = false }, title = { Text("Material model & licenses") },
            text = {
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    if (sections.isEmpty()) item { Text("Loading local notices…") }
                    items(sections) { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
    }
    TextButton(onClick = { open = true }) { Text("Material model & licenses") }
}
