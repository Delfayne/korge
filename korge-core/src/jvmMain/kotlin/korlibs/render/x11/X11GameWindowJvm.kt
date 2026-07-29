package korlibs.render.x11

import com.sun.jna.*
import com.sun.jna.platform.unix.X11.*
import korlibs.event.*
import korlibs.graphics.gl.*
import korlibs.image.bitmap.*
import korlibs.kgl.*
import korlibs.math.*
import korlibs.memory.*
import korlibs.render.*

class X11GameWindow(checkGl: Boolean) : EventLoopGameWindow() {
    override val dialogInterface: DialogInterface = ZenityDialogs()

    override val ag: AGOpengl = AGOpengl(X11KmlGl().checkedIf(checkGl))
    override var width: Int = 200; private set
    override var height: Int = 200; private set
    override var title: String = "Korgw"
        set(value) {
            field = value
            realSetTitle(value)
        }
    override var icon: Bitmap? = null
        set(value) {
            field = value
            realSetIcon(value)
        }
    override var fullscreen: Boolean = false
        set(value) {
            field = value
            realSetFullscreen(value)
        }
    override var visible: Boolean = true
        set(value) {
            field = value
            realSetVisible(value)
        }
    override var quality: Quality = Quality.AUTOMATIC

    override fun setSize(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    var d: Display? = null
    var root: Window? = null
    val NilWin: Window? = null
    var w: Window? = null
    var s: Int = 0

    fun realSetTitle(title: String): Unit = X.run {
        if (d == null || w == NilWin) return@run
        //X.XSetWMIconName(d, w, )
        X.XStoreName(d, w, title)
        X.XSetIconName(d, w, title)
    }

    fun realSetIcon(value: Bitmap?): Unit = X.run {
        if (d == null || w == NilWin || value == null) return@run
        val property = XInternAtom(d, "_NET_WM_ICON", false)
        val bmp = value.toBMP32()
        val VSIZE = NativeLong.SIZE
        val bytes = ByteArray((bmp.area + 2) * VSIZE)
        bytes.set32LE(0, bmp.width)
        bytes.set32LE(VSIZE, bmp.height)
        for (n in 0 until bmp.area) {
            val pos = VSIZE * (2 + n)
            val c = bmp.getRgbaAtIndex(n)
            bytes[pos + 0] = c.r.toByte()
            bytes[pos + 1] = c.g.toByte()
            bytes[pos + 2] = c.b.toByte()
            bytes[pos + 3] = c.a.toByte()
        }
        val mem = Memory((bytes.size * 8).toLong())
        mem.write(0L, bytes, 0, bytes.size)
        XChangeProperty(
            d, w, property, XA_CARDINAL, 32, PropModeReplace,
            mem, bytes.size / NativeLong.SIZE
        )
    }

    // https://stackoverflow.com/questions/9065669/x11-glx-fullscreen-mode
    fun realSetFullscreen(value: Boolean): Unit = X.run {
        if (d == null || w == NilWin) return@run
    }
    fun realSetVisible(value: Boolean): Unit = X.run {
        if (d == null || w == NilWin) return@run
    }

    override fun close(exitCode: Int) {
        super<EventLoopGameWindow>.close(exitCode)
    }

    // https://github.com/AlexeyAB/SDL-OculusRift/blob/master/src/video/x11/SDL_x11opengl.c
    override fun doInitialize() {
        d = X.XOpenDisplay(null) ?: error("Can't open main display")
        s = X.XDefaultScreen(d)
        root = X.XDefaultRootWindow(d)

        val vi = X11OpenglContext.chooseVisuals(d, s) ?: error("Can't choose a GLX visual")
        // `XVisualInfo` (the typealias in this package) is a raw Pointer: wrap it in the real JNA struct
        // to read its fields, since glXChooseVisual/glXCreateContext only need the opaque pointer but
        // XCreateColormap/XCreateWindow below need the actual visual/depth/visualid values.
        val viStruct = com.sun.jna.Structure.newInstance(com.sun.jna.platform.unix.X11.XVisualInfo::class.java, vi)
            .also { it.read() }

        val screenWidth = X.XDisplayWidth(d, s)
        val screenHeight = X.XDisplayHeight(d, s)

        val gameWindow = this@X11GameWindow

        val winX = screenWidth / 2 - width / 2
        val winY = screenHeight / 2 - height / 2

        println("screenWidth: $screenWidth, screenHeight: $screenHeight, winX=$winX, winY=$winY, width=$width, height=$height")

        val eventMask = NativeLong(
            (ExposureMask
                or StructureNotifyMask
                or EnterWindowMask
                or LeaveWindowMask
                or KeyPressMask
                or KeyReleaseMask
                or PointerMotionMask
                or ButtonPressMask
                or ButtonReleaseMask
                or ButtonMotionMask
                )
                .toLong()
        )

        // The window is built with XCreateWindow against a colormap constructed from the chosen GLX
        // visual, not XCreateSimpleWindow (which always uses the screen's default visual/colormap,
        // ignoring the one glXChooseVisual picked). The Khronos GLX context-creation tutorial linked
        // above explicitly documents XCreateSimpleWindow as unusable for this reason. background_pixel
        // (and CWBackPixel in the value mask) is likewise required; without it the X server never
        // performs an initial paint on the window, matching what a real GLX reference client
        // (mesa-demos' glxgears) does in its own make_window().
        val cmap = X.XCreateColormap(d, X.XRootWindow(d, s), viStruct.visual, AllocNone)
        val attrs = XSetWindowAttributes().apply {
            colormap = cmap
            event_mask = eventMask
            border_pixel = NativeLong(0)
            background_pixel = NativeLong(0)
        }

        w = X.XCreateWindow(
            d!!, X.XRootWindow(d, s),
            winX, winY,
            width, height,
            0, viStruct.depth, InputOutput, viStruct.visual,
            NativeLong((CWBackPixel or CWColormap or CWEventMask or CWBorderPixel).toLong()),
            attrs
        )

        X.XSelectInput(d, w, eventMask)
        X.XMapWindow(d, w)
        realSetIcon(icon)
        realSetVisible(fullscreen)
        realSetVisible(visible)
        realSetTitle(title)

        ctx = X11OpenglContext(gameWindow, d, w, s, vi, doubleBuffered = doubleBuffered)
        ctx.makeCurrent()

        val wmDeleteMessage = X.XInternAtom(d, "WM_DELETE_WINDOW", false)
        if (wmDeleteMessage != null) {
            X.XSetWMProtocols(d, w, arrayOf(wmDeleteMessage), 1)
        }
    }

    val doubleBuffered = false
    //val doubleBuffered = true
    lateinit var ctx: X11OpenglContext

    override fun doSwapBuffers() {
        //println("doSwapBuffers")
        ctx.swapBuffers()
    }

    interface glXSwapIntervalEXTCallback : Callback {
        fun callback(dpy: Display?, draw: Pointer, value: Int)
    }

    private var glXSwapIntervalEXTSet: Boolean = false
    private var swapIntervalEXT: glXSwapIntervalEXTCallback? = null
    private var swapIntervalEXTPointer: Pointer? = null

    override fun doInitRender() {
        ctx.makeCurrent()

        if (!glXSwapIntervalEXTSet) {
            glXSwapIntervalEXTSet = true
            swapIntervalEXTPointer = X.glXGetProcAddress("glXSwapIntervalEXT")

            swapIntervalEXT = when {
                swapIntervalEXTPointer != Pointer.NULL -> CallbackReference.getCallback(glXSwapIntervalEXTCallback::class.java, swapIntervalEXTPointer) as? glXSwapIntervalEXTCallback?
                else -> null
            }
            println("swapIntervalEXT: $swapIntervalEXT")
        }

        val dpy = X.glXGetCurrentDisplay()
        val drawable = X.glXGetCurrentDrawable()
        swapIntervalEXT?.callback(dpy, drawable, vsync.toInt())
        //swapIntervalEXT?.callback(dpy, drawable, 0)
        //swapIntervalEXT?.callback(dpy, drawable, 1)
        //glXSwapIntervalEXT?.callback(dpy, drawable, 0)

        X.glViewport(0, 0, width, height)
        X.glClearColor(.3f, .6f, .3f, 1f)
        X.glClear(GL.GL_COLOR_BUFFER_BIT)
    }

    override fun doDestroy() {
        X.XDestroyWindow(d, w)
        X.XCloseDisplay(d)
    }

    val e = XEvent()
    // Scratch buffer for XPeekEvent, kept separate from `e` so peeking ahead doesn't clobber the
    // event currently being handled.
    private val peekEvent = XEvent()

    override fun doHandleEvents() {
        loop@ while (running) {
            if (X.XPending(d) == 0) return
            X.XNextEvent(d, e)
            when (e.type) {
                // Only *request* a redraw; do not render synchronously here. render() ends in
                // doSwapBuffers(), which blocks until vblank, and this runs inside the event-drain
                // loop, so a resize (which interleaves an Expose with every ConfigureNotify) would
                // block ~16.7ms per queued Expose before any other event, including window-close,
                // could be handled. A few hundred of them across one drag is several seconds of a
                // frozen, frame-by-frame "catching up" resize. invalidatedView() sets the flag
                // mustTriggerRender already reads, so the main loop redraws on its next iteration.
                Expose -> if (e.xexpose.count == 0) invalidatedView()
                ClientMessage, DestroyNotify -> close()
                ConfigureNotify -> {
                    val conf = XConfigureEvent(e.pointer)
                    width = conf.width
                    height = conf.height
                    // An interactive resize fires a ConfigureNotify per pixel-step of the drag,
                    // often hundreds of events for one gesture, arriving back-to-back within the
                    // same doHandleEvents() call. Only dispatch the (comparatively expensive)
                    // reshape, which cascades through Views.resized() and everything listening for
                    // it, for the *last* ConfigureNotify in a run; superseded intermediate ones are
                    // skipped. width/height above are still updated on every event, so
                    // doInitRender's glViewport call, which runs every frame regardless, always
                    // sees the latest size even between dispatches.
                    val supersededByNextEvent = X.XPending(d) > 0 && run {
                        X.XPeekEvent(d, peekEvent)
                        peekEvent.type == ConfigureNotify
                    }
                    if (!supersededByNextEvent) {
                        // conf.x/conf.y are the window's position relative to its parent (the window
                        // manager's reparenting frame after mapping): the screen position, not a
                        // rendering offset. Passing them here used to flow straight into
                        // AGFrameBuffer's x/y and from there into glViewport(x, y, w, h), placing the
                        // actual GL viewport hundreds of pixels outside the window's own 0,0-w,h
                        // drawable: every frame still rendered and presented successfully (verified
                        // via the X Present protocol and zero GL errors), just entirely off-screen,
                        // until an interactive resize's fluctuating x/y coordinates happened to land
                        // closer to correct by chance. A top-level window's own drawable origin is
                        // always (0, 0), regardless of where the window manager placed it on screen.
                        // glxgears's own ConfigureNotify handler only ever passes width/height to
                        // reshape(), never x/y, for this exact reason.
                        dispatchReshapeEvent(0, 0, width, height)
                    }
                    // Deliberately not forcing a synchronous render() here. render() ends in
                    // doSwapBuffers(), which blocks until vblank, and this runs inside the
                    // event-drain loop, so one blocking render per queued resize event serialises
                    // the whole drag at one frame each. continuousRenderMode is on by default, so
                    // the main loop already renders every frame regardless; the state update above
                    // is picked up on its very next iteration. glxgears's own ConfigureNotify
                    // handler works the same way, updating reshape state only and leaving the
                    // actual draw to its normal frame loop.
                }
                KeyPress, KeyRelease -> {
                    val pressing = e.type == KeyPress
                    val ev =
                        if (pressing) korlibs.event.KeyEvent.Type.DOWN else korlibs.event.KeyEvent.Type.UP
                    val keyCode = XKeyEvent(e.pointer).keycode.toInt()
                    val kkey = XK_KeyMap[X.XLookupKeysym(e, 0)] ?: Key.UNKNOWN
                    //println("KEY: $ev, ${keyCode.toChar()}, $kkey, $keyCode, keySym=$keySym")
                    dispatchKeyEvent(ev, 0, keyCode.toChar(), kkey, keyCode)
                    //break@loop
                }
                MotionNotify, ButtonPress, ButtonRelease -> {
                    val mot = MyXMotionEvent(e.pointer)
                    //val mot = e.xmotion
                    val but = e.xbutton

                    val isShiftDown = false
                    val isCtrlDown = false
                    val isAltDown = false
                    val isMetaDown = false
                    val evType = when (e.type) {
                        MotionNotify -> MouseEvent.Type.MOVE
                        ButtonPress -> MouseEvent.Type.DOWN
                        ButtonRelease -> MouseEvent.Type.UP
                        else -> MouseEvent.Type.MOVE
                    }

                    //in 4..7 -> MouseButton.LEFT // 4=WHEEL_UP, 5=WHEEL_DOWN, 6=WHEEL_LEFT, 7=WHEEL_RIGHT!
                    val scrollDeltaX: Float = when (mot.button) {
                        6 -> -1f
                        7 -> +1f
                        else -> 0f
                    }
                    val scrollDeltaY: Float = when (mot.button) {
                        4 -> -1f
                        5 -> +1f
                        else -> 0f
                    }
                    val scrollDeltaZ: Float = 0f

                    val button = when (mot.button) {
                        1 -> MouseButton.LEFT
                        2 -> MouseButton.MIDDLE
                        3 -> MouseButton.RIGHT
                        in 4..7 -> MouseButton.BUTTON_WHEEL
                        else -> MouseButton.BUTTON_UNKNOWN
                    }

                    val realEvType = when (button) {
                        MouseButton.BUTTON_WHEEL -> {
                            when (evType) {
                                MouseEvent.Type.DOWN -> MouseEvent.Type.SCROLL
                                else -> null
                            }
                        }
                        else -> evType
                    }

                    if (realEvType != null) {
                        dispatchMouseEvent(
                            realEvType, 0, mot.x, mot.y,
                            button, 0,
                            scrollDeltaX, scrollDeltaY, scrollDeltaZ, isShiftDown, isCtrlDown, isAltDown, isMetaDown, false,
                            simulateClickOnUp = true
                        )
                    }
                }
                else -> {
                    //println("OTHER EVENT ${e.type}")
                }
            }
        }
    }
}
