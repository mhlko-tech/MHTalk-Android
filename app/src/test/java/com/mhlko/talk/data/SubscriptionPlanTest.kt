package com.mhlko.talk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionPlanTest {
    @Test
    fun freePlanCapsVideoAndAttachments() {
        val plan = subscriptionEntitlements(SubscriptionTier.Free)
        assertEquals(ShareQuality.Medium, plan.maxCameraQuality)
        assertEquals(ShareQuality.Medium, plan.maxScreenShareQuality)
        assertEquals(20L * 1024 * 1024, plan.maxAttachmentBytes)
        assertFalse(plan.animatedProfile)
    }

    @Test
    fun plusPlanUnlocksQualityAndAppearance() {
        val plan = subscriptionEntitlements(SubscriptionTier.Plus)
        assertEquals(ShareQuality.High, plan.maxCameraQuality)
        assertEquals(100L * 1024 * 1024, plan.maxAttachmentBytes)
        assertTrue(plan.animatedProfile)
        assertTrue(plan.customAppearance)
    }
}
