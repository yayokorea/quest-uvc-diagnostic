package com.yayo.questuvc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeUvcTest {
    @Test fun acceptsPositiveAndTaggedPointerHandles() {
        assertTrue(isValidNativeHandle(0x00000070fdfa1630L))
        assertTrue(isValidNativeHandle(0xb4000070fdfa1630UL.toLong()))
    }

    @Test fun rejectsNullAndNativeErrorCodes() {
        assertFalse(isValidNativeHandle(0L))
        assertFalse(isValidNativeHandle(-1L))
        assertFalse(isValidNativeHandle(-99L))
        assertFalse(isValidNativeHandle(-4095L))
    }
}
