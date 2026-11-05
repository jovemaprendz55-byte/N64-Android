package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64HleR4300MemoryExceptionClassifierTest {
    private val classifier = N64HleR4300MemoryExceptionClassifier()

    @Test fun alignedLoadFaultMapsToAddressLoadAndPreservesBadAddress() {
        val address = 0xFFFFFFFF80000101UL.toLong()
        val fault = N64HleR4300MemoryAccessFault(
            N64HleR4300MemoryAccessKind.LOAD,
            N64HleGuestAddressAlignmentFault(address, 4)
        )

        val result = classifier.classify(fault)

        assertEquals(N64HleR4300ExceptionCode.ADDRESS_LOAD, result.code)
        assertEquals(address, result.badVirtualAddress)
    }

    @Test fun alignedStoreFaultMapsToAddressStoreAndPreservesBadAddress() {
        val address = 0x80000102L
        val fault = N64HleR4300MemoryAccessFault(
            N64HleR4300MemoryAccessKind.STORE,
            N64HleGuestAddressAlignmentFault(address, 4)
        )

        val result = classifier.classify(fault)

        assertEquals(N64HleR4300ExceptionCode.ADDRESS_STORE, result.code)
        assertEquals(address, result.badVirtualAddress)
    }

    @Test fun unmappedLoadAndStoreMapToDataBusError() {
        val address = 0x80800000L
        for (kind in N64HleR4300MemoryAccessKind.values()) {
            val fault = N64HleR4300MemoryAccessFault(
                kind,
                N64HleGuestAddressMappingFault(address, "outside mapped RDRAM")
            )

            val result = classifier.classify(fault)

            assertEquals(N64HleR4300ExceptionCode.DATA_BUS_ERROR, result.code)
            assertEquals(address, result.badVirtualAddress)
        }
    }
}
