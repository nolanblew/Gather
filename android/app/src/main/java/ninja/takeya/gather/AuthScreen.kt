@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ninja.takeya.gather

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

@Composable
fun AuthScreen(state: GatherState, model: GatherViewModel) {
    val window = LocalGatherWindow.current
    val fold = window.verticalFold
    if (fold != null && fold.left >= 240.dp && window.width - fold.right >= 300.dp) {
        // Fold coordinates are physical window coordinates, independent of RTL.
        Box(Modifier.fillMaxSize().imePadding().testTag("auth-layout-fold"), contentAlignment = AbsoluteAlignment.TopLeft) {
            Box(Modifier.width(fold.left).fillMaxHeight().padding(24.dp), contentAlignment = Alignment.Center) {
                AuthArtwork(Modifier.fillMaxWidth(), (window.height * .55f).coerceIn(180.dp, 440.dp))
            }
            Box(Modifier.absoluteOffset(x = fold.right).width(window.width - fold.right).fillMaxHeight()) {
                AuthComposition(state, model, allowArtwork = false)
            }
        }
    } else if (window.horizontalFold != null || fold != null) {
        GatherSafeSinglePane { AuthComposition(state, model, allowArtwork = false) }
    } else {
        AuthComposition(state, model)
    }
}

@Composable
private fun AuthComposition(state: GatherState, model: GatherViewModel, allowArtwork: Boolean = true) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("auth-layout")) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val paneWidth = (minOf(maxWidth - 64.dp, 1120.dp) * .55f) - 48.dp
        val wide = allowArtwork && maxWidth >= 720.dp && maxHeight >= 480.dp &&
            paneWidth >= 300.dp * fontScale.coerceAtMost(1.4f)
        // Keep the structural branch stable while the keyboard opens. Only the
        // scroll viewport and decorative artwork respond to the IME inset.
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val viewportHeight = maxHeight
        val showArtwork = allowArtwork && viewportHeight >= 640.dp * fontScale.coerceAtMost(1.3f)
        val artHeight = ((viewportHeight - 500.dp) * .7f + 180.dp).coerceIn(180.dp, 240.dp)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight).padding(horizontal = if (wide) 32.dp else 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (wide) {
                Surface(
                    modifier = Modifier.widthIn(max = 1120.dp).fillMaxWidth().testTag("auth-layout-wide"),
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AuthArtwork(Modifier.weight(.9f), (viewportHeight - 80.dp).coerceIn(320.dp, 500.dp))
                        Box(Modifier.weight(1.1f).padding(24.dp), contentAlignment = Alignment.Center) {
                            AuthForm(state, model, showBrand = true)
                        }
                    }
                }
            } else {
                Brand()
                if (showArtwork) {
                    Spacer(Modifier.height(16.dp))
                    AuthArtwork(Modifier.widthIn(max = 460.dp).fillMaxWidth(), artHeight)
                }
                Spacer(Modifier.height(if (showArtwork) 16.dp else 24.dp))
                Surface(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Box(Modifier.padding(20.dp), contentAlignment = Alignment.Center) {
                        AuthForm(state, model, showBrand = false)
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun AuthArtwork(modifier: Modifier, height: Dp) {
    val surface = MaterialTheme.colorScheme.surface
    val darkFraction = ((.85f - surface.luminance()) / .8f).coerceIn(0f, 1f)
    Image(
        painterResource(R.drawable.gather_welcome), null,
        modifier.height(height).clip(RoundedCornerShape(24.dp)).drawWithContent {
            drawContent()
            if (darkFraction > 0f) drawRect(surface.copy(alpha = .30f * darkFraction))
        }.testTag("auth-artwork"),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun AuthForm(state: GatherState, model: GatherViewModel, showBrand: Boolean) {
    val draft = model.ui.auth
    var register by draft::register
    var username by draft::username
    var password by draft::password
    var visible by draft::visible
    var passwordHelp by draft::passwordHelp
    val passwordFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val submit = {
        if (!state.busy && username.isNotBlank() && password.isNotBlank()) {
            focus.clearFocus()
            model.authenticate(username, password, register)
        }
    }
    Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().testTag("auth-form"), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showBrand) {
                Brand()
                Spacer(Modifier.height(28.dp))
            }
            if (state.inviteToken != null)
                Text(
                    "Sign in or create an account to review your invitation.",
                    Modifier.fillMaxWidth().padding(bottom = 18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            BoxWithConstraints(
                Modifier.fillMaxWidth()
                    .testTag("auth-mode-selector")
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(4.dp)
            ) {
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer()
                val labelStyle = MaterialTheme.typography.labelLarge
                val selectorHeight =
                    with(density) {
                        val labelWidth = (maxWidth / 2 - 24.dp).roundToPx().coerceAtLeast(1)
                        val height =
                            listOf("Sign in", "Create account").maxOf {
                                measurer
                                    .measure(
                                        it,
                                        style = labelStyle,
                                        constraints = Constraints(maxWidth = labelWidth),
                                    )
                                    .size
                                    .height
                            }
                        maxOf(52.dp, height.toDp() + 24.dp)
                    }
                val indicatorOffset by
                    animateDpAsState(
                        if (register) maxWidth / 2 else 0.dp,
                        GatherMotion.standard(),
                        label = "Authentication selector",
                    )
                Box(Modifier.fillMaxWidth().height(selectorHeight)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(14.dp),
                        modifier =
                            Modifier.offset(x = indicatorOffset)
                                .fillMaxWidth(.5f)
                                .fillMaxHeight()
                                .testTag("auth-mode-indicator")
                                .clearAndSetSemantics {},
                    ) {}
                    Row(Modifier.fillMaxSize().selectableGroup()) {
                        listOf(false to "Sign in", true to "Create account").forEach {
                            (value, label) ->
                            val textColor by
                                animateColorAsState(
                                    if (register == value) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    GatherMotion.quick(),
                                    label = "Authentication mode color",
                                )
                            Box(
                                modifier =
                                    Modifier.weight(1f)
                                        .fillMaxHeight()
                                        .testTag(
                                            if (value) "auth-mode-create-account"
                                            else "auth-mode-sign-in"
                                        )
                                        .clip(RoundedCornerShape(14.dp))
                                        .selectable(
                                            selected = register == value,
                                            enabled = !state.busy,
                                            role = Role.Tab,
                                            onClick = {
                                                register = value
                                                model.dismissNotice()
                                            },
                                        ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    color = textColor,
                                    style = MaterialTheme.typography.labelLarge,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                username,
                { username = it.take(80) },
                label = { Text("Username") },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("auth-username"),
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                password,
                { password = it.take(128) },
                label = { Text("Password") },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).testTag("auth-password"),
                shape = RoundedCornerShape(16.dp),
                visualTransformation =
                    if (visible) androidx.compose.ui.text.input.VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Outlined.VisibilityOff
                            else Icons.Outlined.Visibility,
                            if (visible) "Hide password" else "Show password",
                        )
                    }
                },
            )
            AnimatedVisibility(
                visible = register,
                enter =
                    expandVertically(GatherMotion.standard(), expandFrom = Alignment.Top) +
                        fadeIn(GatherMotion.quick()),
                exit =
                    shrinkVertically(GatherMotion.standard(), shrinkTowards = Alignment.Top) +
                        fadeOut(GatherMotion.quick()),
                label = "Password requirements",
            ) {
                Text(
                    "At least 6 characters using two of: uppercase, lowercase, numbers, or symbols.",
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Notice(state, model)
            if (model.canRetrySession())
                TextButton(onClick = model::refresh) { Text("Retry connection") }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = submit,
                enabled = !state.busy && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("auth-submit"),
                shape = RoundedCornerShape(16.dp),
            ) {
                AnimatedContent(
                    targetState = state.busy to register,
                    transitionSpec = { gatherFade() },
                    label = "Authentication action",
                ) { (busy, creating) ->
                    if (busy)
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    else Text(if (creating) "Create account" else "Sign in")
                }
            }
            TextButton(onClick = { passwordHelp = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Forgot password?")
            }
    }
    if (passwordHelp)
        GatherAlertDialog(
            onDismissRequest = { passwordHelp = false },
            title = { Text("Reset password") },
            text = { Text("Contact your server administrator to reset your password.") },
            confirmButton = { TextButton(onClick = { passwordHelp = false }) { Text("Done") } },
        )
}

