package tv.own.owntv.core.profile

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.subtitles.OpenSubtitlesAccountManager
import tv.own.owntv.core.util.Pin

/**
 * Creating, editing, switching and deleting a profile — everything about a profile that is not
 * layout.
 *
 * Both apps run this. What a profile *is* spans more tables than the `profiles` row itself: its
 * sources, its OpenSubtitles login, its "start on this channel" target, and the app-wide active id
 * that every screen resolves its content against. Two independent implementations of that would
 * drift, and the two apps share one database.
 */
class ProfileManager(
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val launcherIntegration: LauncherIntegrationRepository,
    private val openSubtitlesAccounts: OpenSubtitlesAccountManager,
) {

    /** Make [id] the profile the app is showing. */
    suspend fun switchTo(id: Long) = settings.setActiveProfile(id)

    fun verifyPin(profile: ProfileEntity, pin: String): Boolean = Pin.verify(pin, profile.pinHash)

    /**
     * Add a profile. It inherits every source that already exists — one account, several viewers —
     * so it has something to watch immediately; favorites and history stay its own.
     */
    suspend fun create(name: String, avatarId: Int, isKids: Boolean, pin: String?, fallbackName: String): Long {
        val id = profileDao.insert(
            ProfileEntity(
                name = name.ifBlank { fallbackName },
                avatarColor = 0,
                avatarId = avatarId,
                isKids = isKids,
                pinHash = pin?.takeIf { it.isNotBlank() }?.let { Pin.hash(it) },
            ),
        )
        sourceDao.allSourceIds().forEach { sourceDao.link(ProfileSourceCrossRef(profileId = id, sourceId = it)) }
        return id
    }

    /** Apply the editor's changes. [pin]: null keeps the existing PIN, "" removes it. */
    suspend fun edit(profile: ProfileEntity, name: String, avatarId: Int, isKids: Boolean, pin: String?) {
        val pinHash = when {
            pin == null -> profile.pinHash
            pin.isEmpty() -> null
            else -> Pin.hash(pin)
        }
        profileDao.update(
            profile.copy(
                name = name.ifBlank { profile.name },
                avatarId = avatarId,
                isKids = isKids,
                pinHash = pinHash,
            ),
        )
        if (profile.isKids != isKids) {
            launcherIntegration.refreshProfile(profile.id, allowBrowsableRequest = false)
        }
    }

    /**
     * Delete a profile and everything keyed to it, and hand the active slot to a survivor when it
     * was the one on screen. The last profile is never deleted — an app with none has nobody to
     * resolve any content for.
     */
    suspend fun delete(profile: ProfileEntity) {
        if (profileDao.count() <= 1) return
        val activeProfileId = settings.activeProfileId.first()
        val remainingProfileId = profileDao.getAllOnce().firstOrNull { it.id != profile.id }?.id
        runCatching { launcherIntegration.clearProfile(profile.id) }
        // Deleting a profile permanently erases its stored OpenSubtitles login (subtitle plan §5.5).
        openSubtitlesAccounts.eraseFor(profile.id)
        settings.setStartupChannel(profile.id, null)
        profileDao.delete(profile)
        if (activeProfileId == profile.id) settings.setActiveProfile(remainingProfileId ?: -1L)
    }
}

/** How many avatars each app draws. Both shells number them the same, so a profile made on one
 *  device shows the same picture on the other. */
const val PROFILE_AVATAR_COUNT = 10

/** One unlocked profile enters the app immediately; every chooser and PIN case stays gated. */
fun profileGateRequired(profiles: List<ProfileEntity>): Boolean =
    profiles.size > 1 || profiles.singleOrNull()?.pinHash != null

/**
 * Whether the app proper may be shown yet, given what is known so far.
 *
 * Both launch decisions run through this, because getting it wrong shows somebody else's library.
 * [profiles] null is Room not having answered — deliberately different from an answered empty list,
 * which is a fresh install or a restore window and is not permission to enter either. The unlock is
 * bound to an id, so a session that unlocked one profile cannot admit another.
 */
fun shellMayCompose(
    profiles: List<ProfileEntity>?,
    activeProfileId: Long?,
    authenticatedProfileId: Long?,
    gateRequired: Boolean,
): Boolean {
    val loaded = profiles ?: return false
    val active = activeProfileId ?: return false
    if (active < 0L || loaded.none { it.id == active }) return false
    return !gateRequired || authenticatedProfileId == active
}
