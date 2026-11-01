package com.blacklab.app.core

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Fills PDF forms from your details. Field names in real forms are messy ("Applicant_LastName",
 * "txtEmail", "Date of Signature", "Sig1"), so each name (and its tooltip) is normalized and matched
 * against keywords. Your contact details live in a private preferences file on the phone and are
 * not part of the settings that sync to BlackLab Desktop.
 */
object FormAutofill {
    enum class Slot(val label: String) {
        FULL_NAME("Full name"), FIRST_NAME("First name"), LAST_NAME("Last name"), TITLE("Job title"), ORGANIZATION("Organization"),
        EMAIL("Email"), PHONE("Phone"), ADDRESS("Street address"), CITY("City"), STATE("State"), ZIP("ZIP / postal code"),
        DATE("Today's date"), SIGNATURE("Signature"), INITIALS("Initials"),
    }

    data class Profile(
        val fullName: String = "", val title: String = "", val organization: String = "", val email: String = "", val phone: String = "",
        val address: String = "", val city: String = "", val state: String = "", val zip: String = "",
    ) {
        val firstName get() = fullName.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        val lastName get() = fullName.trim().split(Regex("\\s+")).let { if (it.size > 1) it.last() else "" }
    }

    // ----------------------------------------------------------------- pure (unit tested)

    /** "Applicant_LastName2" → "applicant last name". */
    fun normalize(name: String): String = name
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace(Regex("[^A-Za-z]+"), " ")
        .lowercase().trim().replace(Regex("\\s+"), " ")

    /** Best guess for what a field wants, from its name and tooltip; null when unsure. */
    fun classify(name: String, alt: String = ""): Slot? {
        val n = " " + normalize("$name $alt") + " "
        fun has(vararg w: String) = w.any { n.contains(" $it ") || n.contains(" $it") && it.length > 4 }
        return when {
            has("initial", "initials", "init") && !has("first") -> Slot.INITIALS
            has("signature", "sign", "sig", "signed by", "signer") && !has("date", "dated", "print", "printed", "name of") -> Slot.SIGNATURE
            has("date", "dated", "today") && !has("birth", "dob", "start", "end", "expiration", "expiry", "due", "hire") -> Slot.DATE
            has("email", "e mail", "mail address") -> Slot.EMAIL
            has("phone", "telephone", "tel", "mobile", "cell", "phone number") -> Slot.PHONE
            has("zip", "postal", "postcode", "zipcode") -> Slot.ZIP
            has("state", "province", "region") && !has("statement") -> Slot.STATE
            has("city", "town") -> Slot.CITY
            has("address", "street", "addr") -> Slot.ADDRESS
            has("first name", "given name", "fname", "first") -> Slot.FIRST_NAME
            has("last name", "surname", "family name", "lname", "last") -> Slot.LAST_NAME
            has("company", "organization", "organisation", "employer", "agency", "institution", "business", "org") -> Slot.ORGANIZATION
            has("title", "position", "job", "role") && !has("document", "project", "course") -> Slot.TITLE
            has("name", "full name", "print name", "printed name", "your name", "employee", "applicant") -> Slot.FULL_NAME
            else -> null
        }
    }

    fun valueFor(slot: Slot, p: Profile, today: LocalDate = LocalDate.now()): String = when (slot) {
        Slot.FULL_NAME -> p.fullName
        Slot.FIRST_NAME -> p.firstName
        Slot.LAST_NAME -> p.lastName
        Slot.TITLE -> p.title
        Slot.ORGANIZATION -> p.organization
        Slot.EMAIL -> p.email
        Slot.PHONE -> p.phone
        Slot.ADDRESS -> p.address
        Slot.CITY -> p.city
        Slot.STATE -> p.state
        Slot.ZIP -> p.zip
        Slot.DATE -> today.format(DateTimeFormatter.ofPattern("MM/dd/yyyy"))
        Slot.SIGNATURE, Slot.INITIALS -> ""
    }

    // ----------------------------------------------------------------- store

    private const val PREFS = "blacklab_form_profile"

    /** Name, title and organization come from Settings › You; contact details from this private store. */
    fun profile(context: Context, s: Settings): Profile {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Profile(s.userName, s.jobTitle, s.organization, p.getString("email", "")!!, p.getString("phone", "")!!,
            p.getString("address", "")!!, p.getString("city", "")!!, p.getString("state", "")!!, p.getString("zip", "")!!)
    }

    fun saveContact(context: Context, p: Profile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("email", p.email.trim()).putString("phone", p.phone.trim()).putString("address", p.address.trim())
            .putString("city", p.city.trim()).putString("state", p.state.trim()).putString("zip", p.zip.trim()).apply()
    }
}
