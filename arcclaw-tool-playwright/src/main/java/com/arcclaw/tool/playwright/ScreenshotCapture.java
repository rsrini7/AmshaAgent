package com.arcclaw.tool.playwright;

import java.util.Base64;

import jakarta.enterprise.context.ApplicationScoped;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ScreenshotScale;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ScreenshotCapture {

    @ConfigProperty(name = "arcclaw.tools.playwright.max-screenshot-bytes", defaultValue = "1048576")
    int maxScreenshotBytes;

    public String captureScreenshot(Page page) {
        byte[] png = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
        if (png.length > maxScreenshotBytes) {
            png = page.screenshot(new Page.ScreenshotOptions().setFullPage(false).setScale(ScreenshotScale.CSS));
        }
        return Base64.getEncoder().encodeToString(png);
    }
}
