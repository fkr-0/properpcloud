package dev.properpcloud.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.properpcloud.app.BuildConfig
import dev.properpcloud.app.auth.PCloudOAuthConfiguration
import dev.properpcloud.source.pcloud.PCloudAccountRegion

@Composable
internal fun PCloudAccountSettings(
    state: AppUiState,
    actions: AppActions,
    onAuthorizePCloud: (String) -> Unit,
) {
    val oauth = PCloudOAuthConfiguration.resolve(BuildConfig.PCLOUD_CLIENT_ID, state.clientId)
    var showDirectLogin by remember(oauth.isConfigured) { mutableStateOf(!oauth.isConfigured) }
    var email by remember { mutableStateOf("") }
    var secretText by remember { mutableStateOf("") }
    var revealSecret by remember { mutableStateOf(false) }
    var region by remember { mutableStateOf(PCloudAccountRegion.EUROPE) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "pCloud account",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (state.pCloudConnected) {
            ConnectedPCloudCard(actions.disconnectPCloud)
        } else {
            OAuthSignInCard(
                state = state,
                oauth = oauth,
                onAuthorizePCloud = onAuthorizePCloud,
            )
            DirectLoginCard(
                loginInProgress = state.pCloudLoginInProgress,
                expanded = showDirectLogin,
                onExpandedChange = { showDirectLogin = it },
                email = email,
                onEmailChange = { email = it },
                secretText = secretText,
                onSecretTextChange = { secretText = it },
                revealSecret = revealSecret,
                onRevealSecretChange = { revealSecret = it },
                region = region,
                onRegionChange = { region = it },
                onSubmit = {
                    val oneRequestSecret = secretText.toCharArray()
                    secretText = ""
                    revealSecret = false
                    actions.signInWithPCloudPassword(email, oneRequestSecret, region)
                },
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun ConnectedPCloudCard(onDisconnect: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f)) {
                Text("Connected to pCloud", fontWeight = FontWeight.SemiBold)
                Text(
                    "Ready to browse and play your library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            TextButton(onClick = onDisconnect) {
                Icon(Icons.AutoMirrored.Filled.Logout, null)
                Spacer(Modifier.width(4.dp))
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun OAuthSignInCard(
    state: AppUiState,
    oauth: PCloudOAuthConfiguration,
    onAuthorizePCloud: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AccountMethodHeader(
                icon = { Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary) },
                title = "Recommended: pCloud OAuth",
                subtitle = "Your password stays on pCloud's authorization page.",
            )
            if (oauth.isConfigured) {
                Button(
                    onClick = { onAuthorizePCloud(oauth.clientId) },
                    enabled = !state.pCloudLoginInProgress,
                    modifier = Modifier.testTag("connect-pcloud"),
                ) {
                    Icon(Icons.Default.Security, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Continue with pCloud OAuth")
                }
            } else {
                Text(
                    "OAuth sign-in is unavailable in this build. Use email and password below.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun DirectLoginCard(
    loginInProgress: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    secretText: String,
    onSecretTextChange: (String) -> Unit,
    revealSecret: Boolean,
    onRevealSecretChange: (Boolean) -> Unit,
    region: PCloudAccountRegion,
    onRegionChange: (PCloudAccountRegion) -> Unit,
    onSubmit: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("direct-login-card"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AccountMethodHeader(
                icon = { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.tertiary) },
                title = "Sign in with email and password",
                subtitle = "Use this when OAuth is unavailable.",
            )
            Text(
                "Your password is sent once to the selected pCloud region and is not saved. " +
                    "Accounts that use two-factor authentication or social sign-in may need OAuth instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier.testTag("toggle-direct-login"),
            ) {
                Text(if (expanded) "Hide email sign-in" else "Use email and password")
            }
            if (expanded) {
                DirectLoginForm(
                    loginInProgress = loginInProgress,
                    email = email,
                    onEmailChange = onEmailChange,
                    secretText = secretText,
                    onSecretTextChange = onSecretTextChange,
                    revealSecret = revealSecret,
                    onRevealSecretChange = onRevealSecretChange,
                    region = region,
                    onRegionChange = onRegionChange,
                    onSubmit = onSubmit,
                )
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun DirectLoginForm(
    loginInProgress: Boolean,
    email: String,
    onEmailChange: (String) -> Unit,
    secretText: String,
    onSecretTextChange: (String) -> Unit,
    revealSecret: Boolean,
    onRevealSecretChange: (Boolean) -> Unit,
    region: PCloudAccountRegion,
    onRegionChange: (PCloudAccountRegion) -> Unit,
    onSubmit: () -> Unit,
) {
    Text("Account region", style = MaterialTheme.typography.labelLarge)
    Text(
        "Choose where the account was originally created. A generic login failure does not identify which credential or regional selection was wrong.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PCloudAccountRegion.entries.forEach { option ->
            if (region == option) {
                Button(
                    onClick = { onRegionChange(option) },
                    enabled = !loginInProgress,
                ) { Text(option.displayName) }
            } else {
                OutlinedButton(
                    onClick = { onRegionChange(option) },
                    enabled = !loginInProgress,
                ) { Text(option.displayName) }
            }
        }
    }
    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text("pCloud email") },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
        singleLine = true,
        enabled = !loginInProgress,
        modifier = Modifier.fillMaxWidth().testTag("direct-login-email"),
    )
    OutlinedTextField(
        value = secretText,
        onValueChange = onSecretTextChange,
        label = { Text("pCloud password") },
        visualTransformation = if (revealSecret) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        trailingIcon = {
            IconButton(onClick = { onRevealSecretChange(!revealSecret) }) {
                Icon(
                    if (revealSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    if (revealSecret) "Hide password" else "Show password",
                )
            }
        },
        singleLine = true,
        enabled = !loginInProgress,
        modifier = Modifier.fillMaxWidth().testTag("direct-login-password"),
    )
    Button(
        onClick = onSubmit,
        enabled = email.isNotBlank() && secretText.isNotEmpty() && !loginInProgress,
        modifier = Modifier.testTag("direct-login-submit"),
    ) {
        if (loginInProgress) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Lock, null)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (loginInProgress) "Signing in…" else "Sign in directly")
    }
}

@Composable
private fun AccountMethodHeader(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
