package com.saaspaymentsolutions.axion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChatMessagePositionMapperTest {
    @Test
    public void disabledAdsKeepEveryMessagePositionUnchanged() {
        for (int position = 0; position < 14; position++) {
            assertEquals(position,
                    ChatMessagePositionMapper.messageIndexInPage(position, false, 8));
            assertEquals(position,
                    ChatMessagePositionMapper.adapterPosition(position, false, 8));
        }
    }

    @Test
    public void enabledAdsMapPositionsAroundRepeatedAdSlots() {
        assertEquals(7, ChatMessagePositionMapper.messageIndexInPage(7, true, 8));
        assertEquals(8, ChatMessagePositionMapper.messageIndexInPage(9, true, 8));
        assertEquals(9, ChatMessagePositionMapper.adapterPosition(8, true, 8));
        assertEquals(11, ChatMessagePositionMapper.itemCount(10, true, 8));
        assertEquals(22, ChatMessagePositionMapper.itemCount(20, true, 8));
        assertTrue(ChatMessagePositionMapper.isAdPosition(8, 20, true, 8));
        assertTrue(ChatMessagePositionMapper.isAdPosition(17, 20, true, 8));
    }

    @Test
    public void noAdSlotExistsBeforeThreshold() {
        assertEquals(7, ChatMessagePositionMapper.itemCount(7, true, 8));
        assertFalse(ChatMessagePositionMapper.isAdPosition(8, 7, true, 8));
    }
}
