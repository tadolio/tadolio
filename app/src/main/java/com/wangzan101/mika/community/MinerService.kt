// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import android.app.*
import android.content.*
import android.net.*
import android.os.BatteryManager
import android.os.IBinder
import com.wangzan101.mika.R
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** User-enabled foreground work, restored after boot when requested, with a persistent stop action. */
class MinerService: Service() {
    private lateinit var store: MinerStore
    private lateinit var worker: MinerWorker
    private val jobs=Executors.newSingleThreadScheduledExecutor()
    private val heart=Executors.newSingleThreadScheduledExecutor()
    private var started=false
    private val conditions=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){changed()}}
    private val network=object:ConnectivityManager.NetworkCallback(){
        override fun onLost(n:Network){changed()}
        override fun onCapabilitiesChanged(n:Network,c:NetworkCapabilities){changed()}
    }
    private fun allowed():Boolean = runCatching {
        store.refreshKeyState()
        val cfg=store.read();val cm=getSystemService(ConnectivityManager::class.java)
        val caps=cm.getNetworkCapabilities(cm.activeNetwork)
        val battery=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged=(battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)?:0)!=0
        val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1
        val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100
        store.pauseReason = MinerState.pause(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true,
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true,plugged,if(scale>0)level*100/scale else -1,
            cfg["wifi"]?.jsonPrimitive?.booleanOrNull?:MinerPolicy.DEFAULT_WIFI_ONLY,cfg["charging"]?.jsonPrimitive?.booleanOrNull?:MinerPolicy.DEFAULT_CHARGING_ONLY,
            store.used(),cfg.getValue("limit").jsonPrimitive.long,
            cfg["minBattery"]?.jsonPrimitive?.intOrNull?:MinerPolicy.DEFAULT_MIN_BATTERY)
        store.enabled && store.pauseReason == null && store.phase != MinerState.KEY_BAD
    }.getOrDefault(false)
    private fun changed(){if(::worker.isInitialized&&!allowed()){worker.cancel();heart.execute{runCatching{worker.heartbeat()}}}}
    override fun onCreate(){
        super.onCreate();store=MinerStore(this);worker=MinerWorker(this,store,allowed=::allowed)
        store.phase=MinerState.CONNECTING
        store.pauseReason=null
        registerReceiver(conditions,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){store.enabled=false;stopSelf();return START_NOT_STICKY}
        if(!store.enabled){stopSelf();return START_NOT_STICKY}
        changed()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,getString(R.string.miner_title),NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,0,Intent(this,MinerService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(this,0,Intent(this,MinerActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(3047,Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.miner_notification)).setContentText(getString(R.string.miner_conditions_note))
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,getString(R.string.miner_stop),stop).build()).build())
        if(!started){
            started=true
            heart.scheduleWithFixedDelay({runCatching{worker.heartbeat()}},0,15,TimeUnit.SECONDS)
            jobs.scheduleWithFixedDelay({if(store.enabled)runCatching{worker.step()} else stopSelf()},0,3,TimeUnit.SECONDS)
        }
        return START_STICKY
    }
    override fun onDestroy(){
        if(::worker.isInitialized)worker.cancel()
        jobs.shutdownNow();heart.shutdownNow()
        if(::worker.isInitialized)Thread{runCatching{worker.pause()}}.apply{isDaemon=true;start()}
        unregisterReceiver(conditions)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
    companion object { const val STOP="hivo_miner_stop";private const val CHANNEL="hivo_community_miner" }
}
