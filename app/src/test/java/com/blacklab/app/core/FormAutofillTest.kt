package com.blacklab.app.core

import com.blacklab.app.core.FormAutofill.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class FormAutofillTest {
    @Test fun recognizesCommonFieldNames() {
        val cases = mapOf(
            "Applicant_LastName" to Slot.LAST_NAME, "FirstName" to Slot.FIRST_NAME, "txtEmail" to Slot.EMAIL, "Email Address" to Slot.EMAIL,
            "Phone Number" to Slot.PHONE, "Cell" to Slot.PHONE, "Zip Code" to Slot.ZIP, "State" to Slot.STATE, "City" to Slot.CITY,
            "Street Address" to Slot.ADDRESS, "Company Name" to Slot.ORGANIZATION, "Employer" to Slot.ORGANIZATION, "Job Title" to Slot.TITLE,
            "Printed Name" to Slot.FULL_NAME, "Employee Name" to Slot.FULL_NAME, "Date" to Slot.DATE, "Date of Signature" to Slot.DATE,
            "Signature Date" to Slot.DATE, "Employee Signature" to Slot.SIGNATURE, "Sig1" to Slot.SIGNATURE, "Initials" to Slot.INITIALS,
        )
        cases.forEach { (name, slot) -> assertEquals(name, slot, FormAutofill.classify(name)) }
    }

    @Test fun leavesUnrelatedFieldsAlone() {
        listOf("Date of Birth", "Statement", "Comments", "Amount", "Document Title", "Q7").forEach { assertNull(it, FormAutofill.classify(it)) }
    }

    @Test fun tooltipHelpsWhenTheNameIsCryptic() {
        assertEquals(Slot.EMAIL, FormAutofill.classify("Text12", "Your e-mail address"))
    }

    @Test fun valuesComeFromTheProfile() {
        val p = FormAutofill.Profile(fullName = "Paul A Moore", email = "me@example.com")
        assertEquals("Paul", FormAutofill.valueFor(Slot.FIRST_NAME, p))
        assertEquals("Moore", FormAutofill.valueFor(Slot.LAST_NAME, p))
        assertEquals("11/01/2026", FormAutofill.valueFor(Slot.DATE, p, LocalDate.of(2026, 11, 1)))
        assertEquals("", FormAutofill.valueFor(Slot.SIGNATURE, p))
    }
}
