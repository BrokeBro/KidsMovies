package com.kidsmovies.parent

import android.app.Application
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

    override fun onCreate() {
        super.onCreate()

        // Enable Firebase offline persistence
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)
    }
}
