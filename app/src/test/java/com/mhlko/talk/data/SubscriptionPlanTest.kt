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

    @Test
    fun paidBadgesKeepCanonicalNamesWithoutAddingFeaturesAbovePro() {
        assertEquals(SubscriptionTier.Plus, subscriptionTierFromWire("plus"))
        assertEquals(SubscriptionTier.Pro, subscriptionTierFromWire("pro"))
        assertEquals(SubscriptionTier.Ultimate, subscriptionTierFromWire("ultimate"))
        assertEquals(SubscriptionTier.MaxSupporter, subscriptionTierFromWire("max_supporter"))
        assertEquals(subscriptionEntitlements(SubscriptionTier.Free), subscriptionEntitlements(SubscriptionTier.Ultimate))
        assertEquals(subscriptionEntitlements(SubscriptionTier.Free), subscriptionEntitlements(SubscriptionTier.MaxSupporter))
        assertTrue(SubscriptionTier.Pro.isPaid())
        assertFalse(SubscriptionTier.Ultimate.isPaid())
        assertFalse(SubscriptionTier.MaxSupporter.isPaid())
        assertTrue(SubscriptionTier.Ultimate.hasMembershipBadge())
        assertFalse(SubscriptionTier.Free.isPaid())
    }
}
