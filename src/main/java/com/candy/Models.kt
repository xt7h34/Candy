package com.candy

import org.json.JSONArray
import org.json.JSONObject

/** org.json gives the text "null" for a JSON null, so we check for it ourselves. */
fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/** A person, as the bot shows them to others. Never has a phone number. */
class CUser(
    val name: String,
    val hash: String,
    val vibe: String?,
    val joinDate: String?,
    val followers: Int,
    val following: Int,
    val isMe: Boolean,
    val followedByMe: Boolean,
    val followsMe: Boolean,
)

fun JSONObject.toUser() = CUser(
    name = optString("name"),
    hash = optString("hash"),
    vibe = str("vibe"),
    joinDate = str("joinDate"),
    followers = optInt("followers"),
    following = optInt("following"),
    isMe = optBoolean("isMe"),
    followedByMe = optBoolean("followedByMe"),
    followsMe = optBoolean("followsMe"),
)

fun JSONArray?.toUsers(): List<CUser> {
    if (this == null) return emptyList()
    val out = ArrayList<CUser>()
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it.toUser()) }
    return out
}

/** A short text that disappears after 24 hours. [ownerHash] is empty on a person's own profile. */
class Drift(
    val text: String,
    val postedAt: Long,
    val expiresAt: Long,
    val inBubble: Boolean,
    val ownerName: String,
    val ownerHash: String,
)

fun JSONArray?.toDrifts(): List<Drift> {
    if (this == null) return emptyList()
    val out = ArrayList<Drift>()
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        val owner = o.optJSONObject("owner")
        out.add(
            Drift(
                text = o.optString("text"),
                postedAt = o.optLong("postedAt"),
                expiresAt = o.optLong("expiresAt"),
                inBubble = o.optString("audience") == "bubble",
                ownerName = owner?.optString("name").orEmpty(),
                ownerHash = owner?.optString("hash").orEmpty(),
            ),
        )
    }
    return out
}

/** The hash the way the bot wants it in a request: no #. */
fun bareHash(h: String): String = h.trim().removePrefix("#")

class Member(val name: String, val hash: String)

class Bubble(val name: String, val members: List<Member>)

fun JSONArray?.toBubbles(): List<Bubble> {
    if (this == null) return emptyList()
    val out = ArrayList<Bubble>()
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        val people = ArrayList<Member>()
        val arr = o.optJSONArray("members")
        if (arr != null) {
            for (j in 0 until arr.length()) {
                val p = arr.optJSONObject(j) ?: continue
                people.add(Member(p.optString("name"), p.optString("hash")))
            }
        }
        out.add(Bubble(o.optString("name"), people))
    }
    return out
}
