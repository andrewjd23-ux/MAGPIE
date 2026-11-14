package uk.co.magpie.instrument

import android.hardware.Sensor
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import uk.co.magpie.instrument.vision.VisionScreen
import uk.co.magpie.instrument.vision.TargetAcquisitionModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun MagpieApp(model: InstrumentModel, sensor: Sensor?) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val targets: TargetAcquisitionModel = viewModel()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(model, targets, owner) {
        val subscription = model.observeSamples(targets::onMagneticSample)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) targets.interruptScan("Activity paused; restart the controlled scan")
        }
        owner.lifecycle.addObserver(observer)
        onDispose { subscription.close(); owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(tab) {
        if (tab != 1) targets.interruptScan("Left Vision; restart the controlled scan")
    }
    // Both screens share freshness and the existing instrument state.
    LaunchedEffect(model.active) {
        while (model.active) {
            val now = SystemClock.elapsedRealtimeNanos() / 1e9
            model.tick(now)
            targets.tick(now)
            delay(250)
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFA6C6A0), secondary = Color(0xFFD4AB62),
        background = Color(0xFF0E1512), surface = Color(0xFF19231F)
    )) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Instrument") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("MAGPIE Vision") })
                }
                Box(Modifier.weight(1f)) {
                    if (tab == 0) InstrumentScreen(model, sensor) else VisionScreen(model, targets)
                }
            }
        }
    }
}
