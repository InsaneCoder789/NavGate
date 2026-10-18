package com.rohanc.navgate.ui.ar

import android.content.Context
import android.hardware.*
import android.os.SystemClock
import android.view.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.HeadingFilter
import kotlinx.coroutines.delay

/** Optional compass aid; missing/unreliable/stale sensors produce no alignment claim. */
@Composable
fun rememberHeadingState(location:GeoPoint?=null): MutableState<Double?> {
    val context=LocalContext.current
    val view=LocalView.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val currentLocation=rememberUpdatedState(location)
    val heading=remember { mutableStateOf<Double?>(null) }
    val filter=remember { HeadingFilter() }
    DisposableEffect(context,view,lifecycle) {
        val manager=context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor=manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val matrix=FloatArray(9);val remapped=FloatArray(9);val orientation=FloatArray(3)
        var registered=false
        var reliable=false
        var rotation:Int?=null
        fun clear(){filter.reset();heading.value=null}
        val listener=object:SensorEventListener {
            override fun onSensorChanged(event:SensorEvent) {
                if(!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)){clear();return}
                val point=currentLocation.value?.takeIf{it.valid} ?: run{clear();return}
                if(!reliable || event.accuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM){clear();return}
                SensorManager.getRotationMatrixFromVector(matrix,event.values)
                val displayRotation=view.display?.rotation ?: Surface.ROTATION_0
                if(rotation!=displayRotation){clear();rotation=displayRotation}
                val axes=when(displayRotation){
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                if(!SensorManager.remapCoordinateSystem(matrix,axes.first,axes.second,remapped)){clear();return}
                SensorManager.getOrientation(remapped,orientation)
                val declination=GeomagneticField(point.latitude.toFloat(),point.longitude.toFloat(),0f,System.currentTimeMillis()).declination
                heading.value=filter.sample(Math.toDegrees(orientation[0].toDouble()),declination.toDouble(),event.timestamp/1_000_000,true)
            }
            override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){
                reliable=accuracy>=SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                if(!reliable) clear()
            }
        }
        fun update(){
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)){
                if(!registered && sensor!=null) registered=manager.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_UI)
            } else {manager.unregisterListener(listener);registered=false;reliable=false;clear()}
        }
        val observer=LifecycleEventObserver{_,_->update()}
        lifecycle.addObserver(observer);update()
        onDispose{lifecycle.removeObserver(observer);manager.unregisterListener(listener);clear()}
    }
    LaunchedEffect(filter){while(true){delay(500);heading.value=filter.current(SystemClock.elapsedRealtime())}}
    return heading
}
