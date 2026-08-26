package com.mhlko.talk

import android.os.Bundle
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mhlko.talk.ui.MHTalkApp
import com.mhlko.talk.ui.PipController
import com.mhlko.talk.ui.theme.MHTalkTheme
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.mhlko.talk.auth.AuthRepository
import com.mhlko.talk.auth.SocialRepository
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeFirebase()
        handleAppIntent(intent)
        enableEdgeToEdge()
        setContent {
            MHTalkTheme {
                MHTalkApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAppIntent(intent)
    }

    private fun handleAppIntent(intent: Intent?) {
        lifecycleScope.launch {
            val auth = AuthRepository.get(this@MainActivity)
            val handledAuth = auth.handleDeepLink(intent?.data)
            if (!handledAuth) auth.initialize()
            val uri = intent?.data
            if (uri?.scheme == "mhtalk" && uri.host == "invite" && uri.pathSegments.isNotEmpty()) {
                runCatching { SocialRepository.get(this@MainActivity).loadInvite(uri.pathSegments.first()) }
            }
        }
    }

    private fun initializeFirebase() {
        if (BuildConfig.FIREBASE_PROJECT_ID.isBlank() || BuildConfig.FIREBASE_APP_ID.isBlank() ||
            BuildConfig.FIREBASE_API_KEY.isBlank() || BuildConfig.FIREBASE_SENDER_ID.isBlank()
        ) return
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder().setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build(),
            )
        }
        FirebaseMessaging.getInstance().register()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipController.inPictureInPicture = isInPictureInPictureMode
        if (!isInPictureInPictureMode) PipController.track = null
    }
}
