package com.yunx.app.ui.jcef

import org.cef.browser.mac.CefBrowserWindowMac
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Canvas

class MacWindowAccessTest {
    @Test(timeout = 10000)
    fun `JCEF window bridge can access AWT internals on macOS`() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        JcefHolder.checkMacWindowAccess()
        // An unrealized canvas has no handle, but exercises the AWTAccessor call
        // that previously threw IllegalAccessError and cancelled the UI renderer.
        assertEquals(0L, CefBrowserWindowMac().getWindowHandle(Canvas()))
    }
}
