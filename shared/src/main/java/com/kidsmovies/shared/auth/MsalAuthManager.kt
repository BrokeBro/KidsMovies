package com.kidsmovies.shared.auth

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MsalAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "MsalAuthManager"
        private val SCOPES = arrayOf("Files.Read.All", "Sites.Read.All")

        @Suppress("DEPRECATION", "PackageManagerGetSignatures")
        fun getSignatureHash(context: Context): String {
            val info = context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNATURES
            )
            val signature = info.signatures[0]
            val md = MessageDigest.getInstance("SHA")
            md.update(signature.toByteArray())
            return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
        }

        fun generateConfig(context: Context, clientId: String): File {
            val hash = getSignatureHash(context)
            val redirectUri = "msauth://${context.packageName}/$hash"
            val config = """{
  "client_id": "$clientId",
  "authorization_user_agent": "DEFAULT",
  "redirect_uri": "$redirectUri",
  "account_mode": "SINGLE",
  "broker_redirect_uri_registered": false,
  "authorities": [
    {
      "type": "AAD",
      "audience": {
        "type": "AzureADandPersonalMicrosoftAccount",
        "tenant_id": "common"
      }
    }
  ]
}"""
            val file = File(context.filesDir, "msal_config_dynamic.json")
            file.writeText(config)
            return file
        }
    }

    private var msalApp: IPublicClientApplication? = null
    private var currentAccount: IAccount? = null

    suspend fun initialize(configResourceId: Int) {
        withContext(Dispatchers.IO) {
            try {
                msalApp = suspendCancellableCoroutine { cont ->
                    PublicClientApplication.createSingleAccountPublicClientApplication(
                        context,
                        configResourceId,
                        object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                            override fun onCreated(application: ISingleAccountPublicClientApplication) {
                                cont.resume(application)
                            }

                            override fun onError(exception: MsalException) {
                                Log.e(TAG, "Failed to create MSAL application", exception)
                                cont.resumeWithException(exception)
                            }
                        }
                    )
                }

                // Try to get currently signed-in account
                val singleApp = msalApp as? ISingleAccountPublicClientApplication
                try {
                    val accountResult = singleApp?.currentAccount
                    currentAccount = accountResult?.currentAccount
                } catch (e: Exception) {
                    Log.w(TAG, "No existing account found", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing MSAL", e)
            }
        }
    }

    suspend fun initialize(configFile: File) {
        withContext(Dispatchers.IO) {
            try {
                msalApp = suspendCancellableCoroutine { cont ->
                    PublicClientApplication.createSingleAccountPublicClientApplication(
                        context,
                        configFile,
                        object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                            override fun onCreated(application: ISingleAccountPublicClientApplication) {
                                cont.resume(application)
                            }

                            override fun onError(exception: MsalException) {
                                Log.e(TAG, "Failed to create MSAL application", exception)
                                cont.resumeWithException(exception)
                            }
                        }
                    )
                }

                // Try to get currently signed-in account
                val singleApp = msalApp as? ISingleAccountPublicClientApplication
                try {
                    val accountResult = singleApp?.currentAccount
                    currentAccount = accountResult?.currentAccount
                } catch (e: Exception) {
                    Log.w(TAG, "No existing account found", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing MSAL", e)
            }
        }
    }

    fun isSignedIn(): Boolean = currentAccount != null

    suspend fun signIn(activity: Activity): String? {
        val app = msalApp as? ISingleAccountPublicClientApplication
            ?: throw IllegalStateException("MSAL not initialized")

        return suspendCancellableCoroutine { cont ->
            val params = SignInParameters.builder()
                .withActivity(activity)
                .withScopes(SCOPES.toList())
                .withCallback(object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        currentAccount = authenticationResult.account
                        cont.resume(authenticationResult.accessToken)
                    }

                    override fun onError(exception: MsalException) {
                        Log.e(TAG, "Sign-in failed", exception)
                        cont.resumeWithException(exception)
                    }

                    override fun onCancel() {
                        cont.resume(null)
                    }
                })
                .build()
            app.signIn(params)
        }
    }

    suspend fun acquireTokenSilently(): String? {
        val app = msalApp as? ISingleAccountPublicClientApplication ?: return null
        val account = currentAccount ?: return null

        return withContext(Dispatchers.IO) {
            try {
                suspendCancellableCoroutine { cont ->
                    val params = AcquireTokenSilentParameters.Builder()
                        .forAccount(account)
                        .fromAuthority(account.authority)
                        .forceRefresh(false)
                        .withScopes(SCOPES.toList())
                        .withCallback(object : SilentAuthenticationCallback {
                            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                                currentAccount = authenticationResult.account
                                cont.resume(authenticationResult.accessToken)
                            }

                            override fun onError(exception: MsalException) {
                                Log.e(TAG, "Silent token acquisition failed", exception)
                                cont.resume(null)
                            }
                        })
                        .build()
                    app.acquireTokenSilentAsync(params)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring token silently", e)
                null
            }
        }
    }

    suspend fun signOut() {
        val app = msalApp as? ISingleAccountPublicClientApplication ?: return

        withContext(Dispatchers.IO) {
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                        override fun onSignOut() {
                            currentAccount = null
                            cont.resume(Unit)
                        }

                        override fun onError(exception: MsalException) {
                            Log.e(TAG, "Sign-out failed", exception)
                            currentAccount = null
                            cont.resume(Unit)
                        }
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error signing out", e)
                currentAccount = null
            }
        }
    }

    fun getAccountDisplayName(): String? = currentAccount?.username
}
