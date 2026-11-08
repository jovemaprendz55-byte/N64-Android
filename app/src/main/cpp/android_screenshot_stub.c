/*
 * Android frontend shim for the upstream screenshot API.
 * PNG screenshot encoding is intentionally disabled until libpng is added.
 * The upstream screenshot implementation remains available under GPL-2.0-or-later.
 */
#include "main/screenshot.h"

void ScreenshotRomOpen(void)
{
}

void TakeScreenshot(int iFrameNumber)
{
    (void) iFrameNumber;
}
