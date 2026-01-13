package com.assistant.services.contacts

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log

/**
 * Resolves contact names to phone numbers with intelligent disambiguation.
 */
class ContactResolver(private val context: Context) {

    companion object {
        private const val TAG = "ContactResolver"
    }

    data class Contact(
        val id: String,
        val name: String,
        val phoneNumber: String?,
        val displayName: String
    )

    data class ResolutionResult(
        val status: Status,
        val contacts: List<Contact> = emptyList(),
        val disambiguationQuestion: String? = null
    ) {
        enum class Status {
            EXACT_MATCH,      // Found exactly one contact
            MULTIPLE_MATCHES, // Found multiple contacts with same/similar name
            NO_MATCH,         // No contacts found
            PERMISSION_DENIED // READ_CONTACTS permission not granted
        }
    }

    /**
     * Search for contacts by name.
     */
    fun resolveContact(query: String): ResolutionResult {
        // Check permission
        if (!hasContactsPermission()) {
            return ResolutionResult(
                status = ResolutionResult.Status.PERMISSION_DENIED,
                disambiguationQuestion = "I need permission to access your contacts. Please grant it in settings."
            )
        }

        val matches = searchContacts(query)

        return when {
            matches.isEmpty() -> {
                ResolutionResult(
                    status = ResolutionResult.Status.NO_MATCH,
                    disambiguationQuestion = "I couldn't find '$query' in your contacts. Could you spell it differently or try another name?"
                )
            }
            matches.size == 1 -> {
                ResolutionResult(
                    status = ResolutionResult.Status.EXACT_MATCH,
                    contacts = matches
                )
            }
            else -> {
                val question = generateDisambiguationQuestion(query, matches)
                ResolutionResult(
                    status = ResolutionResult.Status.MULTIPLE_MATCHES,
                    contacts = matches,
                    disambiguationQuestion = question
                )
            }
        }
    }
    
    /**
     * Search for contacts using multiple keywords for better disambiguation.
     * Example: "Harsh jo Kushal ka roommate hai" -> searches for contacts with both "Harsh" and "Kushal"
     */
    fun resolveContactWithKeywords(primaryName: String, keywords: List<String>): ResolutionResult {
        // Check permission
        if (!hasContactsPermission()) {
            return ResolutionResult(
                status = ResolutionResult.Status.PERMISSION_DENIED,
                disambiguationQuestion = "I need permission to access your contacts. Please grant it in settings."
            )
        }

        // First, get all contacts matching the primary name
        val primaryMatches = searchContacts(primaryName)
        
        if (primaryMatches.isEmpty()) {
            return ResolutionResult(
                status = ResolutionResult.Status.NO_MATCH,
                disambiguationQuestion = "I couldn't find '$primaryName' in your contacts."
            )
        }
        
        // If no additional keywords, use regular resolution
        if (keywords.isEmpty()) {
            return resolveContact(primaryName)
        }
        
        Log.d(TAG, "Resolving '$primaryName' with keywords: ${keywords.joinToString()}")
        
        // Score each contact based on keyword matches
        val scoredContacts = primaryMatches.map { contact ->
            var score = 0
            keywords.forEach { keyword ->
                val keywordLower = keyword.lowercase()
                // Check if keyword appears in display name
                if (contact.displayName.lowercase().contains(keywordLower)) {
                    score += 2  // Higher weight for name matches
                }
            }
            contact to score
        }
        
        // Get contacts with at least one keyword match
        val matchedContacts = scoredContacts
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
        
        return when {
            matchedContacts.isEmpty() -> {
                // No contacts matched the keywords, return all primary matches
                Log.w(TAG, "No contacts matched keywords, returning all '$primaryName' matches")
                ResolutionResult(
                    status = ResolutionResult.Status.MULTIPLE_MATCHES,
                    contacts = primaryMatches,
                    disambiguationQuestion = "I found ${primaryMatches.size} contacts named '$primaryName', but none matched your description. Which one?"
                )
            }
            matchedContacts.size == 1 -> {
                Log.i(TAG, "Found exact match: ${matchedContacts.first().displayName}")
                ResolutionResult(
                    status = ResolutionResult.Status.EXACT_MATCH,
                    contacts = matchedContacts
                )
            }
            else -> {
                Log.i(TAG, "Found ${matchedContacts.size} contacts matching keywords")
                val question = generateDisambiguationQuestion(primaryName, matchedContacts)
                ResolutionResult(
                    status = ResolutionResult.Status.MULTIPLE_MATCHES,
                    contacts = matchedContacts,
                    disambiguationQuestion = question
                )
            }
        }
    }

    /**
     * Search contacts database for matching names.
     */
    private fun searchContacts(query: String): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val normalizedQuery = query.lowercase().trim()

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME
        )

        val selection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use { c ->
                val idIndex = c.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIndex = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)

                while (c.moveToNext()) {
                    val id = c.getString(idIndex)
                    val name = c.getString(nameIndex)

                    // Get phone number
                    val phoneNumber = getPhoneNumber(id)

                    contacts.add(
                        Contact(
                            id = id,
                            name = name,
                            phoneNumber = phoneNumber,
                            displayName = name
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching contacts", e)
        } finally {
            cursor?.close()
        }

        return contacts
    }

    /**
     * Get phone number for a contact ID.
     */
    private fun getPhoneNumber(contactId: String): String? {
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val selectionArgs = arrayOf(contactId)

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val numberIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    return c.getString(numberIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting phone number", e)
        } finally {
            cursor?.close()
        }

        return null
    }

    /**
     * Generate a natural disambiguation question.
     */
    private fun generateDisambiguationQuestion(query: String, matches: List<Contact>): String {
        return when {
            matches.size == 2 -> {
                "I found 2 contacts named $query: ${matches[0].displayName} and ${matches[1].displayName}. Which one?"
            }
            matches.size <= 5 -> {
                val names = matches.joinToString(", ") { it.displayName }
                "I found ${matches.size} contacts named $query: $names. Which one would you like to call?"
            }
            else -> {
                "I found ${matches.size} contacts matching '$query'. Can you be more specific?"
            }
        }
    }

    /**
     * Check if READ_CONTACTS permission is granted.
     */
    private fun hasContactsPermission(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
