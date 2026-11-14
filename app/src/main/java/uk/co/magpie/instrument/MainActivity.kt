package uk.co.magpie.instrument

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var manager: SensorManager
    private lateinit var model: InstrumentModel
    private var sensor: Sensor? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        model = ViewModelProvider(this)[InstrumentModel::class.java]
        manager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensor = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        setContent { InstrumentScreen(model, sensor) }
    }
    override fun onResume() {
        super.onResume()
        model.resume()
        model.accuracy = -1
        model.active = sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } ?: false
    }
    override fun onPause() {
        manager.unregisterListener(this)
        model.pause()
        super.onPause()
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (!model.active || event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD || event.values.size < 3) return
        model.accuracy = event.accuracy
        model.accept(Field(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble()), SystemClock.elapsedRealtimeNanos() / 1e9)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { model.accuracy = accuracy }
}
