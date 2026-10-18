package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test

class HeadingFilterTest {
    @Test fun `north crossing uses shortest circular delta and true north correction`() {
        val filter=HeadingFilter(0.5)
        assertEquals(359.0,filter.sample(357.0,2.0,100,true)!!,0.001)
        assertEquals(0.0,filter.sample(359.0,2.0,200,true)!!,0.001)
        assertEquals(1.0,filter.sample(0.0,2.0,300,true)!!,0.001)
    }
    @Test fun `stale unreliable or nonfinite headings never maintain alignment`() {
        val filter=HeadingFilter()
        filter.sample(90.0,0.0,100,true)
        assertNull(filter.current(2101));assertNull(filter.current(99))
        assertEquals(180.0,filter.sample(180.0,0.0,2200,true)!!,0.001)
        assertNull(filter.sample(180.0,0.0,2300,false));assertNull(filter.current(2300))
        assertNull(filter.sample(Double.NaN,0.0,2400,true))
        assertEquals(90.0,filter.sample(90.0,0.0,2500,true)!!,0.001)
        assertEquals(90.0,filter.sample(10.0,0.0,2499,true)!!,0.001)
    }
}
