package com.assistant.services.calllog

import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import android.Manifest

/**
 * Helper class to query call logs for intelligent calling features.
 * 
 * Supports queries like:
 * - Last person I called
 * - Last missed call
 * - Last person who called me
 */
class CallLogHelper(private val context: Context) {

    companion object {
        private const val TAG = "CallLogHelper"
    }

    data class CallInfo(
        val phoneNumber: String,
        val contactName: String?,
        val callType: Int,
        val timestamp: Long
    )

    /**
     * Get the last outgoing call (last person I called).
     */
    fun getLastOutgoingCall(): CallInfo? {
        return getLastCallOfType(CallLog.Calls.OUTGOING_TYPE)
    }

    /**
     * Get the last missed call.
     */
    fun getLastMissedCall(): CallInfo? {
        return getLastCallOfType(CallLog.Calls.MISSED_TYPE)
    }

    /**
     * Get the last incoming call (last person who called me).
     */
    fun getLastIncomingCall(): CallInfo? {
        return getLastCallOfType(CallLog.Calls.INCOMING_TYPE)
    }

    /**
     * Get the last call of a specific type.
     */
    private fun getLastCallOfType(callType: Int): CallInfo? {
        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) 
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CALL_LOG permission not granted")
            return null
        }

        var cursor: Cursor? = null
        try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE
            )

            val selection = "${CallLog.Calls.TYPE} = ?"
            val selectionArgs = arrayOf(callType.toString())
            val sortOrder = "${CallLog.Calls.DATE} DESC"

            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            if (cursor != null && cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)

                val phoneNumber = if (numberIndex >= 0) cursor.getString(numberIndex) else null
                val contactName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val type = if (typeIndex >= 0) cursor.getInt(typeIndex) else callType
                val timestamp = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L

                if (phoneNumber != null) {
                    return CallInfo(phoneNumber, contactName, type, timestamp)
                }
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error querying call logs", e)
            return null
        } finally {
            cursor?.close()
        }
    }

    /**
     * Get a human-readable description of the call type.
     */
    fun getCallTypeDescription(callType: Int): String {
        return when (callType) {
            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
            CallLog.Calls.INCOMING_TYPE -> "incoming"
            CallLog.Calls.MISSED_TYPE -> "missed"
            else -> "unknown"
        }
    }
}
