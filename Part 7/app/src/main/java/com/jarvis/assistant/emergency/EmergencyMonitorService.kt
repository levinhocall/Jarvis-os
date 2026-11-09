package com.jarvis.assistant.emergency

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.*
import android.net.Uri
import android.os.*
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class EmergencyMonitorService:Service(),SensorEventListener {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val detector=FallCandidateDetector()
    private lateinit var sensors:SensorManager
    private var countdown:Job?=null
    private var contact=""
    private var lastAlertAt=-300000L
    override fun onCreate() {
        super.onCreate();sensors=getSystemService(SENSOR_SERVICE) as SensorManager
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL,"JARVIS emergency monitor",NotificationManager.IMPORTANCE_HIGH))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP) { stopSelf();return START_NOT_STICKY }
        if(intent?.action==CANCEL) {countdown?.cancel();countdown=null;detector.reset();(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID+1);if(contact.isBlank()) stopSelf() else update("Alert cancelled. Monitoring continues.");return START_NOT_STICKY}
        if(Build.VERSION.SDK_INT>=34) startForeground(ID,notification("Preparing emergency monitor"),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID,notification("Preparing emergency monitor"))
        scope.launch {
            try {
                val config=EmergencySettings.load(this@EmergencyMonitorService)
                if(!config.enabled || !notificationsAvailable() || !validContact(config.phone) || ContextCompat.checkSelfPermission(this@EmergencyMonitorService,android.Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED) {
                    statusMutable.value="Monitoring off: configure a trusted contact and call permission.";stopSelf();return@launch
                }
                contact=config.phone
                if(intent?.action==SOS) {beginCountdown(manual=true);return@launch}
                val sensor=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER,true) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                check(sensor!=null && sensors.registerListener(this@EmergencyMonitorService,sensor,SensorManager.SENSOR_DELAY_GAME,200000)) {"Motion sensor unavailable"}
                update(if(sensor.isWakeUpSensor) "Possible-fall monitoring active" else "Monitoring active; screen-off delivery depends on this device")
            } catch(_:Exception) {statusMutable.value="Emergency monitor unavailable. Open Emergency Contact to retry.";stopSelf()}
        }
        return START_NOT_STICKY
    }
    override fun onSensorChanged(event:SensorEvent) {
        val a=event.values;val magnitude=kotlin.math.sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2])
        if(detector.sample(event.timestamp/1_000_000,magnitude)) beginCountdown()
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) = Unit
    private fun beginCountdown(manual:Boolean=false) {
        if(countdown?.isActive==true)return
        val now=SystemClock.elapsedRealtime()
        if(!manual && now-lastAlertAt<300000L)return
        lastAlertAt=now
        countdown=scope.launch {
            for(seconds in 30 downTo 1) {
                if(!notificationsAvailable()) {(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID+1);update("Countdown stopped: notifications must be enabled");return@launch}
                val message="Possible fall: calling trusted contact in $seconds s. Tap I'm OK to cancel."
                statusMutable.value=message
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID+1,notification(message))
                delay(1000)
            }
            try {
                val latest=EmergencySettings.load(this@EmergencyMonitorService)
                check(latest.enabled && latest.phone==contact && validContact(contact) && notificationsAvailable())
                check(ContextCompat.checkSelfPermission(this@EmergencyMonitorService,android.Manifest.permission.CALL_PHONE)==PackageManager.PERMISSION_GRANTED)
                currentCoroutineContext().ensureActive()
                (getSystemService(TELECOM_SERVICE) as TelecomManager).placeCall(Uri.fromParts("tel",contact,null),Bundle())
                update("Call requested through Android; connection is not independently verified")
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {update("Call could not be placed. Open Phone and call your trusted contact.")}
            finally {(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID+1)}
        }
    }
    private fun notification(text:String):Notification {
        fun action(action:String)=PendingIntent.getService(this,action.hashCode(),Intent(this,EmergencyMonitorService::class.java).setAction(action),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(this,302,Intent(this,EmergencyContactActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(com.jarvis.assistant.R.drawable.ic_settings_apps).setContentTitle("JARVIS emergency monitor").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).addAction(0,"I'm OK · cancel",action(CANCEL)).addAction(0,"Stop monitoring",action(STOP)).build()
    }
    private fun update(text:String) {statusMutable.value=text;(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID,notification(text))}
    private fun notificationsAvailable():Boolean {
        val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    override fun onDestroy() {sensors.unregisterListener(this);scope.cancel();(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID+1);statusMutable.value="Emergency monitoring stopped";super.onDestroy()}
    override fun onBind(intent:Intent?) = null
    companion object {
        private const val ID=7301;private const val CHANNEL="jarvis_emergency_monitor"
        const val STOP="jarvis.emergency.STOP";const val CANCEL="jarvis.emergency.CANCEL";const val SOS="jarvis.emergency.SOS"
        private val statusMutable=MutableStateFlow("Emergency monitoring off")
        val status=statusMutable.asStateFlow()
        fun validContact(phone:String):Boolean = runCatching {
            phone.matches(Regex("\\+?[0-9]{5,15}")) && !PhoneNumberUtils.isEmergencyNumber(phone)
        }.getOrDefault(false)
    }
}
