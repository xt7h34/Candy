package com.candy

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

// ───────────────────────── Subscriptions ─────────────────────────
// The Kanade Pass plans (Plus / Pro / Elite) bought with Nero. This is the same page as in the Kanade app:
// the prices, the discount and the rules all come from the bot, so buying here is the same as
// .باقه_اشتري / .باقه_رقي in WhatsApp.

private val SUB_TIERS = listOf("plus", "pro", "elite")

private enum class SubMode { BUY, RENEW, UPGRADE, BLOCKED }

private class SubAction(val mode: SubMode, val tier: String, val unit: String, val value: Int, val price: Long)

private fun subRank(tier: String): Int = SUB_TIERS.indexOf(tier)

private fun JSONArray?.subObjects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun subNum(n: Long): String = NumberFormat.getInstance().format(n)

/** "2d 5h" or "5h 20m" for what is left of a plan. */
private fun subTimeLeft(c: Context, ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0L)
    val d = (minutes / 1440L).toInt()
    val h = ((minutes % 1440L) / 60L).toInt()
    val m = (minutes % 60L).toInt()
    return if (d > 0) c.getString(R.string.sub_left_dh, d, h) else c.getString(R.string.sub_left_hm, h, m)
}

@Composable
private fun subTierName(tier: String, fallback: String): String = when (tier) {
    "plus" -> stringResource(R.string.sub_tier_plus)
    "pro" -> stringResource(R.string.sub_tier_pro)
    "elite" -> stringResource(R.string.sub_tier_elite)
    else -> fallback
}

@Composable
private fun subDuration(unit: String, value: Int): String = when (unit) {
    "hours" -> stringResource(R.string.sub_dur_hours, value)
    "days" -> stringResource(R.string.sub_dur_days, value)
    "weeks" -> stringResource(R.string.sub_dur_weeks, value)
    else -> value.toString()
}

private fun subKey(o: JSONObject): String = o.optString("unit") + ":" + o.optInt("value")

@Composable
private fun SubSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

/** The Subscriptions page: your plan, the plans to buy or renew, and auto-renew. */
@Composable
fun SubscriptionScreen(net: Net, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var selTier by remember { mutableStateOf("") }
    var selKey by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<SubAction?>(null) }

    val msgBought = stringResource(R.string.sub_done_buy)
    val msgUpgraded = stringResource(R.string.sub_done_upgrade)
    val msgAutoOn = stringResource(R.string.sub_done_auto_on)
    val msgAutoOff = stringResource(R.string.sub_done_auto_off)

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    LaunchedEffect(Unit) {
        val r = net.get("/me/pass")
        loading = false
        if (r.ok) data = r.body else error = r.message(ctx)
    }

    // One call to the bot. It answers with the fresh plan state, so the page updates itself.
    suspend fun send(path: String, body: JSONObject, okNotice: String) {
        busy = true
        error = ""
        notice = ""
        val r = net.post(path, body)
        busy = false
        val fresh = r.body?.optJSONObject("pass")
        if (fresh != null) data = fresh
        if (r.ok) notice = okNotice else error = r.message(ctx)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.sub_title), onBack)
        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ErrorText(error)
            if (notice.isNotBlank()) {
                Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            val d = data
            if (d != null) {
                SubscriptionBody(
                    d = d,
                    now = now,
                    busy = busy,
                    selTier = selTier,
                    selKey = selKey,
                    onTier = {
                        selTier = it
                        selKey = ""
                    },
                    onKey = { selKey = it },
                    onAsk = { confirm = it },
                    onAuto = { on, unit, value ->
                        scope.launch {
                            send(
                                "/me/pass/autorenew",
                                JSONObject().put("enabled", on).put("unit", unit).put("value", value),
                                if (on) msgAutoOn else msgAutoOff,
                            )
                        }
                    },
                )
            }
            Box(Modifier.height(LocalBarInset.current + 16.dp))
        }
    }

    val ask = confirm
    if (ask != null) {
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.sub_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val tierJson = data?.optJSONArray("tiers").subObjects().firstOrNull { it.optString("tier") == ask.tier }
                    val name = subTierName(ask.tier, tierJson?.optString("name").orEmpty())
                    val emoji = tierJson?.optString("emoji").orEmpty()
                    Text("$emoji $name", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (ask.mode != SubMode.UPGRADE) {
                        Text(subDuration(ask.unit, ask.value))
                    }
                    Text(stringResource(R.string.sub_price_nero, subNum(ask.price)))
                    if (ask.mode == SubMode.UPGRADE) {
                        Text(
                            stringResource(R.string.sub_upgrade_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        if (ask.mode == SubMode.UPGRADE) {
                            send("/me/pass/upgrade", JSONObject().put("tier", ask.tier), msgUpgraded)
                        } else {
                            send(
                                "/me/pass/buy",
                                JSONObject().put("tier", ask.tier).put("unit", ask.unit).put("value", ask.value),
                                msgBought,
                            )
                        }
                    }
                }) { Text(stringResource(R.string.sub_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubscriptionBody(
    d: JSONObject,
    now: Long,
    busy: Boolean,
    selTier: String,
    selKey: String,
    onTier: (String) -> Unit,
    onKey: (String) -> Unit,
    onAsk: (SubAction) -> Unit,
    onAuto: (Boolean, String, Int) -> Unit,
) {
    val ctx = LocalContext.current
    val active = d.optBoolean("active")
    val curTier = if (active) d.optString("tier") else ""
    val balance = d.optLong("balance")
    val tiers = d.optJSONArray("tiers").subObjects()
    val tier = when {
        selTier.isNotEmpty() && tiers.any { it.optString("tier") == selTier } -> selTier
        curTier.isNotEmpty() -> curTier
        else -> "pro"
    }
    val tierJson = tiers.firstOrNull { it.optString("tier") == tier }
    val options = d.optJSONObject("options")?.optJSONArray(tier).subObjects()
    val sel = options.firstOrNull { subKey(it) == selKey }
        ?: options.firstOrNull { it.optString("unit") == "days" }
        ?: options.firstOrNull()

    // ── Your plan + wallet ──
    SubSection(stringResource(R.string.sub_your_plan)) {
        if (active) {
            val expires = d.optLong("expiresAt")
            Text(
                d.optString("emoji") + " " + subTierName(curTier, d.optString("name")),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.pass_left, subTimeLeft(ctx, expires - now)))
            Text(
                stringResource(
                    R.string.pass_until,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(expires)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(stringResource(R.string.pass_none))
        }
        Text(
            stringResource(R.string.sub_balance, subNum(balance)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // ── Upgrade: only when a plan is running and a higher one exists ──
    val upgrades = d.optJSONArray("upgrades").subObjects()
    if (active && upgrades.isNotEmpty()) {
        SubSection(stringResource(R.string.sub_upgrade_title)) {
            Text(stringResource(R.string.sub_upgrade_note), style = MaterialTheme.typography.bodyMedium)
            for (u in upgrades) {
                val uTier = u.optString("tier")
                val uPrice = u.optLong("price")
                val uJson = tiers.firstOrNull { it.optString("tier") == uTier }
                OutlinedButton(
                    onClick = { onAsk(SubAction(SubMode.UPGRADE, uTier, "", 0, uPrice)) },
                    enabled = !busy && balance >= uPrice,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.sub_upgrade_to,
                            uJson?.optString("emoji").orEmpty() + " " + subTierName(uTier, uJson?.optString("name").orEmpty()),
                            subNum(uPrice),
                        ),
                    )
                }
            }
        }
    }

    // ── The plans: a carousel. The centered card is the chosen one, the others peek from the sides ──
    val pageW = 140
    val pagerState = rememberPagerState(initialPage = tiers.indexOfFirst { it.optString("tier") == tier }.coerceAtLeast(0)) { tiers.size }
    val pagerScope = rememberCoroutineScope()
    LaunchedEffect(pagerState.currentPage) {
        val key = tiers.getOrNull(pagerState.currentPage)?.optString("tier").orEmpty()
        if (key.isNotEmpty() && key != tier) onTier(key)
    }
    val sidePad = ((LocalConfiguration.current.screenWidthDp - 72 - pageW) / 2).coerceAtLeast(0)
    SubSection(stringResource(R.string.sub_choose_plan)) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = sidePad.dp),
            pageSize = PageSize.Fixed(pageW.dp),
            pageSpacing = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val t = tiers[page]
            val key = t.optString("tier")
            val first = d.optJSONObject("options")?.optJSONArray(key).subObjects().firstOrNull()
            SubTierCard(
                emoji = t.optString("emoji"),
                name = subTierName(key, t.optString("name")),
                from = first?.let { stringResource(R.string.sub_from, subNum(it.optLong("price"))) }.orEmpty(),
                badge = when {
                    key == curTier -> stringResource(R.string.sub_badge_current)
                    key == "pro" -> stringResource(R.string.sub_badge_popular)
                    key == "elite" -> stringResource(R.string.sub_badge_top)
                    else -> ""
                },
                selected = pagerState.currentPage == page,
                modifier = Modifier.fillMaxWidth(),
                onClick = { pagerScope.launch { pagerState.animateScrollToPage(page) } },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in tiers.indices) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (pagerState.currentPage == i) 9.dp else 7.dp)
                        .clip(CircleShape)
                        .background(
                            if (pagerState.currentPage == i) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }

        if (tierJson != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SubPerk(stringResource(R.string.sub_perk_xp, tierJson.optInt("xpBonusPct")))
                SubPerk(stringResource(R.string.sub_perk_missions, tierJson.optInt("dailyMissions")))
                SubPerk(stringResource(R.string.sub_perk_discount, tierJson.optInt("storeDiscountPct")))
                SubPerk(stringResource(R.string.sub_perk_bill, tierJson.optDouble("billMultiplier", 1.0).toString().removeSuffix(".0")))
                if (tierJson.optInt("extraAttempts") > 0) {
                    SubPerk(stringResource(R.string.sub_perk_attempts, tierJson.optInt("extraAttempts")))
                }
                val boost = tierJson.optDouble("boostMultiplier", 0.0)
                if (boost > 0.0) {
                    val times = boost.toString().removeSuffix(".0")
                    if (tierJson.optBoolean("boostAll")) {
                        SubPerk(stringResource(R.string.sub_perk_boost_all, times))
                    } else {
                        SubPerk(stringResource(R.string.sub_perk_boost, tierJson.optInt("boostCommands"), times))
                    }
                }
            }
        }
    }

    // ── Duration + price + Continue ──
    val upgradePrice = d.optJSONArray("upgrades").subObjects()
        .firstOrNull { it.optString("tier") == tier }?.optLong("price")
    val mode = when {
        !active -> SubMode.BUY
        tier == curTier -> SubMode.RENEW
        subRank(tier) > subRank(curTier) -> SubMode.UPGRADE
        else -> SubMode.BLOCKED
    }
    val price = if (mode == SubMode.UPGRADE) (upgradePrice ?: 0L) else (sel?.optLong("price") ?: 0L)
    val enough = balance >= price

    SubSection(stringResource(if (mode == SubMode.UPGRADE) R.string.sub_upgrade_title else R.string.sub_duration)) {
        if (mode == SubMode.UPGRADE) {
            Text(stringResource(R.string.sub_upgrade_note), style = MaterialTheme.typography.bodyMedium)
        } else if (mode == SubMode.BLOCKED) {
            Text(stringResource(R.string.sub_blocked), style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (o in options) {
                    FilterChip(
                        selected = sel != null && subKey(o) == subKey(sel),
                        onClick = { onKey(subKey(o)) },
                        label = { Text(subDuration(o.optString("unit"), o.optInt("value"))) },
                    )
                }
            }
        }

        if (mode != SubMode.BLOCKED) {
            Text(
                stringResource(R.string.sub_price_nero, subNum(price)),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (!enough) {
                Text(
                    stringResource(R.string.sub_not_enough, subNum(balance)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Button(
            onClick = {
                if (sel != null || mode == SubMode.UPGRADE) {
                    onAsk(SubAction(mode, tier, sel?.optString("unit").orEmpty(), sel?.optInt("value") ?: 0, price))
                }
            },
            enabled = !busy && enough && mode != SubMode.BLOCKED && (sel != null || mode == SubMode.UPGRADE),
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                stringResource(
                    when (mode) {
                        SubMode.RENEW -> R.string.sub_renew
                        SubMode.UPGRADE -> R.string.sub_upgrade
                        else -> R.string.sub_continue
                    },
                ),
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            stringResource(R.string.sub_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        val loyalty = d.optJSONObject("loyalty")
        if (loyalty != null && !loyalty.optBoolean("rewarded") && loyalty.optInt("renewals") > 0) {
            Text(
                stringResource(R.string.sub_loyalty, loyalty.optInt("renewals"), loyalty.optInt("needed", 3)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    // ── Auto-renew ──
    val auto = d.optJSONObject("autoRenew")
    val autoOn = auto?.optBoolean("enabled") ?: false
    SubSection(stringResource(R.string.sub_auto_title)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sub_auto_switch), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.sub_auto_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoOn,
                enabled = !busy && (autoOn || (active && sel != null && mode != SubMode.UPGRADE && mode != SubMode.BLOCKED)),
                onCheckedChange = { on ->
                    if (!on) {
                        onAuto(false, auto?.optString("unit").orEmpty(), auto?.optInt("value") ?: 1)
                    } else if (sel != null) {
                        onAuto(true, sel.optString("unit"), sel.optInt("value"))
                    }
                },
            )
        }
        if (autoOn && auto != null) {
            val aUnit = auto.optString("unit")
            val aValue = auto.optInt("value")
            val aPrice = d.optJSONObject("options")?.optJSONArray(curTier.ifEmpty { tier }).subObjects()
                .firstOrNull { it.optString("unit") == aUnit && it.optInt("value") == aValue }?.optLong("price")
            Text(
                stringResource(
                    R.string.sub_auto_on,
                    subDuration(aUnit, aValue),
                    if (aPrice != null) subNum(aPrice) else "?",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (sel != null && !(sel.optString("unit") == aUnit && sel.optInt("value") == aValue) && mode == SubMode.RENEW) {
                TextButton(onClick = { onAuto(true, sel.optString("unit"), sel.optInt("value")) }, enabled = !busy) {
                    Text(stringResource(R.string.sub_auto_change, subDuration(sel.optString("unit"), sel.optInt("value"))))
                }
            }
            when (auto.optString("lastResult")) {
                "ok" -> Text(
                    stringResource(R.string.sub_auto_last_ok),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                "insufficient" -> Text(
                    stringResource(R.string.sub_auto_last_low),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                "error" -> Text(
                    stringResource(R.string.sub_auto_last_err),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (!active) {
            Text(
                stringResource(R.string.sub_auto_need_plan),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubPerk(text: String) {
    Text("✓  $text", style = MaterialTheme.typography.bodyMedium)
}

/** One plan card. The selected one is full size and outlined, the rest are smaller and dimmer. */
@Composable
private fun SubTierCard(
    emoji: String,
    name: String,
    from: String,
    badge: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(targetValue = if (selected) 1f else 0.9f, label = "subTierScale")
    val fade by animateFloatAsState(targetValue = if (selected) 1f else 0.7f, label = "subTierFade")
    CandyCard(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = fade
            }
            .clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                badge.ifEmpty { " " },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(emoji, style = MaterialTheme.typography.headlineMedium)
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                from.ifEmpty { " " },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}
