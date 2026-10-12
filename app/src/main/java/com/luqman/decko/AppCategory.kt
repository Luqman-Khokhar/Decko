package com.luqman.decko

import android.content.pm.ApplicationInfo

/**
 * App Library groups. Android's own app category comes first where the app declares one; most don't,
 * so package and label keywords fill in. Order here is the order cards appear in.
 */
enum class AppCategory(val title: Int, private val declared: Set<Int>, private val keywords: List<String>) {
    SOCIAL(R.string.cat_social, setOf(ApplicationInfo.CATEGORY_SOCIAL), listOf(
        "whatsapp", "telegram", "messenger", "facebook", "instagram", "snapchat", "twitter", "tiktok", "musically",
        "linkedin", "discord", "signal", "viber", "imo", "threads", "reddit", "messaging", "mms", "sms",
    )),
    AI(R.string.cat_ai, emptySet(), listOf("chatgpt", "openai", "claude", "anthropic", "gemini", "bard", "copilot", "perplexity", "deepseek", "assistant", "grok")),
    ENTERTAINMENT(R.string.cat_entertainment, setOf(ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO), listOf(
        "youtube", "netflix", "spotify", "music", "crunchyroll", "primevideo", "hotstar", "player", "podcast", "video", "movie", "soundcloud",
    )),
    GAMES(R.string.cat_games, setOf(ApplicationInfo.CATEGORY_GAME), listOf("game", "callofduty", "activision", "pubg", "freefire", "garena", "minecraft", "supercell", "roblox", "chess", "ludo")),
    PHOTO(R.string.cat_photo, setOf(ApplicationInfo.CATEGORY_IMAGE), listOf("camera", "gallery", "photo", "snapseed", "lightroom", "picsart", "canva", "editor", "lens", "capcut")),
    FINANCE(R.string.cat_finance, emptySet(), listOf(
        "bank", "pay", "wallet", "easypaisa", "jazzcash", "finance", "dib", "hbl", "meezan", "nayapay", "sadapay", "binance", "crypto", "upaisa", "alfalah", "ubl", "mcb",
    )),
    SHOPPING(R.string.cat_shopping, emptySet(), listOf("daraz", "amazon", "aliexpress", "temu", "shein", "foodpanda", "food", "shop", "store", "mart", "olx", "cheetay", "kfc", "mcdonald")),
    TRAVEL(R.string.cat_travel, setOf(ApplicationInfo.CATEGORY_MAPS), listOf("maps", "uber", "careem", "bykea", "indrive", "daewoo", "travel", "booking", "airline", "flight", "taxi", "ride")),
    PRODUCTIVITY(R.string.cat_productivity, setOf(ApplicationInfo.CATEGORY_PRODUCTIVITY), listOf(
        "drive", "docs", "sheets", "slides", "office", "word", "excel", "notes", "keep", "calendar", "gmail", "mail", "outlook", "authenticator",
        "github", "slack", "notion", "zoom", "teams", "meet", "trello", "expo", "dropbox", "pdf", "scanner",
        "chrome", "browser", "firefox", "opera", "brave", "edge",
    )),
    NEWS(R.string.cat_news, setOf(ApplicationInfo.CATEGORY_NEWS), listOf("news", "dawn", "reader", "medium", "feed")),
    UTILITIES(R.string.cat_utilities, emptySet(), emptyList()),
    OTHER(R.string.cat_other, emptySet(), emptyList());

    companion object {
        fun of(app: AppEntry): AppCategory {
            val info = app.info.applicationInfo
            // Whole words that start with a keyword: "smart" must not count as "mart", nor "public" as "ubl".
            val words = (app.component.packageName + " " + app.label).lowercase().split(Regex("[^a-z0-9]+"))
            fun AppCategory.matches() = keywords.any { k -> words.any { it.startsWith(k) } }
            // Curated keywords beat what the app declares: big apps often pick a broad category (Chrome and Gmail say "social").
            entries.firstOrNull { it.matches() }?.let { return it }
            entries.firstOrNull { info.category in it.declared }?.let { return it }
            return if (app.isSystem) UTILITIES else OTHER
        }
    }
}
