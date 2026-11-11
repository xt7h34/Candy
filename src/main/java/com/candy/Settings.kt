package com.candy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/** What you chose in Accessibility (the look and the language). Kept on this phone. */
object AppPrefs {
    private const val FILE = "candy_app_prefs"

    /** "system", "light" or "dark". */
    var theme by mutableStateOf("system")
        private set

    /** "system", "ar" or "en". */
    var language by mutableStateOf("system")
        private set

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun init(c: Context) {
        theme = sp(c).getString("theme", "system") ?: "system"
        language = sp(c).getString("language", "system") ?: "system"
    }

    fun setTheme(c: Context, v: String) {
        theme = v
        sp(c).edit().putString("theme", v).apply()
    }

    fun setLanguage(c: Context, v: String) {
        language = v
        sp(c).edit().putString("language", v).apply()
    }

    /** Gives the activity the saved language before anything is drawn. */
    fun wrap(base: Context): Context {
        val lang = base.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("language", "system") ?: "system"
        if (lang != "ar" && lang != "en") return base
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }
}

private fun Context.activityOrNull(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// ───────────────────────── building blocks ─────────────────────────

/** A small arrow that points to the end of the row, and flips in right-to-left languages. */
@Composable
private fun Chevron() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val a = if (rtl) w * 0.68f else w * 0.32f
        val b = if (rtl) w * 0.32f else w * 0.68f
        val p = Path().apply {
            moveTo(a, h * 0.2f)
            lineTo(b, h * 0.5f)
            lineTo(a, h * 0.8f)
        }
        drawPath(p, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** One row of a settings list: an icon, a title, an optional line under it, and an arrow. */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    emoji: String? = null,
    icon: (@Composable () -> Unit)? = null,
    arrow: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            if (icon != null) icon() else if (emoji != null) Text(emoji, fontSize = 22.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) Muted(subtitle)
        }
        if (arrow) Chevron()
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** The thick line between groups, like in the Instagram settings. */
@Composable
private fun GroupDivider() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh))
}

@Composable
private fun KanadeLogo(size: androidx.compose.ui.unit.Dp = 28.dp) {
    Image(painterResource(R.drawable.kanade_account_logo), contentDescription = null, modifier = Modifier.size(size))
}

// ───────────────────────── Settings and activity ─────────────────────────

/** The page the three lines on your profile open. */
@Composable
fun SettingsScreen(
    net: Net,
    onBack: () -> Unit,
    onKanadeAccount: () -> Unit,
    onMemories: () -> Unit,
    onBubbles: () -> Unit,
    onAccessibility: () -> Unit,
    onSignOut: () -> Unit,
) {
    var messages by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.set_title), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            GroupLabel(stringResource(R.string.set_your_account))
            SettingsRow(
                title = stringResource(R.string.set_kanade_account),
                subtitle = stringResource(R.string.set_kanade_account_sub),
                icon = { KanadeLogo() },
                onClick = onKanadeAccount,
            )
            Muted(stringResource(R.string.set_kanade_account_note), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            GroupDivider()

            GroupLabel(stringResource(R.string.set_how_use))
            SettingsRow(stringResource(R.string.mem_title), emoji = "🗂️", onClick = onMemories)
            SettingsRow(stringResource(R.string.set_messages), emoji = "💬", onClick = { messages = true })
            SettingsRow(stringResource(R.string.prof_bubbles), emoji = "🫧", onClick = onBubbles)
            GroupDivider()

            GroupLabel(stringResource(R.string.set_more))
            SettingsRow(stringResource(R.string.set_accessibility), emoji = "🎨", onClick = onAccessibility)
            SettingsRow(stringResource(R.string.prof_signout), emoji = "🚪", arrow = false, danger = true, onClick = { confirmOut = true })
            Box(Modifier.height(LocalBarInset.current + 16.dp))
        }
    }

    if (messages) MessageSettingsDialog(net) { messages = false }
    if (confirmOut) {
        AlertDialog(
            onDismissRequest = { confirmOut = false },
            title = { Text(stringResource(R.string.prof_signout)) },
            text = { Text(stringResource(R.string.set_signout_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmOut = false; onSignOut() }) { Text(stringResource(R.string.prof_signout)) }
            },
            dismissButton = { TextButton(onClick = { confirmOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ───────────────────────── Kanade Account ─────────────────────────

@Composable
fun KanadeAccountScreen(onBack: () -> Unit, onSubscriptions: () -> Unit, onManage: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.set_kanade_account), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { KanadeLogo(72.dp) }
            SettingsRow(stringResource(R.string.set_subscriptions), emoji = "💎", onClick = onSubscriptions)
            SettingsRow(stringResource(R.string.set_manage_account), emoji = "⚙️", onClick = onManage)
            Box(Modifier.height(LocalBarInset.current + 16.dp))
        }
    }
}

// ───────────────────────── Manage Account ─────────────────────────

/** What used to sit at the bottom of the profile: visitors, growth, join date and location. */
@Composable
fun ManageAccountScreen(net: Net, onBack: () -> Unit, onVisits: () -> Unit, onGrowth: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<JSONObject?>(null) }
    var reload by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(reload) {
        val r = net.get("/candy/me")
        if (r.ok) me = r.body else error = r.message(ctx)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.set_manage_account), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.padding(horizontal = 16.dp)) { ErrorText(error) }
            SettingsRow(stringResource(R.string.prof_visits), emoji = "👀", onClick = onVisits)
            SettingsRow(stringResource(R.string.prof_growth), emoji = "📈", onClick = onGrowth)
            GroupDivider()
            val data = me
            if (data != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.prof_show_join), Modifier.weight(1f))
                    Switch(
                        checked = data.optBoolean("showJoinDate", true),
                        onCheckedChange = { on ->
                            scope.launch {
                                val r = net.post("/candy/joindate", JSONObject().put("visible", on))
                                if (r.ok) reload++ else error = r.message(ctx)
                            }
                        },
                    )
                }
                val loc = data.optJSONObject("location")
                if (loc != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.prof_show_location, loc.optString("text")), Modifier.weight(1f))
                        Switch(
                            checked = loc.optBoolean("visible", true),
                            onCheckedChange = { on ->
                                scope.launch {
                                    val r = net.post("/candy/location/visibility", JSONObject().put("visible", on))
                                    if (r.ok) reload++ else error = r.message(ctx)
                                }
                            },
                        )
                    }
                } else {
                    Muted(stringResource(R.string.prof_location_hint), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
            Box(Modifier.height(LocalBarInset.current + 16.dp))
        }
    }
}

// ───────────────────────── Accessibility ─────────────────────────

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Light or dark, and Arabic or English. */
@Composable
fun AccessibilityScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.set_accessibility), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            GroupLabel(stringResource(R.string.set_theme))
            ChoiceRow(stringResource(R.string.set_theme_system), AppPrefs.theme == "system") { AppPrefs.setTheme(ctx, "system") }
            ChoiceRow(stringResource(R.string.set_theme_light), AppPrefs.theme == "light") { AppPrefs.setTheme(ctx, "light") }
            ChoiceRow(stringResource(R.string.set_theme_dark), AppPrefs.theme == "dark") { AppPrefs.setTheme(ctx, "dark") }
            GroupDivider()
            GroupLabel(stringResource(R.string.set_language))
            fun pick(v: String) {
                if (AppPrefs.language == v) return
                AppPrefs.setLanguage(ctx, v)
                // the screen is rebuilt in the new language
                ctx.activityOrNull()?.recreate()
            }
            ChoiceRow(stringResource(R.string.set_lang_system), AppPrefs.language == "system") { pick("system") }
            ChoiceRow(stringResource(R.string.set_lang_ar), AppPrefs.language == "ar") { pick("ar") }
            ChoiceRow(stringResource(R.string.set_lang_en), AppPrefs.language == "en") { pick("en") }
            Box(Modifier.height(LocalBarInset.current + 16.dp))
        }
    }
}

/** The three lines on your profile. They open Settings and activity. */
@Composable
fun SettingsMenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val desc = stringResource(R.string.set_open)
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = Color.Black.copy(alpha = 0.35f),
        modifier = modifier.size(42.dp).androidSemantics(desc),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(20.dp)) {
                val w = 2.2.dp.toPx()
                for (i in 0..2) {
                    val y = size.height * (0.2f + 0.3f * i)
                    drawLine(Color.White, Offset(0f, y), Offset(size.width, y), strokeWidth = w, cap = StrokeCap.Round)
                }
            }
        }
    }
}

private fun Modifier.androidSemantics(desc: String): Modifier =
    this.then(Modifier.semantics { contentDescription = desc })
