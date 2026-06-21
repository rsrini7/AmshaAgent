package com.amsha.channel.api.hitl;

public interface ApprovalNotifier {
    void flushPendingNotifications(String chatId);
}
