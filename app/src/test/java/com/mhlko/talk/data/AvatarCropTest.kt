package com.mhlko.talk.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarCropTest {
    @Test
    fun centeredCropUsesShortImageEdge() {
        assertEquals(
            AvatarCropRect(left = 500, top = 0, side = 1000),
            calculateAvatarCrop(2000, 1000, AvatarCropSelection()),
        )
    }

    @Test
    fun zoomAndPanStayInsideTheImage() {
        assertEquals(
            AvatarCropRect(left = 0, top = 1000, side = 500),
            calculateAvatarCrop(
                width = 1000,
                height = 1500,
                selection = AvatarCropSelection(zoom = 2f, offsetX = 1f, offsetY = -1f),
            ),
        )
    }

    @Test
    fun transformValuesAreClamped() {
        assertEquals(
            AvatarCropRect(left = 0, top = 750, side = 250),
            calculateAvatarCrop(
                width = 1000,
                height = 1000,
                selection = AvatarCropSelection(zoom = 99f, offsetX = 99f, offsetY = -99f),
            ),
        )
    }
}
