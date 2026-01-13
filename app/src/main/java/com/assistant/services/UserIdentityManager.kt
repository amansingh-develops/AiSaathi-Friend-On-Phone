package com.assistant.services

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class UserIdentityManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val userIdKey = KEY_USER_ID

    fun getUserId(): String {
        val existingId = prefs.getString(userIdKey, null)
        return if (existingId != null) {
            existingId
        } else {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(userIdKey, newId).apply()
            newId
        }
    }

    companion object {
        private const val PREFS_NAME = "user_identity_prefs"
        private const val KEY_USER_ID = "user_id"
    }
}

