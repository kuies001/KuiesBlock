package tw.kuies.block.filter

import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns what the user typed into the "add a source" field into a [FilterSubscription], or says why it
 * cannot become one.
 *
 * Why this exists: a custom source is the one subscription the app did not ship, so every value that
 * decides what happens to it afterwards — the id above all — is derived here from the URL instead of
 * being taken from what the user typed.
 *
 * Rules, each of them deliberate:
 *  * **https only.** The scheme is checked here as well as in [SubscriptionHostGuard] and in
 *    [SubscriptionFetcher], because a plain-http source would still be downloaded if anything else
 *    ever let one through. Nothing anywhere may weaken TLS to make a source work.
 *  * **The id is derived from the URL and from nothing else**: the literal prefix `user.` plus the
 *    first [ID_DIGEST_CHARS] hex characters of the URL's SHA-256. That makes it deterministic (the
 *    same URL always yields the same id, so adding it twice cannot leave two files behind) and it
 *    keeps the user-supplied *name* entirely out of the filesystem: [SubscriptionContentStore] writes
 *    `<filesDir>/filters/<id>.list`, and this id is `[a-z0-9]` after a literal `user.`, so it can
 *    never contain `/`, `\`, `..`, a space or any character outside `[A-Za-z0-9._-]`, is never a name
 *    a host filesystem treats as a device, and passes [SubscriptionContentStore.safeId] — which the
 *    unit test asserts rather than assuming.
 *  * **The address half of the SSRF policy is left to the download.** [SubscriptionHostGuard.checkHost]
 *    is pure and runs here (it already refuses `localhost`, `.local` names and private literals);
 *    resolving a hostname is network work and belongs to [SubscriptionFetcher], which repeats both
 *    halves of the check on every hop.
 *  * **Neither the URL nor the name is ever logged**, by this class or by anything it returns.
 */
object SubscriptionDraft {

    /**
     * Most custom sources one install may hold.
     *
     * The cap counts [SubscriptionOrigin.USER] entries, so the built-in main list — which cannot be
     * removed and is not something the user added — never consumes one of these slots, whichever list
     * the caller passes as `existing`.
     */
    const val MAX_SUBSCRIPTIONS: Int = 10

    /**
     * Longest URL accepted.
     *
     * Matches the bound `FilterSettingsStore` applies when it reads a stored subscription back
     * (2048): a longer URL could be accepted here and then silently dropped on the next start-up, so
     * it is refused while the user is still looking at the field.
     */
    const val MAX_URL_LENGTH: Int = 2048

    /**
     * Longest display name kept, in UTF-16 characters.
     *
     * The name is only ever a row label, and a row has no room for more; the bound also keeps a
     * pasted paragraph from pushing the rest of the settings screen off screen. The URL's host is
     * capped by the same bound when the user leaves the field blank.
     */
    const val MAX_NAME_LENGTH: Int = 60

    /** The id prefix that keeps a user source's id apart from a built-in one. */
    private const val ID_PREFIX = "user."

    /** Hex characters of the URL digest kept in the id. 32 hex = 128 bits: no realistic collision. */
    private const val ID_DIGEST_CHARS = 32

    private const val HEX_DIGITS = "0123456789abcdef"

    /** The pure half of the SSRF check; it never resolves anything, so this is safe to hold. */
    private val HOST_GUARD = SubscriptionHostGuard()

    enum class Rejection { EMPTY_URL, NOT_HTTPS, URL_TOO_LONG, BAD_HOST, DUPLICATE, TOO_MANY }

    sealed interface Result {
        data class Accepted(val subscription: FilterSubscription) : Result
        data class Rejected(val reason: Rejection) : Result
    }

    /**
     * [existing] is the list the new subscription would join, for duplicate and count checks.
     *
     * Checks run cheapest-and-most-obvious first: an empty field, an over-long URL, the scheme, the
     * host, then "you already added this" before "you have too many". A duplicate of an existing URL
     * is refused rather than added under a second id, because two ids for one list means two
     * downloads and two stored copies of the same rules.
     *
     * The accepted source is [SubscriptionState.NOT_READY] with `ruleCount = 0` and no timestamps:
     * nothing has been downloaded yet, and [SubscriptionUpdater] fills the bookkeeping in. Claiming
     * anything else here would make a brand-new row read as though it were already working.
     */
    fun parse(rawUrl: String, rawName: String, existing: List<FilterSubscription>): Result {
        val trimmedUrl = rawUrl.trim()
        if (trimmedUrl.isEmpty()) return Result.Rejected(Rejection.EMPTY_URL)
        if (trimmedUrl.length > MAX_URL_LENGTH) return Result.Rejected(Rejection.URL_TOO_LONG)

        val parsed = trimmedUrl.toHttpUrlOrNull()
            ?: return Result.Rejected(
                // OkHttp only carries http and https, so a string that fails to parse either never
                // said https (`http://…`, `ftp://…`, a bare `lists.example.com/x`) — the user has to
                // add the scheme — or claimed https and had no usable host (`https://`, `https:///x`).
                if (trimmedUrl.startsWith(HTTPS_PREFIX, ignoreCase = true)) {
                    Rejection.BAD_HOST
                } else {
                    Rejection.NOT_HTTPS
                }
            )
        if (parsed.scheme != SubscriptionHostGuard.HTTPS_SCHEME) {
            return Result.Rejected(Rejection.NOT_HTTPS)
        }
        // Host only: the guard's literal-address and local-name checks run now, DNS at download time.
        if (HOST_GUARD.checkHost(parsed.host, SubscriptionOrigin.USER) != null) {
            return Result.Rejected(Rejection.BAD_HOST)
        }

        // The canonical form, so `https://Lists.Example.com/x` and `https://lists.example.com/x` are
        // one source and one id rather than two.
        val url = parsed.toString()
        if (existing.any { canonicalUrl(it.url) == url }) return Result.Rejected(Rejection.DUPLICATE)
        if (existing.count { it.origin == SubscriptionOrigin.USER } >= MAX_SUBSCRIPTIONS) {
            return Result.Rejected(Rejection.TOO_MANY)
        }

        return Result.Accepted(
            FilterSubscription(
                id = idFor(url),
                displayName = displayName(rawName, parsed.host),
                url = url,
                origin = SubscriptionOrigin.USER,
                enabled = true,
                state = SubscriptionState.NOT_READY,
                ruleCount = 0,
                lastSuccessAtMillis = null,
                lastError = null
            )
        )
    }

    /** The id a custom source for [url] always has, whatever its display name is. */
    private fun idFor(url: String): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(url.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(ID_PREFIX.length + ID_DIGEST_CHARS)
        builder.append(ID_PREFIX)
        for (index in 0 until ID_DIGEST_CHARS / 2) {
            val value = digest[index].toInt() and 0xFF
            builder.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return builder.toString()
    }

    /** The name to show for a source: the user's own, or the URL's host when they left it blank. */
    private fun displayName(rawName: String, host: String): String =
        capped(sanitiseName(rawName).ifEmpty { host })

    /**
     * Drops control characters, collapses every run of whitespace to one space and trims.
     *
     * A name is a label, not data: a newline, a tab or a stray control character pasted in from a web
     * page would otherwise show up as a broken row, and the preference codec would have to escape
     * them for no benefit.
     */
    private fun sanitiseName(raw: String): String {
        val builder = StringBuilder(raw.length.coerceAtMost(MAX_NAME_LENGTH))
        var gapPending = false
        for (character in raw) {
            if (character.isWhitespace() || character.isISOControl()) {
                gapPending = builder.isNotEmpty()
                continue
            }
            if (gapPending) {
                builder.append(' ')
                gapPending = false
            }
            builder.append(character)
        }
        return builder.toString()
    }

    /** Truncates to [MAX_NAME_LENGTH] characters without ever cutting a surrogate pair in half. */
    private fun capped(value: String): String {
        if (value.length <= MAX_NAME_LENGTH) return value
        var end = MAX_NAME_LENGTH
        if (value[end - 1].isHighSurrogate()) end--
        return value.substring(0, end)
    }

    /** A stored URL in the same form a freshly typed one is compared in. */
    private fun canonicalUrl(url: String): String =
        url.trim().toHttpUrlOrNull()?.toString() ?: url.trim()

    private const val SHA_256 = "SHA-256"
    private const val HTTPS_PREFIX = "https://"
}
