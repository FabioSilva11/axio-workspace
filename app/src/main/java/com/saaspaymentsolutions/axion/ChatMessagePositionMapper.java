package com.saaspaymentsolutions.axion;

/**
 * Converts positions between the RecyclerView and its message-only snapshot.
 * Ad slots repeat at a fixed interval while ads are enabled.
 */
final class ChatMessagePositionMapper {
    private ChatMessagePositionMapper() {
    }

    static int messageIndexInPage(int adapterPosition, boolean showAds, int adEvery) {
        if (!showAds) {
            return adapterPosition;
        }
        return adapterPosition - adapterPosition / (adEvery + 1);
    }

    static int itemCount(int messageCount, boolean showAds, int adEvery) {
        if (!showAds) {
            return messageCount;
        }
        return messageCount + messageCount / adEvery;
    }

    static int adapterPosition(int messageIndexInPage, boolean showAds, int adEvery) {
        if (!showAds) {
            return messageIndexInPage;
        }
        return messageIndexInPage + messageIndexInPage / adEvery;
    }

    static boolean isAdPosition(
            int adapterPosition,
            int messageCount,
            boolean showAds,
            int adEvery
    ) {
        return showAds
                && adapterPosition > 0
                && adapterPosition < itemCount(messageCount, true, adEvery)
                && adapterPosition % (adEvery + 1) == adEvery;
    }
}
