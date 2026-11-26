package br.com.c6tag.diagnostico

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationIdentityTest {
    @Test
    fun applicationIdUsesTheTagAlertIdentity() {
        assertEquals("br.com.tagalert.app", BuildConfig.APPLICATION_ID)
    }
}
