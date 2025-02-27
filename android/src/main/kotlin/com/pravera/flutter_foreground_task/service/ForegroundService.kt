package com.pravera.flutter_foreground_task.service

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.*
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pravera.flutter_foreground_task.FlutterForegroundTaskLifecycleListener
import com.pravera.flutter_foreground_task.RequestCode
import com.pravera.flutter_foreground_task.models.*
import com.pravera.flutter_foreground_task.utils.ForegroundServiceUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.*
import com.pravera.flutter_foreground_task.service.NotificationDismissedReceiver
import android.widget.RemoteViews
import com.pravera.flutter_foreground_task.R

/**
 * A service class for implementing foreground service.
 *
 * @author Dev-hwang
 * @version 1.0
 */
class ForegroundService : Service() {
    companion object {
        private val TAG = ForegroundService::class.java.simpleName

        private const val ACTION_NOTIFICATION_PRESSED = "onNotificationPressed"
        private const val ACTION_NOTIFICATION_DISMISSED = "onNotificationDismissed"
        private const val ACTION_NOTIFICATION_BUTTON_PRESSED = "onNotificationButtonPressed"
        private const val ACTION_RECEIVE_DATA = "onReceiveData"
        private const val INTENT_DATA_NAME = "intentData"

        private val _isRunningServiceState = MutableStateFlow(false)
        val isRunningServiceState = _isRunningServiceState.asStateFlow()

        private var task: ForegroundTask? = null
        private var taskLifecycleListeners = ForegroundTaskLifecycleListeners()

        fun addTaskLifecycleListener(listener: FlutterForegroundTaskLifecycleListener) {
            taskLifecycleListeners.addListener(listener)
        }

        fun removeTaskLifecycleListener(listener: FlutterForegroundTaskLifecycleListener) {
            taskLifecycleListeners.removeListener(listener)
        }

        fun handleNotificationContentIntent(intent: Intent?) {
            if (intent == null) return

            try {
                // Check if the given intent is a LaunchIntent.
                val isLaunchIntent = (intent.action == Intent.ACTION_MAIN) &&
                        intent.categories.contains(Intent.CATEGORY_LAUNCHER)
                if (!isLaunchIntent) {
                    // Log.d(TAG, "not LaunchIntent")
                    return
                }

                val data = intent.getStringExtra(INTENT_DATA_NAME)
                if (data == ACTION_NOTIFICATION_PRESSED) {
                    task?.invokeMethod(data, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, e.message, e)
            }
        }

        fun sendData(data: Any?) {
            if (isRunningServiceState.value) {
                task?.invokeMethod(ACTION_RECEIVE_DATA, data)
            }
        }
    }

    private lateinit var foregroundServiceStatus: ForegroundServiceStatus
    private lateinit var foregroundTaskOptions: ForegroundTaskOptions
    private lateinit var foregroundTaskData: ForegroundTaskData
    private lateinit var notificationOptions: NotificationOptions
    private lateinit var notificationContent: NotificationContent
    private var prevForegroundTaskOptions: ForegroundTaskOptions? = null
    private var prevForegroundTaskData: ForegroundTaskData? = null
    private var prevNotificationOptions: NotificationOptions? = null
    private var prevNotificationContent: NotificationContent? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // A broadcast receiver that handles intents that occur in the foreground service.
    private var broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            try {
                val action = intent.action ?: return
                val data = intent.getStringExtra(INTENT_DATA_NAME)
                
                if (action == ACTION_NOTIFICATION_BUTTON_PRESSED) {
                    task?.invokeMethod(action, data)
                }
            } catch (e: Exception) {
                Log.e(TAG, e.message, e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerBroadcastReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadDataFromPreferences()

        var action = foregroundServiceStatus.action
        val isSetStopWithTaskFlag = ForegroundServiceUtils.isSetStopWithTaskFlag(this)

        if (action == ForegroundServiceAction.API_STOP) {
            RestartReceiver.cancelRestartAlarm(this)
            stopForegroundService()
            return START_NOT_STICKY
        }

        if (intent == null) {
            ForegroundServiceStatus.setData(this, ForegroundServiceAction.RESTART)
            foregroundServiceStatus = ForegroundServiceStatus.getData(this)
            action = foregroundServiceStatus.action
        }

        when (action) {
            ForegroundServiceAction.API_START,
            ForegroundServiceAction.API_RESTART -> {
                startForegroundService()
                createForegroundTask()
            }
            ForegroundServiceAction.API_UPDATE -> {
                updateNotification()
                val prevCallbackHandle = prevForegroundTaskData?.callbackHandle
                val currCallbackHandle = foregroundTaskData.callbackHandle
                if (prevCallbackHandle != currCallbackHandle) {
                    createForegroundTask()
                } else {
                    val prevEventAction = prevForegroundTaskOptions?.eventAction
                    val currEventAction = foregroundTaskOptions.eventAction
                    if (prevEventAction != currEventAction) {
                        updateForegroundTask()
                    }
                }
            }
            ForegroundServiceAction.REBOOT,
            ForegroundServiceAction.RESTART -> {
                startForegroundService()
                createForegroundTask()
                Log.d(TAG, "The service has been restarted by Android OS.")
            }
        }

        return if (isSetStopWithTaskFlag) {
            START_NOT_STICKY
        } else {
            START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyForegroundTask()
        stopForegroundService()
        unregisterBroadcastReceiver()

        val isCorrectlyStopped = foregroundServiceStatus.isCorrectlyStopped()
        val isSetStopWithTaskFlag = ForegroundServiceUtils.isSetStopWithTaskFlag(this)
        if (!isCorrectlyStopped && !isSetStopWithTaskFlag) {
            Log.e(TAG, "The service was terminated due to an unexpected problem. The service will restart after 5 seconds.")
            RestartReceiver.setRestartAlarm(this, 5000)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (ForegroundServiceUtils.isSetStopWithTaskFlag(this)) {
            stopSelf()
        } else {
            RestartReceiver.setRestartAlarm(this, 1000)
        }
    }

    private fun loadDataFromPreferences() {
        foregroundServiceStatus = ForegroundServiceStatus.getData(applicationContext)

        if (::foregroundTaskOptions.isInitialized) {
            prevForegroundTaskOptions = foregroundTaskOptions
        }
        foregroundTaskOptions = ForegroundTaskOptions.getData(applicationContext)

        if (::foregroundTaskData.isInitialized) {
            prevForegroundTaskData = foregroundTaskData
        }
        foregroundTaskData = ForegroundTaskData.getData(applicationContext)

        if (::notificationOptions.isInitialized) {
            prevNotificationOptions = notificationOptions
        }
        notificationOptions = NotificationOptions.getData(applicationContext)

        if (::notificationContent.isInitialized) {
            prevNotificationContent = notificationContent
        }
        notificationContent = NotificationContent.getData(applicationContext)
    }

    private fun registerBroadcastReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(ACTION_NOTIFICATION_BUTTON_PRESSED)
            addAction(ACTION_NOTIFICATION_PRESSED)
            addAction(ACTION_NOTIFICATION_DISMISSED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, intentFilter)
        }
    }

    private fun unregisterBroadcastReceiver() {
        unregisterReceiver(broadcastReceiver)
    }

    @SuppressLint("WrongConstant", "SuspiciousIndentation")
    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }

        val serviceId = notificationOptions.serviceId
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                serviceId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            )
        } else {
            startForeground(serviceId, notification)
        }

        releaseLockMode()
        acquireLockMode()

        _isRunningServiceState.update { true }
    }

    private fun stopForegroundService() {
        releaseLockMode()
        stopForeground(true)
        stopSelf()

        _isRunningServiceState.update { false }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channelId = notificationOptions.channelId
        val channelName = notificationOptions.channelName
        val channelDesc = notificationOptions.channelDescription
        val channelImportance = notificationOptions.channelImportance

        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(channelId, channelName, channelImportance).apply {
                if (channelDesc != null) {
                    description = channelDesc
                }
                enableVibration(notificationOptions.enableVibration)
                if (!notificationOptions.playSound) {
                    setSound(null, null)
                }
                setShowBadge(notificationOptions.showBadge)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val icon = notificationContent.icon
        val iconResId = getIconResId(icon)
        
        // Create collapsed and expanded RemoteViews
        val collapsedView = RemoteViews(packageName, R.layout.custom_notification)
        val expandedView = RemoteViews(packageName, R.layout.notification_expanded)
        
        // Set notification content for both views
        collapsedView.setTextViewText(R.id.notification_text, notificationContent.text)
        expandedView.setTextViewText(R.id.notification_text, notificationContent.text)
        
        // Get pause state from notification buttons if available
        val isPaused = notificationContent.buttons?.firstOrNull()?.let { button ->
            (button as? Map<*, *>)?.get("text") as? String == "Resume"
        } ?: false

        // Set the appropriate image based on pause state
        val buttonImageRes = if (isPaused) {
            R.drawable.play_one
        } else {
            R.drawable.pause_one
        }
        
        collapsedView.setImageViewResource(R.id.pauseResume_button, buttonImageRes)
        expandedView.setImageViewResource(R.id.pauseResume_button, buttonImageRes)

        // Create content intent to open app
        val contentIntent = getContentIntent()

        // Add pause/resume button click listener
        val pauseResumeIntent = getPendingIntent("pauseResume", 1)
        collapsedView.setOnClickPendingIntent(R.id.pauseResume_button, pauseResumeIntent)
        expandedView.setOnClickPendingIntent(R.id.pauseResume_button, pauseResumeIntent)

        return NotificationCompat.Builder(this, notificationOptions.channelId)
            .setSmallIcon(iconResId)
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .setStyle(
                NotificationCompat.InboxStyle()
                .addLine("Re: Planning")
                .addLine("Delivery on its way")
                .addLine("Follow-up")
.addLine("Follow-up").addLine("Follow-up").addLine("Follow-up").addLine("Follow-up")
                )
            .build()
    }

    private fun updateNotification() {
        val serviceId = notificationOptions.serviceId
        val notification = createNotification()
        val nm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(NotificationManager::class.java)
        } else {
            // crash 23+
            ContextCompat.getSystemService(this, NotificationManager::class.java)
        }
        nm?.notify(serviceId, notification)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLockMode() {
        if (foregroundTaskOptions.allowWakeLock && (wakeLock == null || wakeLock?.isHeld == false)) {
            wakeLock =
                (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                    newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ForegroundService:WakeLock").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
        }

        if (foregroundTaskOptions.allowWifiLock && (wifiLock == null || wifiLock?.isHeld == false)) {
            wifiLock =
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).run {
                    createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ForegroundService:WifiLock").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
        }
    }

    private fun releaseLockMode() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                wakeLock = null
            }
        }

        wifiLock?.let {
            if (it.isHeld) {
                it.release()
                wifiLock = null
            }
        }
    }

    private fun createForegroundTask() {
        destroyForegroundTask()

        task = ForegroundTask(
            context = this,
            serviceStatus = foregroundServiceStatus,
            taskData = foregroundTaskData,
            taskEventAction = foregroundTaskOptions.eventAction,
            taskLifecycleListener = taskLifecycleListeners
        )
    }

    private fun updateForegroundTask() {
        task?.update(taskEventAction = foregroundTaskOptions.eventAction)
    }

    private fun destroyForegroundTask() {
        task?.destroy()
        task = null
    }

    private fun getIconResId(icon: NotificationIcon?): Int {
        try {
            val packageManager = applicationContext.packageManager
            val packageName = applicationContext.packageName
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)

            // application icon
            if (icon == null) {
                return appInfo.icon
            }

            // custom icon
            val metaData = appInfo.metaData
            if (metaData != null) {
                return metaData.getInt(icon.metaDataName)
            }

            return 0
        } catch (e: Exception) {
            Log.e(TAG, "getIconResId($icon)", e)
            return 0
        }
    }

    private fun getContentIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            putExtra(INTENT_DATA_NAME, ACTION_NOTIFICATION_PRESSED)
        }

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }

        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun getDeleteIntent(): PendingIntent {
        val intent = Intent(ACTION_NOTIFICATION_DISMISSED).apply {
            setPackage(packageName)
        }

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }

        return PendingIntent.getBroadcast(this, RequestCode.NOTIFICATION_DISMISSED, intent, flags)
    }

    private fun getRgbColor(rgb: String): Int? {
        val rgbSet = rgb.split(",")
        return if (rgbSet.size == 3) {
            Color.rgb(rgbSet[0].toInt(), rgbSet[1].toInt(), rgbSet[2].toInt())
        } else {
            null
        }
    }

    private fun getTextSpan(text: String, color: Int?): Spannable {
        return if (color != null) {
            SpannableString(text).apply {
                setSpan(ForegroundColorSpan(color), 0, length, 0)
            }
        } else {
            SpannableString(text)
        }
    }

    private fun buildNotificationActions(
        buttons: List<NotificationButton>,
        needsRebuild: Boolean = false
    ): List<Notification.Action> {
        val actions = mutableListOf<Notification.Action>()
        for (i in buttons.indices) {
            val intent = Intent(ACTION_NOTIFICATION_BUTTON_PRESSED).apply {
                setPackage(packageName)
                putExtra(INTENT_DATA_NAME, buttons[i].id)
            }
            var flags = PendingIntent.FLAG_IMMUTABLE
            if (needsRebuild) {
                flags = flags or PendingIntent.FLAG_CANCEL_CURRENT
            }
            val textColor = buttons[i].textColorRgb?.let(::getRgbColor)
            val text = getTextSpan(buttons[i].text, textColor)
            val pendingIntent =
                PendingIntent.getBroadcast(this, i + 1, intent, flags)
            val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Notification.Action.Builder(null, text, pendingIntent).build()
            } else {
                Notification.Action.Builder(0, text, pendingIntent).build()
            }
            actions.add(action)
        }

        return actions
    }

    private fun buildNotificationCompatActions(
        buttons: List<NotificationButton>,
        needsRebuild: Boolean = false
    ): List<NotificationCompat.Action> {
        val actions = mutableListOf<NotificationCompat.Action>()
        for (i in buttons.indices) {
            val intent = Intent(ACTION_NOTIFICATION_BUTTON_PRESSED).apply {
                setPackage(packageName)
                putExtra(INTENT_DATA_NAME, buttons[i].id)
            }
            var flags = PendingIntent.FLAG_IMMUTABLE
            if (needsRebuild) {
                flags = flags or PendingIntent.FLAG_CANCEL_CURRENT
            }
            val textColor = buttons[i].textColorRgb?.let(::getRgbColor)
            val text = getTextSpan(buttons[i].text, textColor)
            val pendingIntent =
                PendingIntent.getBroadcast(this, i + 1, intent, flags)
            val action = NotificationCompat.Action.Builder(0, text, pendingIntent).build()
            actions.add(action)
        }

        return actions
    }

    private fun getPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(ACTION_NOTIFICATION_BUTTON_PRESSED).apply {
            setPackage(packageName)
            putExtra(INTENT_DATA_NAME, action)
        }
        
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        
        return PendingIntent.getBroadcast(this, requestCode, intent, flags)
    }
}