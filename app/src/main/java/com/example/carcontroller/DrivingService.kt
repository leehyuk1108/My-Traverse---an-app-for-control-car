package com.example.carcontroller

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.app.AlarmManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
// ===================================
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class DrivingService : Service(), CoroutineScope by CoroutineScope(Dispatchers.IO) {

    companion object {
        const val ACTION_START_DRIVING = "ACTION_START_DRIVING"
        const val ACTION_STOP_DRIVING = "ACTION_STOP_DRIVING"

        const val BROADCAST_DRIVING_UPDATE = "BROADCAST_DRIVING_UPDATE"
        const val EXTRA_DRIVING_TIME = "EXTRA_DRIVING_TIME"
        const val EXTRA_DRIVING_DISTANCE = "EXTRA_DRIVING_DISTANCE"

        private const val NOTIFICATION_CHANNEL_ID = "DrivingChannel"
        private const val NOTIFICATION_ID = 3 // (Timer/Location과 겹치지 않게)

        // (신규) SharedPreferences에 저장할 키 (MainActivity가 읽을 수 있도록 public)
        const val PREF_KEY_LAST_DRIVE_TIME = "LAST_DRIVE_TIME"
        const val PREF_KEY_LAST_DRIVE_DIST = "LAST_DRIVE_DIST"
        const val PREF_KEY_LAST_DRIVE_AVG_SPEED = "LAST_DRIVE_AVG_SPEED"
        const val PREF_KEY_LAST_DRIVE_TOP_SPEED = "LAST_DRIVE_TOP_SPEED"

        // ‼️ (신규) 임시 상태 저장용 키
        const val PREF_KEY_IS_DRIVING_ACTIVE = "IS_DRIVING_ACTIVE"
        const val PREF_KEY_TEMP_START_TIME = "TEMP_START_TIME"
        const val PREF_KEY_TEMP_DIST = "TEMP_DIST"
        const val PREF_KEY_TEMP_TOP_SPEED = "TEMP_TOP_SPEED"

        // ‼️ (신규) 주차 위치 주소 저장용 키
        const val PREF_KEY_LOCATION_ADDRESS = "saved_location_address"

        const val HISTORY_FILE_NAME = "driving_history.json"
    }

    private lateinit var locationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var drivingTimerJob: Job? = null
    private var lastLocation: Location? = null
    private var totalDistanceMeters: Double = 0.0
    private var startTimeMillis: Long = 0L
    private var topSpeedMs: Float = 0f



    private var startAddress: String = "" // ‼️ (신규) 출발지 주소

    override fun onCreate() {
        super.onCreate()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // ‼️ (신규) 시스템에 의해 서비스가 재시작된 경우 (START_STICKY)
            Log.d("DrivingService", "Service restarted by system (intent is null)")
            if (tryRestoreState()) {
                 Log.d("DrivingService", "State restored, resuming tracking...")
                 startDrivingTracking()
            }
        } else {
            when (intent.action) {
                ACTION_START_DRIVING -> {
                    startDrivingTracking()
                }
                ACTION_STOP_DRIVING -> {
                    // ‼️ (수정) 비동기 저장을 위해 Coroutine으로 감쌈
                    launch {
                        stopDrivingTracking()
                    }
                }
            }
        }
        return START_STICKY
    }

    // ‼️ (신규) 앱이 '최근 앱'에서 스와이프되어 제거될 때 호출됨
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("DrivingService", "onTaskRemoved called - Scheduling restart")

        // 서비스가 종료되지 않도록 즉시 알람을 통해 재시작 예약 (1초 후)
        val restartServiceIntent = Intent(applicationContext, DrivingService::class.java).apply {
            setPackage(packageName)
        }
        val restartServicePendingIntent = PendingIntent.getService(
            applicationContext, 1, restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )
    }

    @SuppressLint("MissingPermission")
    private fun startDrivingTracking() {
        if (drivingTimerJob?.isActive == true) return // 이미 실행 중

        Log.d("DrivingService", "주행 기록 시작")

        // ‼️ (신규) 기존 상태 복원 시도
        if (tryRestoreState()) {
             Log.d("DrivingService", "기존 주행 상태 복원됨 ($totalDistanceMeters m, $startTimeMillis)")
             startTimeMillis = System.currentTimeMillis()
             totalDistanceMeters = 0.0
             topSpeedMs = 0f

             startAddress = "" // ‼️ 초기화

             // ‼️ (신규) 출발지 주소 가져오기 (비동기)
             launch {
                 val hasLocationPermission = ActivityCompat.checkSelfPermission(this@DrivingService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                 if (hasLocationPermission) {
                     try {
                         val location = locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
                         if (location != null) {
                             val geocoder = android.location.Geocoder(this@DrivingService, Locale.KOREAN)
                             @Suppress("DEPRECATION")
                             val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                             if (!addresses.isNullOrEmpty()) {
                                 startAddress = addresses[0].getAddressLine(0)
                                 Log.d("DrivingService", "출발지 주소 확보: $startAddress")
                             }
                         }
                     } catch (e: Exception) {
                         Log.e("DrivingService", "Failed to get start address", e)
                     }
                 }
             }

             lastLocation = null
        }

        startForeground(NOTIFICATION_ID, createNotification("차량과 블루투스로 연결됨", ""))

        // ‼️ (신규) 임시 상태 저장 (1초마다 호출)
        saveTemporaryState()

        // 1. 위치 추적 시작
        setupLocationUpdates()

        // 2. 1초 타이머 시작
        drivingTimerJob = launch {
            while (true) {
                val elapsedSeconds = (System.currentTimeMillis() - startTimeMillis) / 1000
                val timeString = formatTime(elapsedSeconds)
                val distanceString = formatDistance(totalDistanceMeters)

                // MainActivity로 브로드캐스트
                broadcastDrivingUpdate(timeString, distanceString)

                // ‼️ (신규) 매 초마다 상태 저장 (비정상 종료 대비)
                saveTemporaryState()

                delay(1000)
            }
        }
    }

    // ‼️ (수정) suspend 함수로 변경
    // ‼️ (수정) suspend 함수로 변경
    private suspend fun stopDrivingTracking() {
        // ‼️ (수정) 중복 저장 방지를 위한 Atomic Check
        val currentStartTime = startTimeMillis
        if (currentStartTime == 0L) {
             Log.d("DrivingService", "Start time is 0. Already stopped.")
             return
        }
        startTimeMillis = 0L // ‼️ 재진입 방지 (초기화)

        Log.d("DrivingService", "주행 기록 중지")
        drivingTimerJob?.cancel()
        if (::locationCallback.isInitialized) {
            locationClient.removeLocationUpdates(locationCallback)
        }
        stopForeground(true)

        // ‼️ (수정) 주행 기록 및 '최종 위치' 저장 (캡처된 시간 전달)
        saveDrivingSessionAndLocation(currentStartTime)
        
        // ‼️ (신규) 임시 상태 클리어 (정상 종료되었으므로)
        clearTemporaryState()

        stopSelf()
    }

    // --- State Persistence Methods ---
    private fun saveTemporaryState() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(PREF_KEY_IS_DRIVING_ACTIVE, true)
            putLong(PREF_KEY_TEMP_START_TIME, startTimeMillis)
            putFloat(PREF_KEY_TEMP_DIST, totalDistanceMeters.toFloat())
            putFloat(PREF_KEY_TEMP_TOP_SPEED, topSpeedMs)
            apply()
        }
    }

    private fun clearTemporaryState() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(PREF_KEY_IS_DRIVING_ACTIVE, false)
            remove(PREF_KEY_TEMP_START_TIME)
            remove(PREF_KEY_TEMP_DIST)
            remove(PREF_KEY_TEMP_TOP_SPEED)
            apply()
        }
    }

    private fun tryRestoreState(): Boolean {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean(PREF_KEY_IS_DRIVING_ACTIVE, false)
        if (isActive) {
            val savedStartTime = prefs.getLong(PREF_KEY_TEMP_START_TIME, 0L)
            // Restore only if valid and recent (e.g. less than 24 hours to prevent really old zombies)
            if (savedStartTime > 0 && (System.currentTimeMillis() - savedStartTime) < 24 * 60 * 60 * 1000) {
                startTimeMillis = savedStartTime
                totalDistanceMeters = prefs.getFloat(PREF_KEY_TEMP_DIST, 0f).toDouble()
                topSpeedMs = prefs.getFloat(PREF_KEY_TEMP_TOP_SPEED, 0f)
                
                // Note: startAddress is not currently persisted in temp state, might be lost on crash.
                // For simplicity, we tolerate this or could add it to prefs if critical.
                
                Log.d("DrivingService", "State Restored: Dist=$totalDistanceMeters, Top=$topSpeedMs")
                return true
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private suspend fun saveDrivingSessionAndLocation(sessionStartTime: Long) {
        if (sessionStartTime == 0L) return

        val elapsedSeconds = (System.currentTimeMillis() - sessionStartTime) / 1000

        if (elapsedSeconds < 1) {
            Log.d("DrivingService", "주행 시간이 너무 짧아 저장하지 않음")
            return
        }

        // --- 1. 주행 기록 계산 ---
        val finalTimeString = formatTime(elapsedSeconds)
        val finalDistanceString = formatDistance(totalDistanceMeters)

        val elapsedHours = elapsedSeconds / 3600.0
        val totalDistanceKm = totalDistanceMeters / 1000.0
        val avgSpeedKmh = if (elapsedHours > 0) (totalDistanceKm / elapsedHours) else 0.0
        val finalAvgSpeedString = "${avgSpeedKmh.roundToInt()} km/h"

        val topSpeedKmh = (topSpeedMs * 3.6).roundToInt()
        val finalTopSpeedString = "$topSpeedKmh km/h"

        // --- 2. ‼️ (신규) 마지막 주차 위치 가져오기 ---
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w("DrivingService", "Location permission missing, cannot save parking spot.")
            return
        }

        // (현재 위치를 비동기로 가져오기 - await)
        val location: Location? = try {
            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
        } catch (e: Exception) {
            Log.e("DrivingService", "Failed to get last location", e)
            null
        }

        // --- 3. SharedPreferences에 모든 데이터 저장 ---
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        var currentAddress = "주소를 찾을 수 없음"
        if (location != null) {
            Log.d("DrivingService", "주차 위치 저장: ${location.latitude}, ${location.longitude}")
            val timeFormat = SimpleDateFormat("HH:mm", Locale.KOREAN)
            val timestamp = timeFormat.format(Date())

            editor.putString(MainActivity.PREF_KEY_LOCATION_LAT, location.latitude.toString())
            editor.putString(MainActivity.PREF_KEY_LOCATION_LON, location.longitude.toString())
            editor.putString(MainActivity.PREF_KEY_LOCATION_TIME, timestamp)

            // 주행 이력 저장을 위해 주소를 미리 가져옴
            try {
                val geocoder = android.location.Geocoder(this, Locale.KOREAN)
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    currentAddress = addresses[0].getAddressLine(0)
                    // ‼️ (신규) 주차 위치 주소 Preference에 저장
                    editor.putString(PREF_KEY_LOCATION_ADDRESS, currentAddress)
                } 
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            Log.w("DrivingService", "주차 위치 (null), 기존 위치 유지")
        }

        editor.putString(PREF_KEY_LAST_DRIVE_TIME, finalTimeString)
        editor.putString(PREF_KEY_LAST_DRIVE_DIST, finalDistanceString)
        editor.putString(PREF_KEY_LAST_DRIVE_AVG_SPEED, finalAvgSpeedString)
        editor.putString(PREF_KEY_LAST_DRIVE_TOP_SPEED, finalTopSpeedString)

        editor.apply()

        val endAddress = currentAddress // ‼️ 명칭 명확화

        // --- 4. ‼️ (신규) 주행 이력 파일에 저장 ---
        saveToHistoryFile(
            date = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN).format(Date()),
            time = SimpleDateFormat("HH:mm", Locale.KOREAN).format(Date()),
            duration = finalTimeString,
            distance = finalDistanceString,
            avgSpeed = finalAvgSpeedString,
            topSpeed = finalTopSpeedString,

            startAddress = startAddress, // ‼️ 전달
            endAddress = endAddress      // ‼️ 전달
        )

        Log.d("DrivingService", "주행 기록 및 위치 저장 완료")
    }

    private fun saveToHistoryFile(date: String, time: String, duration: String, distance: String, avgSpeed: String, topSpeed: String, startAddress: String, endAddress: String) {
        try {
            val file = java.io.File(filesDir, HISTORY_FILE_NAME)
            val historyArray = if (file.exists()) {
                val content = file.readText()
                org.json.JSONArray(content)
            } else {
                org.json.JSONArray()
            }

            val newSession = org.json.JSONObject().apply {
                put("date", date)
                put("time", time)
                put("duration", duration)
                put("distance", distance)
                put("avgSpeed", avgSpeed)
                put("topSpeed", topSpeed)
                put("startAddress", startAddress) // ‼️ 추가
                put("endAddress", endAddress)     // ‼️ 추가
                put("address", endAddress)        // ‼️ 호환성 유지 (구버전용)
            }

            historyArray.put(newSession)
            file.writeText(historyArray.toString())
            Log.d("DrivingService", "History saved to file: $HISTORY_FILE_NAME")
        } catch (e: Exception) {
            Log.e("DrivingService", "Failed to save history file", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w("DrivingService", "Location permission missing, cannot start updates.")
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000) // 10초
            .setMinUpdateIntervalMillis(5000) // 5초
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { newLocation ->
                    var currentSpeed = 0f

                    if (newLocation.hasSpeed()) {
                        currentSpeed = newLocation.speed
                    }

                    if (lastLocation != null) {
                        // Fallback calculation if speed is missing
                        if (currentSpeed == 0f) {
                            val timeDelta = (newLocation.time - lastLocation!!.time) / 1000.0 // seconds
                            if (timeDelta > 0) {
                                val dist = newLocation.distanceTo(lastLocation!!)
                                currentSpeed = (dist / timeDelta).toFloat()
                            }
                        }

                        // Accuracy check
                        if (newLocation.accuracy < 200.0) {
                            val distance = newLocation.distanceTo(lastLocation!!)
                            totalDistanceMeters += distance
                        }
                    }

                    if (currentSpeed > topSpeedMs) {
                        topSpeedMs = currentSpeed
                    }

                    lastLocation = newLocation
                }
            }
        }

        locationClient.requestLocationUpdates(locationRequest, locationCallback, null) // (Looper null)
    }

    private fun broadcastDrivingUpdate(time: String, distance: String) {
        val intent = Intent(BROADCAST_DRIVING_UPDATE).apply {
            putExtra(EXTRA_DRIVING_TIME, time)
            putExtra(EXTRA_DRIVING_DISTANCE, distance)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // --- 알림 관련 ---
    private fun createNotification(title: String, text: String): android.app.Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val notification = createNotification(title, text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "주행 기록 (백그라운드)",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    // --- 포맷 헬퍼 ---
    private fun formatTime(totalSeconds: Long): String {
        val hours = TimeUnit.SECONDS.toHours(totalSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatDistance(meters: Double): String {
        return if (meters < 1000) {
            "${meters.roundToInt()} m"
        } else {
            String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}