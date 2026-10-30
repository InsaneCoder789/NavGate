package com.rohanc.navgate.ui.journey

import org.junit.Assert.assertEquals
import org.junit.Test

class SocialLabelTest {
    @Test fun numericFacebookPageIsJustNamed() = assertEquals("Facebook", socialLabel("https://www.facebook.com/182719182431276"))
    @Test fun shortHandleIsKept() = assertEquals("Instagram · kiitlibrary", socialLabel("https://instagram.com/kiitlibrary/"))
    @Test fun unknownHostShowsAddress() = assertEquals("example.org/page", socialLabel("https://www.example.org/page"))
}
