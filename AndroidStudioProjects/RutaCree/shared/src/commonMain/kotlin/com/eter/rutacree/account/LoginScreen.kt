package com.eter.rutacree.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.NeuButton
import com.eter.rutacree.ui.NeuTextField
import com.eter.rutacree.ui.PillShape
import com.eter.rutacree.ui.StatusBanner
import com.eter.rutacree.ui.neuCard
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_demo_driver
import rutacree.shared.generated.resources.action_demo_rider
import rutacree.shared.generated.resources.action_register
import rutacree.shared.generated.resources.action_signin
import rutacree.shared.generated.resources.action_skip
import rutacree.shared.generated.resources.action_switch_to_register
import rutacree.shared.generated.resources.action_switch_to_signin
import rutacree.shared.generated.resources.app_name
import rutacree.shared.generated.resources.err_email_taken
import rutacree.shared.generated.resources.err_invalid_credentials
import rutacree.shared.generated.resources.err_invalid_email
import rutacree.shared.generated.resources.err_locked
import rutacree.shared.generated.resources.err_missing_name
import rutacree.shared.generated.resources.err_network
import rutacree.shared.generated.resources.err_rate_limited
import rutacree.shared.generated.resources.err_session_expired
import rutacree.shared.generated.resources.err_unknown
import rutacree.shared.generated.resources.err_weak_password
import rutacree.shared.generated.resources.field_email
import rutacree.shared.generated.resources.field_email_hint
import rutacree.shared.generated.resources.field_name
import rutacree.shared.generated.resources.field_name_hint
import rutacree.shared.generated.resources.field_password
import rutacree.shared.generated.resources.field_password_hint
import rutacree.shared.generated.resources.field_password_new_hint
import rutacree.shared.generated.resources.login_busy
import rutacree.shared.generated.resources.login_demo_driver
import rutacree.shared.generated.resources.login_demo_rider
import rutacree.shared.generated.resources.login_demo_title
import rutacree.shared.generated.resources.login_register_subtitle
import rutacree.shared.generated.resources.login_register_title
import rutacree.shared.generated.resources.login_signin_subtitle
import rutacree.shared.generated.resources.login_skip
import rutacree.shared.generated.resources.login_skip_hint
import rutacree.shared.generated.resources.login_submit_register
import rutacree.shared.generated.resources.login_submit_signin
import rutacree.shared.generated.resources.login_switch_to_register
import rutacree.shared.generated.resources.login_switch_to_signin

@Composable
fun accountErrorText(error: AccountError): String = stringResource(
    when (error) {
        AccountError.InvalidCredentials -> Res.string.err_invalid_credentials
        AccountError.Locked -> Res.string.err_locked
        AccountError.RateLimited -> Res.string.err_rate_limited
        AccountError.EmailTaken -> Res.string.err_email_taken
        AccountError.WeakPassword -> Res.string.err_weak_password
        AccountError.InvalidEmail -> Res.string.err_invalid_email
        AccountError.MissingName -> Res.string.err_missing_name
        AccountError.Network -> Res.string.err_network
        AccountError.SessionExpired -> Res.string.err_session_expired
        AccountError.Unknown -> Res.string.err_unknown
    }
)

/**
 * The single entrance. Drivers and riders type the same two fields; the server answers with the
 * role and the app opens the matching screens. Only riders can create an account here (driver
 * accounts are issued by the operator), and riders may also go on without one.
 */
@Composable
fun LoginScreen(
    state: AccountUiState,
    onSignIn: (email: String, password: String) -> Unit,
    onRegister: (name: String, email: String, password: String) -> Unit,
    onClearError: () -> Unit,
    onContinueAsGuest: () -> Unit,
    onDemoDriver: () -> Unit,
) {
    var registering by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    // Not saveable on purpose: the password never goes into the saved-state bundle.
    var password by remember { mutableStateOf("") }

    val canSubmit = !state.busy && email.isNotBlank() && password.isNotEmpty() && (!registering || name.isNotBlank())
    val submit = {
        if (canSubmit) {
            if (registering) onRegister(name, email, password) else onSignIn(email, password)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.size(88.dp).neuCard(CircleShape, 8.dp), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Bus, null, tint = Neu.Mint, modifier = Modifier.size(46.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (registering) Res.string.login_register_title else Res.string.app_name),
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Neu.Text,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(if (registering) Res.string.login_register_subtitle else Res.string.login_signin_subtitle),
            fontSize = 16.sp,
            color = Neu.TextMuted,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(28.dp))

        if (!state.available) {
            DemoEntries(onContinueAsGuest, onDemoDriver)
            return@Column
        }

        Column(Modifier.fillMaxWidth()) {
            if (registering) {
                NeuTextField(
                    name, { name = it; onClearError() },
                    stringResource(Res.string.field_name), stringResource(Res.string.field_name_hint),
                    imeAction = ImeAction.Next,
                )
                Spacer(Modifier.height(20.dp))
            }
            NeuTextField(
                email, { email = it.trim(); onClearError() },
                stringResource(Res.string.field_email), stringResource(Res.string.field_email_hint),
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            )
            Spacer(Modifier.height(20.dp))
            NeuTextField(
                password, { password = it; onClearError() },
                stringResource(Res.string.field_password),
                stringResource(if (registering) Res.string.field_password_new_hint else Res.string.field_password_hint),
                keyboardType = KeyboardType.Password,
                secret = true,
                imeAction = ImeAction.Done,
                onImeAction = submit,
            )
            state.error?.let { StatusBanner(accountErrorText(it), Modifier.padding(top = 16.dp)) }
            Spacer(Modifier.height(24.dp))
            NeuButton(
                onClick = submit,
                actionLabel = stringResource(if (registering) Res.string.action_register else Res.string.action_signin),
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                color = Neu.Blue,
                enabled = canSubmit,
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Neu.palette.onBlue, strokeWidth = 3.dp)
                    Spacer(Modifier.size(10.dp))
                }
                Text(
                    stringResource(
                        when {
                            state.busy -> Res.string.login_busy
                            registering -> Res.string.login_submit_register
                            else -> Res.string.login_submit_signin
                        }
                    ),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Neu.palette.onBlue,
                )
            }
            Spacer(Modifier.height(12.dp))
            // Switching modes is a secondary, text-like action so it never competes with "Sign in".
            NeuButton(
                onClick = { registering = !registering; onClearError() },
                actionLabel = stringResource(
                    if (registering) Res.string.action_switch_to_signin else Res.string.action_switch_to_register
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = PillShape,
            ) {
                Text(
                    stringResource(if (registering) Res.string.login_switch_to_signin else Res.string.login_switch_to_register),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Neu.BlueStrong,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(Res.string.login_skip_hint),
            fontSize = 14.sp,
            color = Neu.TextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        NeuButton(
            onClick = onContinueAsGuest,
            actionLabel = stringResource(Res.string.action_skip),
            modifier = Modifier.fillMaxWidth(),
            shape = PillShape,
        ) {
            Text(stringResource(Res.string.login_skip), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Neu.Text)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Simulation mode (no server): accounts don't exist, so both sides of the app can be explored directly. */
@Composable
private fun DemoEntries(onRider: () -> Unit, onDriver: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StatusBanner(stringResource(Res.string.login_demo_title))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NeuButton(
                onClick = onRider,
                actionLabel = stringResource(Res.string.action_demo_rider),
                modifier = Modifier.weight(1f).heightIn(min = 64.dp),
            ) {
                Text(stringResource(Res.string.login_demo_rider), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Neu.BlueStrong)
            }
            NeuButton(
                onClick = onDriver,
                actionLabel = stringResource(Res.string.action_demo_driver),
                modifier = Modifier.weight(1f).heightIn(min = 64.dp),
            ) {
                Text(stringResource(Res.string.login_demo_driver), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Neu.BlueStrong)
            }
        }
    }
}
