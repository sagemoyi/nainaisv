package com.xmoyi.nainaisv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xmoyi.nainaisv.BuildConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val setupComplete = booleanPreferencesKey("setup_complete")
        val pinSalt = stringPreferencesKey("pin_salt")
        val pinHash = stringPreferencesKey("pin_hash")
        val lastDramaId = stringPreferencesKey("last_drama_id")
        val lastGlobalSearch = longPreferencesKey("last_global_search")
        val updateUrl = stringPreferencesKey("update_url")
        val positiveTerms = stringPreferencesKey("positive_terms")
        val blockedTerms = stringPreferencesKey("blocked_terms")
        val hintSeen = booleanPreferencesKey("hint_seen")
    }

    val setupComplete: Flow<Boolean> = context.dataStore.data.map { it[Keys.setupComplete] ?: false }
    val hasPin: Flow<Boolean> = context.dataStore.data.map {
        !it[Keys.pinHash].isNullOrBlank() && !it[Keys.pinSalt].isNullOrBlank()
    }
    val lastDramaId: Flow<String?> = context.dataStore.data.map { it[Keys.lastDramaId] }
    val lastGlobalSearch: Flow<Long> = context.dataStore.data.map { it[Keys.lastGlobalSearch] ?: 0L }
    val hintSeen: Flow<Boolean> = context.dataStore.data.map { it[Keys.hintSeen] ?: false }

    val positiveTerms: Flow<String> = context.dataStore.data.map {
        it[Keys.positiveTerms] ?: DEFAULT_POSITIVE_TERMS
    }
    val blockedTerms: Flow<String> = context.dataStore.data.map {
        it[Keys.blockedTerms] ?: DEFAULT_BLOCKED_TERMS
    }
    val updateUrl: Flow<String> = context.dataStore.data.map {
        it[Keys.updateUrl] ?: BuildConfig.UPDATE_MANIFEST_URL
    }

    suspend fun savePin(pin: String) {
        require(pin.length >= 4)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        context.dataStore.edit {
            it[Keys.pinSalt] = Base64.getEncoder().encodeToString(salt)
            it[Keys.pinHash] = hashPin(pin, salt)
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val prefs = context.dataStore.data.first()
        val saltValue = prefs[Keys.pinSalt]
        val expected = prefs[Keys.pinHash]
        val salt = saltValue?.let(Base64.getDecoder()::decode) ?: return false
        val actual = hashPin(pin, salt)
        return MessageDigest.isEqual(actual.toByteArray(), expected?.toByteArray() ?: return false)
    }

    suspend fun completeSetup() = context.dataStore.edit { it[Keys.setupComplete] = true }
    suspend fun setLastDrama(id: String) = context.dataStore.edit { it[Keys.lastDramaId] = id }
    suspend fun setHintSeen() = context.dataStore.edit { it[Keys.hintSeen] = true }
    suspend fun setLastGlobalSearch(time: Long) = context.dataStore.edit { it[Keys.lastGlobalSearch] = time }
    suspend fun setTerms(positive: String, blocked: String) = context.dataStore.edit {
        it[Keys.positiveTerms] = positive
        it[Keys.blockedTerms] = blocked
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        repeat(20_000) {
            digest.update(salt)
            digest.update(pin.toByteArray())
        }
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    companion object {
        const val DEFAULT_POSITIVE_TERMS = "全集,完结,合集,加长版,家庭,年代,甜宠,轻喜,治愈,美食,田园"
        const val DEFAULT_BLOCKED_TERMS = "教程,课程,制作,变现,接稿,赚钱,软件,预告,试看,付费,擦边,成人,恐怖,血腥"
    }
}
