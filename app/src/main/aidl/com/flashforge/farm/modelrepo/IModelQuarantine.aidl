package com.flashforge.farm.modelrepo;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IModelQuarantine {
    Bundle quarantine(in ParcelFileDescriptor fd, in String fileName, in long fileSize);
}
