package com.jsr.n64android

/** Internal signal translated by the stepper into architectural ExcCode 12. */
class N64HleR4300ArithmeticOverflow(val operation: String) :
    RuntimeException("$operation signed arithmetic overflow")
