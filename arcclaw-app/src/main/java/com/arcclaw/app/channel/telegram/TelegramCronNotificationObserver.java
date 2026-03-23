package com.arcclaw.app.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;


/**
 * CDI observer that delivers cron job results to Telegram users.
 */
@ApplicationScoped
@Deprecated
public class TelegramCronNotificationObserver {
    // Replaced by ChannelCronNotificationDispatcher and TelegramJobNotifier
}
