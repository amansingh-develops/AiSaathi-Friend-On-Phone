package com.assistant.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.assistant.domain.model.UserProfile
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Repository for managing UserProfile persistence.
 * 
 * Architecture:
 * - Uses SharedPreferences for local storage (simple, no database needed yet)
 * - Stores UserProfile as JSON string
 * - Provides save/load/clear operations
 */
class UserProfileRepository(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    /**
     * Save UserProfile to local storage.
     */
    fun saveUserProfile(profile: UserProfile) {
        val json = gson.toJson(profile)
        prefs.edit()
            .putString(KEY_USER_PROFILE, json)
            .apply()
    }
    
    /**
     * Load UserProfile from local storage.
     * Returns null if no profile exists.
     */
    fun getUserProfile(): UserProfile? {
        val json = prefs.getString(KEY_USER_PROFILE, null) ?: return null
        return try {
            gson.fromJson(json, UserProfile::class.java)
        } catch (e: JsonSyntaxException) {
            null
        }
    }
    
    /**
     * Check if user profile exists (onboarding completed).
     */
    fun hasUserProfile(): Boolean {
        return prefs.contains(KEY_USER_PROFILE)
    }
    
    /**
     * Clear user profile (for testing/logout).
     */
    fun clearUserProfile() {
        prefs.edit()
            .remove(KEY_USER_PROFILE)
            .apply()
    }
    
    companion object {
        private const val PREFS_NAME = "user_profile_prefs"
        private const val KEY_USER_PROFILE = "user_profile"
    }
}

