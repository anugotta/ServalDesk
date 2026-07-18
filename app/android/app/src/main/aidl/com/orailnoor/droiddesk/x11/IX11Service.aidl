package com.orailnoor.droiddesk.x11;

import android.os.ParcelFileDescriptor;

/** Cross-process boundary between the X server and LorieView client. */
interface IX11Service {
    boolean startServer();
    ParcelFileDescriptor getXConnection();
    ParcelFileDescriptor getLogcatOutput();
}
