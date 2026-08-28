package com.mhlko.talk.ui.auth

import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mhlko.talk.BuildConfig
import com.mhlko.talk.R
import com.mhlko.talk.auth.AuthRepository
import com.mhlko.talk.auth.AuthRules
import com.mhlko.talk.auth.AuthState
import com.mhlko.talk.ui.theme.MHTalkMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun requireValidUsername(username: String) {
    AuthRules.usernameError(username)?.let { throw IllegalArgumentException(it) }
}

private fun requireValidPassword(password: String) {
    AuthRules.passwordError(password)?.let { throw IllegalArgumentException(it) }
}

private enum class AuthMode { Login, Register, Forgot, Verification, RecoveryCode, Reset }

@Composable
internal fun RequiredSignInScreen(authState: AuthState, auth: AuthRepository, onRetry: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AuthMode.Login) }
    var identifier by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var authAvatar by remember { mutableStateOf<String?>(null) }
    var onboardingCodeSent by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var acceptedTerms by remember { mutableStateOf(false) }
    var localBusy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var resendSeconds by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val authPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runCatching {
            val mime = context.contentResolver.getType(uri).orEmpty().takeIf { it.startsWith("image/") } ?: "image/jpeg"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Could not read this image")
            require(bytes.size <= 5 * 1024 * 1024) { "Choose an image that is 5 MB or smaller" }
            authAvatar = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }.onFailure { localError = it.message ?: "Could not read this image" }
    }
    val busy = localBusy || authState == AuthState.Checking || authState == AuthState.Authenticating

    LaunchedEffect(authState) {
        when (authState) {
            is AuthState.AwaitingVerification -> { email = authState.email; mode = AuthMode.Verification }
            is AuthState.Onboarding -> {
                email = authState.email; username = authState.username; displayName = authState.displayName
                authAvatar = authState.avatarUrl; verificationCode = ""; onboardingCodeSent = false
            }
            AuthState.PasswordRecovery -> mode = AuthMode.Reset
            else -> Unit
        }
    }
    LaunchedEffect(resendSeconds) {
        if (resendSeconds > 0) { delay(1_000); resendSeconds-- }
    }
    fun switchMode(next: AuthMode) {
        auth.clearAuthError(); mode = next; localError = ""; notice = ""; verificationCode = ""
    }
    fun perform(block: suspend () -> Unit) {
        scope.launch {
            localBusy = true; localError = ""; notice = ""
            runCatching { block() }.onFailure { localError = it.message ?: "Something went wrong. Try again." }
            localBusy = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF11152A), Color(0xFF090C16))),
        ).padding(26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1E32)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF434A70)),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 25.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Box(
                        Modifier.size(58.dp).clip(RoundedCornerShape(17.dp)).background(
                            Brush.linearGradient(listOf(Color(0xFF8B78FF), Color(0xFF5B4ADE))),
                        ), contentAlignment = Alignment.Center,
                    ) { Text("M", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black) }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("MHTalk", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.width(8.dp))
                            Surface(color = Color(0xFF5B4AC6), shape = RoundedCornerShape(50), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8D7CFF))) {
                                Text("BETA", color = Color(0xFFFFF7CA), fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                            }
                        }
                        Text("Voice, video and rooms · v${BuildConfig.VERSION_NAME}", color = MHTalkMuted, fontSize = 11.sp)
                    }
                }
                HorizontalDivider(color = Color(0xFF303650))

                if (authState == AuthState.Checking) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Restoring your secure session…", color = MHTalkMuted)
                    }
                } else if (authState is AuthState.Onboarding) {
                    androidx.compose.foundation.Image(painterResource(R.drawable.ic_google_logo), null, Modifier.size(32.dp))
                    Text(
                        if (onboardingCodeSent) "Verify account creation" else "Finish your MHTalk account",
                        color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    )
                    if (!onboardingCodeSent) {
                        Text("Google verified ${authState.email}. Choose how your MHTalk profile will appear.", color = MHTalkMuted, lineHeight = 20.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (authAvatar?.startsWith("data:image/") == true || authAvatar?.startsWith("http") == true) {
                                AsyncImage(authAvatar, "Profile photo", Modifier.size(68.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            } else {
                                Box(Modifier.size(68.dp).clip(CircleShape).background(Color(0xFF5B4ADE)), contentAlignment = Alignment.Center) {
                                    Text(displayName.take(1).ifBlank { "M" }.uppercase(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                                }
                            }
                            Column {
                                TextButton({ authPhotoPicker.launch(arrayOf("image/*")) }) { Text("Choose profile photo") }
                                if (!authAvatar.isNullOrBlank()) TextButton({ authAvatar = null }) { Text("Remove photo") }
                            }
                        }
                        OutlinedTextField(authState.email, {}, Modifier.fillMaxWidth(), readOnly = true, label = { Text("Email") })
                        OutlinedTextField(displayName, { displayName = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true)
                        OutlinedTextField(username, { username = it.filter { char -> char.isLetterOrDigit() || char == '_' }.take(32) }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
                        if (localError.isNotBlank()) Text(localError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        Button(
                            onClick = { perform {
                                requireValidUsername(username)
                                require(displayName.isNotBlank()) { "Enter a display name" }
                                auth.startGoogleOnboarding(); verificationCode = ""; onboardingCodeSent = true; resendSeconds = 60
                            } }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(49.dp),
                        ) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Send account creation code") }
                    } else {
                        Text("Enter the account creation code sent to ${authState.email}.", color = MHTalkMuted, lineHeight = 20.sp)
                        OutlinedTextField(
                            verificationCode, { verificationCode = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(),
                            label = { Text("Account creation code") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        )
                        if (localError.isNotBlank()) Text(localError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        if (notice.isNotBlank()) Text(notice, color = Color(0xFF8EE5BD), fontSize = 12.sp)
                        Button(
                            onClick = { perform { auth.completeGoogleOnboarding(username, displayName, authAvatar, verificationCode) } },
                            enabled = !busy && verificationCode.length >= 6, modifier = Modifier.fillMaxWidth().height(49.dp),
                        ) { Text("Verify and enter MHTalk", fontWeight = FontWeight.Bold) }
                        TextButton(
                            onClick = { perform { auth.startGoogleOnboarding(); resendSeconds = 60; notice = "A new account creation code was sent." } },
                            enabled = !busy && resendSeconds == 0,
                        ) { Text(if (resendSeconds > 0) "Resend in ${resendSeconds}s" else "Resend account creation code") }
                        TextButton({ onboardingCodeSent = false; localError = ""; notice = "" }) { Text("Edit profile details") }
                    }
                    TextButton({ perform { auth.signOut() } }) { Text("Cancel and sign out") }
                } else if (authState is AuthState.AccountExists) {
                    Icon(Icons.Rounded.Info, null, tint = Color(0xFFA99CFF), modifier = Modifier.size(48.dp))
                    Text("Account already exists", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(authState.message, color = MHTalkMuted, lineHeight = 20.sp)
                    Text(authState.email, color = Color.White, fontWeight = FontWeight.Bold)
                    if (localError.isNotBlank()) Text(localError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Button(
                        onClick = { perform {
                            auth.requestPasswordReset(authState.email); auth.dismissAccountNotice()
                            email = authState.email; verificationCode = ""; mode = AuthMode.RecoveryCode
                            notice = "A password setup code was sent."
                        } }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(49.dp),
                    ) { Text(if (authState.passwordEnabled) "Reset password" else "Set a password") }
                    if (authState.googleLinked) Button(
                        onClick = { auth.beginSignIn("google") }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(49.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF222532)),
                    ) { androidx.compose.foundation.Image(painterResource(R.drawable.ic_google_logo), null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("Log in using Google") }
                    TextButton({ auth.dismissAccountNotice(); switchMode(AuthMode.Login) }) { Text("Back to login") }
                } else if (mode == AuthMode.Verification) {
                    Icon(Icons.Rounded.MarkEmailRead, null, tint = Color(0xFFA99CFF), modifier = Modifier.size(48.dp))
                    Text("Verify your email", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Enter the verification code sent to $email.", color = MHTalkMuted, lineHeight = 20.sp)
                    OutlinedTextField(
                        verificationCode, { verificationCode = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(),
                        label = { Text("Verification code") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    )
                    if (localError.isNotBlank()) Text(localError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    if (notice.isNotBlank()) Text(notice, color = Color(0xFF8EE5BD), fontSize = 12.sp)
                    Button(
                        onClick = { perform { auth.verifyEmailCode(email, verificationCode, displayName, authAvatar) } },
                        enabled = !busy && verificationCode.length >= 6, modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("Verify and continue") }
                    Button(
                        onClick = { perform { auth.resendVerification(email); resendSeconds = 60; notice = "A new verification code was sent." } },
                        enabled = !busy && resendSeconds == 0, modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text(if (resendSeconds > 0) "Resend in ${resendSeconds}s" else "Resend verification code") }
                    TextButton(onClick = { identifier = email; switchMode(AuthMode.Forgot) }) { Text("Already use this email? Set a password") }
                    TextButton(onClick = { switchMode(AuthMode.Login) }) { Text("Back to login") }
                } else {
                    Text(
                        when (mode) { AuthMode.Login -> "Welcome back"; AuthMode.Register -> "Create your account"; AuthMode.Forgot -> "Reset your password"; AuthMode.RecoveryCode -> "Enter recovery code"; else -> "Choose a new password" },
                        color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when (mode) { AuthMode.Login -> "Sign in to continue to MHTalk."; AuthMode.Register -> "One account works on phone and PC."; AuthMode.Forgot -> "Enter your username or email and we’ll send a recovery code."; AuthMode.RecoveryCode -> "Enter the code sent to $email."; else -> "Use at least 10 characters for your new password." },
                        color = MHTalkMuted, fontSize = 13.sp,
                    )

                    if (mode == AuthMode.Register) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (authAvatar?.startsWith("data:image/") == true) {
                                AsyncImage(authAvatar, "Profile photo", Modifier.size(62.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            } else {
                                Box(Modifier.size(62.dp).clip(CircleShape).background(Color(0xFF5B4ADE)), contentAlignment = Alignment.Center) {
                                    Text(displayName.take(1).ifBlank { "M" }.uppercase(), color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
                                }
                            }
                            Column {
                                TextButton({ authPhotoPicker.launch(arrayOf("image/*")) }) { Text("Choose profile photo") }
                                if (!authAvatar.isNullOrBlank()) TextButton({ authAvatar = null }) { Text("Remove photo") }
                            }
                        }
                        OutlinedTextField(username, { username = it.filter { char -> char.isLetterOrDigit() || char == '_' }.take(32) }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next))
                        OutlinedTextField(displayName, { displayName = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                    }
                    if (mode == AuthMode.Login || mode == AuthMode.Forgot) {
                        OutlinedTextField(identifier, { identifier = it }, Modifier.fillMaxWidth(), label = { Text("Username or Email") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = if (mode == AuthMode.Forgot) ImeAction.Done else ImeAction.Next))
                    }
                    if (mode == AuthMode.RecoveryCode) {
                        OutlinedTextField(
                            verificationCode, { verificationCode = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(),
                            label = { Text("Recovery code") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        )
                    }
                    if (mode == AuthMode.Login || mode == AuthMode.Register || mode == AuthMode.Reset) {
                        OutlinedTextField(
                            password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { IconButton({ passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (mode == AuthMode.Login) ImeAction.Done else ImeAction.Next),
                        )
                    }
                    if (mode == AuthMode.Register || mode == AuthMode.Reset) {
                        OutlinedTextField(confirmation, { confirmation = it }, Modifier.fillMaxWidth(), label = { Text("Confirm password") }, singleLine = true, visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
                    }
                    if (mode == AuthMode.Login) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton({ switchMode(AuthMode.Register) }, contentPadding = PaddingValues(0.dp)) { Text("Register new account", fontSize = 12.sp) }
                            TextButton({ switchMode(AuthMode.Forgot) }, contentPadding = PaddingValues(0.dp)) { Text("Forgot password?", fontSize = 12.sp) }
                        }
                    }
                    if (mode == AuthMode.Register) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Checkbox(acceptedTerms, { acceptedTerms = it })
                            Column(Modifier.padding(top = 7.dp)) {
                                Text("I agree to the:", color = MHTalkMuted, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mhtalk-token-service.mhlkotalk.workers.dev/terms"))) },
                                        contentPadding = PaddingValues(0.dp),
                                    ) { Text("Terms of Service", fontSize = 12.sp) }
                                    Text("and", color = MHTalkMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 13.dp))
                                    TextButton(
                                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mhtalk-token-service.mhlkotalk.workers.dev/privacy"))) },
                                        contentPadding = PaddingValues(0.dp),
                                    ) { Text("Privacy Policy", fontSize = 12.sp) }
                                }
                            }
                        }
                    }

                    val displayedError = localError.ifBlank { (authState as? AuthState.Failed)?.message.orEmpty() }
                    if (displayedError.isNotBlank()) Text(displayedError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    if (notice.isNotBlank()) Text(notice, color = Color(0xFF8EE5BD), fontSize = 12.sp)
                    if (authState == AuthState.Unavailable) {
                        Text("Account service is unavailable. Check your connection and try again.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                    }

                    Button(
                        enabled = !busy && (mode != AuthMode.RecoveryCode || verificationCode.length >= 6),
                        modifier = Modifier.fillMaxWidth().height(49.dp),
                        onClick = {
                            perform {
                                when (mode) {
                                    AuthMode.Login -> auth.login(identifier, password)
                                    AuthMode.Register -> {
                                        requireValidUsername(username)
                                        requireValidPassword(password)
                                        require(password == confirmation) { "Passwords do not match" }
                                        require(acceptedTerms) { "Accept the Terms and Privacy Policy to continue" }
                                        require(auth.usernameAvailable(username)) { "Username is unavailable" }
                                        auth.register(username, displayName, email, password)
                                    }
                                    AuthMode.Forgot -> {
                                        auth.requestPasswordReset(identifier); email = identifier.trim(); verificationCode = ""
                                        mode = AuthMode.RecoveryCode; notice = "If an account matches this information, a recovery code has been sent."
                                    }
                                    AuthMode.RecoveryCode -> auth.verifyPasswordRecoveryCode(email, verificationCode)
                                    AuthMode.Reset -> {
                                        requireValidPassword(password)
                                        require(password == confirmation) { "Passwords do not match" }
                                        auth.completePasswordRecovery(password)
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        else Text(when (mode) { AuthMode.Login -> "Login"; AuthMode.Register -> "Create account"; AuthMode.Forgot -> "Send recovery code"; AuthMode.RecoveryCode -> "Verify code"; else -> "Save new password" }, fontWeight = FontWeight.Bold)
                    }

                    if (mode == AuthMode.Login) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { HorizontalDivider(Modifier.weight(1f), color = Color(0xFF343A57)); Text("  OR  ", color = MHTalkMuted, fontSize = 10.sp); HorizontalDivider(Modifier.weight(1f), color = Color(0xFF343A57)) }
                        Button(
                            onClick = { auth.beginSignIn("google") }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(49.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF222532)),
                        ) { androidx.compose.foundation.Image(painterResource(R.drawable.ic_google_logo), null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("Log in using Google", fontWeight = FontWeight.Bold) }
                    } else if (mode != AuthMode.Reset) {
                        TextButton({ switchMode(if (mode == AuthMode.RecoveryCode) AuthMode.Forgot else AuthMode.Login) }) { Text(if (mode == AuthMode.RecoveryCode) "Use another email" else "Back to login") }
                    } else {
                        TextButton({ perform { auth.cancelPasswordRecovery() } }) { Text("Cancel") }
                    }
                }
                HorizontalDivider(color = Color(0xFF303650))
                Text(
                    "Protected sign-in · Your password is never stored by MHTalk",
                    color = Color(0xFF858EAC),
                    fontSize = 10.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}
