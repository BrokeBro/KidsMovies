package com.kidsmovies.parent

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.FirebaseDatabase
import com.kidsmovies.parent.firebase.FamilyManager
import com.kidsmovies.parent.firebase.PairingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ParentApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val familyManager by lazy { FamilyManager() }
    val pairingManager by lazy { PairingManager() }

    var firebaseInitialized = false
        private set

    override fun onCreate() {
        super.onCreate()

        initializeFirebase()
    }

    private fun initializeFirebase() {
        try {
            // Check if Firebase is already initialized (from google-services.json)
            if (FirebaseApp.getApps(this).isNotEmpty()) {
                firebaseInitialized = true
                FirebaseDatabase.getInstance().setPersistenceEnabled(true)
                Log.d(TAG, "Firebase initialized from google-services.json")
                return
            }

            // Manual initialization for demo/testing without google-services.json
            // Users should replace these with their own Firebase project credentials
            val options = FirebaseOptions.Builder()
                .setProjectId("kidsmovies-demo")
                .setApplicationId("1:000000000000:android:0000000000000000000000")
                .setApiKey("demo-api-key-replace-with-your-own")
                .setDatabaseUrl("https://kidsmovies-demo-default-rtdb.firebaseio.com")
                .build()

            FirebaseApp.initializeApp(this, options)
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
            firebaseInitialized = true
            Log.d(TAG, "Firebase initialized with demo configuration")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase: ${e.message}", e)
            firebaseInitialized = false
        }
    }

    companion object {
        private const val TAG = "ParentApp"
    }
}
