package com.capturedkeeps.app

import android.app.Application
import androidx.room.Room
import com.capturedkeeps.data.CkDatabase
import com.capturedkeeps.data.KioskFlow
import com.capturedkeeps.data.Store
import com.capturedkeeps.data.open
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID

class App : Application() {
    /** Outlives screens, so a save started by a tap always finishes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val store by lazy { Store(Room.databaseBuilder<CkDatabase>(this, getDatabasePath("captured-keeps.db").path).open()) }

    val kiosk by lazy { KioskFlow(store, deviceId, freeBytes = { filesDir.usableSpace }) }

    /** Stable per install; goes on every session so records from several booths can be merged later. */
    private val deviceId by lazy {
        File(filesDir, "device-id").run { if (exists()) readText() else UUID.randomUUID().toString().also(::writeText) }
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            store.seedDemoIfEmpty()
            store.recoverAfterRestart(Instant.now()) // ponytail: "Did it print?" prompts come with the staff screens
        }
    }
}
