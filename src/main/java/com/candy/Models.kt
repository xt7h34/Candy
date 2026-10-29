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
    val posts: Int = 0,
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
    posts = optInt("posts"),
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

/** A photo post. */
class Post(
    val id: String,
    val caption: String,
    val at: Long,
    val w: Int,
    val h: Int,
    val hasImage: Boolean,
    val likes: Int,
    val liked: Boolean,
    val comments: Int,
    val mine: Boolean,
    val ownerName: String,
    val ownerHash: String,
)

fun JSONObject.toPost(): Post {
    val owner = optJSONObject("owner")
    return Post(
        id = optString("id"),
        caption = optString("caption"),
        at = optLong("at"),
        w = optInt("w"),
        h = optInt("h"),
        hasImage = optBoolean("hasImage", optInt("w") > 0),
        likes = optInt("likes"),
        liked = optBoolean("liked"),
        comments = optInt("comments"),
        mine = optBoolean("mine"),
        ownerName = owner?.optString("name").orEmpty(),
        ownerHash = owner?.optString("hash").orEmpty(),
    )
}

fun JSONArray?.toPosts(): List<Post> {
    if (this == null) return emptyList()
    val out = ArrayList<Post>()
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it.toPost()) }
    return out
}

class Comment(
    val id: String,
    val text: String,
    val at: Long,
    val canDelete: Boolean,
    val ownerName: String,
    val ownerHash: String,
)

fun JSONArray?.toComments(): List<Comment> {
    if (this == null) return emptyList()
    val out = ArrayList<Comment>()
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        val owner = o.optJSONObject("owner")
        out.add(
            Comment(
                id = o.optString("id"),
                text = o.optString("text"),
                at = o.optLong("at"),
                canDelete = o.optBoolean("canDelete"),
                ownerName = owner?.optString("name").orEmpty(),
                ownerHash = owner?.optString("hash").orEmpty(),
            ),
        )
    }
    return out
}

/** A piece of text written on top of a stage. [x] and [y] are fractions of the screen, [c] a colour and [s] a size. */
data class Overlay(val t: String, val x: Float, val y: Float, val c: Int, val s: Int)

fun JSONArray?.toOverlays(): List<Overlay> {
    if (this == null) return emptyList()
    val out = ArrayList<Overlay>()
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        out.add(Overlay(o.optString("t"), o.optDouble("x").toFloat(), o.optDouble("y").toFloat(), o.optInt("c"), o.optInt("s")))
    }
    return out
}

/** One picture or video in a stage. */
class StoryItem(
    val id: String,
    val at: Long,
    val exp: Long,
    val caption: String,
    val seen: Boolean,
    val views: Int,
    val kind: String,
    val overlays: List<Overlay>,
)

/** All the live stories of one person. */
class StoryGroup(
    val ownerName: String,
    val ownerHash: String,
    val mine: Boolean,
    val allSeen: Boolean,
    val items: List<StoryItem>,
)

fun JSONArray?.toStoryGroups(): List<StoryGroup> {
    if (this == null) return emptyList()
    val out = ArrayList<StoryGroup>()
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        val owner = o.optJSONObject("owner")
        val items = ArrayList<StoryItem>()
        val arr = o.optJSONArray("items")
        if (arr != null) {
            for (j in 0 until arr.length()) {
                val s = arr.optJSONObject(j) ?: continue
                items.add(
                    StoryItem(
                        id = s.optString("id"),
                        at = s.optLong("at"),
                        exp = s.optLong("exp"),
                        caption = s.optString("caption"),
                        seen = s.optBoolean("seen"),
                        views = s.optInt("views"),
                        kind = s.optString("kind", "photo"),
                        overlays = s.optJSONArray("overlays").toOverlays(),
                    ),
                )
            }
        }
        if (items.isEmpty()) continue
        out.add(
            StoryGroup(
                ownerName = owner?.optString("name").orEmpty(),
                ownerHash = owner?.optString("hash").orEmpty(),
                mine = o.optBoolean("mine"),
                allSeen = o.optBoolean("allSeen"),
                items = items,
            ),
        )
    }
    return out
}
