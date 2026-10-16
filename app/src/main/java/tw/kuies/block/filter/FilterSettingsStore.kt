package tw.kuies.block.filter

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists [FilterSettings] in the app's private `kuies_block_filter` preference file, the only place
 * that reads or writes those values.
 *
 * Two properties matter here:
 *  * **Loading never throws and never loses everything.** A field that is missing, of the wrong type,
 *    unknown or corrupt falls back to its default, and a single unreadable record is skipped instead
 *    of discarding the rest. An unknown tier id falls back to [FilterProfile.DEFAULT].
 *  * **No new dependency.** The structured parts are stored as sets of line-oriented records encoded
 *    by [FilterPreferenceCodec], so the module keeps working with nothing but SharedPreferences.
 *
 * The file is excluded from backup and device transfer (see `res/xml/backup_rules.xml` and
 * `res/xml/data_extraction_rules.xml`): the user's rules are theirs and stay on the device.
 */
class FilterSettingsStore(private val prefs: SharedPreferences) {

    /** Production constructor: the app's private preference file, as with the other stores. */
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun load(): FilterSettings {
        val profile = guarded(FilterProfile.DEFAULT) {
            FilterProfile.fromId(prefs.getString(KEY_PROFILE, null))
        }
        val stored = guarded(emptyList<StoredSubscription>()) { readStoredSubscriptions() }
        val subscriptions = guarded(listOf(StoredSubscription(FilterSubscription.forProfile(profile), null))) {
            normalise(stored, profile)
        }

        val etags = HashMap<String, String>(subscriptions.size)
        for (storedSubscription in subscriptions) {
            storedSubscription.etag?.let { etags[storedSubscription.subscription.id] = it }
        }

        return FilterSettings(
            profile = profile,
            enabled = guarded(false) { prefs.getBoolean(KEY_ENABLED, false) },
            subscriptions = subscriptions.map { it.subscription },
            manualAllow = guarded(emptyList()) { readManualRules(KEY_MANUAL_ALLOW) },
            manualBlock = guarded(emptyList()) { readManualRules(KEY_MANUAL_BLOCK) },
            etags = etags
        )
    }

    fun save(settings: FilterSettings) {
        // A set has no order and no duplicate ids, so both are settled here: one record per id, and
        // the profile list always first.
        val records = LinkedHashSet<String>()
        val seen = HashSet<String>()
        for (subscription in settings.subscriptions) {
            if (!seen.add(subscription.id)) continue
            records.add(encodeSubscription(subscription, settings.etagFor(subscription.id)))
        }

        prefs.edit()
            .putString(KEY_PROFILE, settings.profile.id)
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putStringSet(KEY_SUBSCRIPTIONS, records)
            .putStringSet(KEY_MANUAL_ALLOW, encodeManualRules(settings.manualAllow))
            .putStringSet(KEY_MANUAL_BLOCK, encodeManualRules(settings.manualBlock))
            .apply()
    }

    /**
     * Reads the stored records, dropping ones that cannot be trusted (malformed record, unusable id,
     * a built-in id claimed by a user source) and keeping the first record per id.
     */
    private fun readStoredSubscriptions(): List<StoredSubscription> {
        val raw = prefs.getStringSet(KEY_SUBSCRIPTIONS, null) ?: return emptyList()
        val stored = ArrayList<StoredSubscription>(raw.size)
        val seen = HashSet<String>()
        // Copy before iterating: the set SharedPreferences hands back must not be reused as-is.
        for (record in raw.toList()) {
            val decoded = decodeSubscription(record) ?: continue
            if (seen.add(decoded.subscription.id)) stored.add(decoded)
        }
        return stored
    }

    /**
     * Restores the two invariants the rest of the module relies on: exactly one main list, pointing
     * at the active tier, and a stable order (a set has none, so the profile list comes first and the
     * rest are sorted by name).
     */
    private fun normalise(
        stored: List<StoredSubscription>,
        profile: FilterProfile
    ): List<StoredSubscription> {
        val profileList = stored.firstOrNull {
            it.subscription.id == FilterSubscription.PROFILE_SUBSCRIPTION_ID
        }
        val activeTier = profileList?.takeIf {
            it.subscription.origin == SubscriptionOrigin.BUILT_IN &&
                (it.subscription.url == profile.primaryUrl ||
                    it.subscription.url == profile.fallbackUrl)
        }
        // A stored copy of another tier describes a list that is not active any more, so its rule
        // count and ETag are meaningless: start that entry over instead of reusing it.
        val main = activeTier ?: StoredSubscription(FilterSubscription.forProfile(profile), null)
        val custom = stored
            .filter { it.subscription.id != FilterSubscription.PROFILE_SUBSCRIPTION_ID }
            .sortedWith(
                compareBy(
                    { it.subscription.displayName.lowercase() },
                    { it.subscription.id }
                )
            )
        return listOf(main) + custom
    }

    private fun decodeSubscription(record: String): StoredSubscription? {
        val fields = FilterPreferenceCodec.decode(record) ?: return null
        if (fields.size < SUBSCRIPTION_FIELDS) return null

        // Only the id is trimmed: it becomes a file name. Everything else is kept verbatim, because a
        // trim would silently eat the control characters a URL or a display name may legitimately
        // contain, and the codec already preserves them exactly.
        val id = fields[0].trim()
        if (id.isEmpty() || id.length > MAX_ID_LENGTH) return null
        val url = fields[2]
        if (url.isBlank() || url.length > MAX_URL_LENGTH) return null
        val origin = originOf(fields[3].trim())
        if (id == FilterSubscription.PROFILE_SUBSCRIPTION_ID && origin != SubscriptionOrigin.BUILT_IN) {
            return null
        }

        val subscription = FilterSubscription(
            id = id,
            displayName = fields[1].ifBlank { id },
            url = url,
            origin = origin,
            // An unreadable flag falls back to the field's own default, which is "enabled".
            enabled = when (fields[4].trim()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> true
            },
            state = stateOf(fields[5].trim()),
            ruleCount = fields[6].trim().toIntOrNull()?.takeIf { it >= 0 } ?: 0,
            lastSuccessAtMillis = fields[7].trim().toLongOrNull(),
            lastError = fields[8].ifEmpty { null }
        )
        return StoredSubscription(subscription, fields[9].ifEmpty { null })
    }

    private fun encodeSubscription(subscription: FilterSubscription, etag: String?): String =
        FilterPreferenceCodec.encode(
            listOf(
                subscription.id,
                subscription.displayName,
                subscription.url,
                subscription.origin.name,
                if (subscription.enabled) "1" else "0",
                subscription.state.name,
                subscription.ruleCount.toString(),
                subscription.lastSuccessAtMillis?.toString() ?: "",
                subscription.lastError ?: "",
                etag ?: ""
            )
        )

    private fun readManualRules(key: String): List<ManualRule> {
        val raw = prefs.getStringSet(key, null) ?: return emptyList()
        val rules = ArrayList<ManualRule>(raw.size)
        for (record in raw.toList()) {
            val fields = FilterPreferenceCodec.decode(record) ?: continue
            if (fields.size < MANUAL_RULE_FIELDS) continue
            val domain = fields[0]
            if (domain.isBlank()) continue
            rules.add(ManualRule(domain = domain, scope = scopeOf(fields[1].trim())))
        }
        return rules
    }

    private fun encodeManualRules(rules: List<ManualRule>): Set<String> {
        val records = LinkedHashSet<String>(rules.size)
        for (rule in rules) {
            records.add(FilterPreferenceCodec.encode(listOf(rule.domain, rule.scope.name)))
        }
        return records
    }

    /** An unknown origin is treated as a user source: fully address-checked, but it is not a tier. */
    private fun originOf(value: String): SubscriptionOrigin =
        SubscriptionOrigin.entries.firstOrNull { it.name == value } ?: SubscriptionOrigin.USER

    private fun stateOf(value: String): SubscriptionState =
        SubscriptionState.entries.firstOrNull { it.name == value } ?: SubscriptionState.NOT_READY

    private fun scopeOf(value: String): MatchScope =
        MatchScope.entries.firstOrNull { it.name == value } ?: MatchScope.SUBDOMAIN

    /** Runs [block] and answers [fallback] for any readable-and-recoverable failure. */
    private fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        fallback
    }

    private data class StoredSubscription(
        val subscription: FilterSubscription,
        val etag: String?
    )

    companion object {
        /** Excluded from cloud backup and device transfer, like the other two preference files. */
        const val PREFS_NAME = "kuies_block_filter"

        private const val KEY_PROFILE = "profile"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SUBSCRIPTIONS = "subscriptions"
        private const val KEY_MANUAL_ALLOW = "manual_allow"
        private const val KEY_MANUAL_BLOCK = "manual_block"

        private const val SUBSCRIPTION_FIELDS = 10
        private const val MANUAL_RULE_FIELDS = 2
        private const val MAX_ID_LENGTH = 64
        private const val MAX_URL_LENGTH = 2048
    }
}
