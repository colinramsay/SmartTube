package com.liskovsoft.smartyoutubetv2.common.localrecs;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.R;

/**
 * Build-time switches. Each flavour sets them by overriding the bool resources in localrecs.xml.
 */
public class LocalRecsFlags {
    /**
     * True in our own build so it never offers to install official SmartTube releases over itself.
     */
    public static boolean isUpdaterDisabled(Context context) {
        return context != null && context.getResources().getBoolean(R.bool.localrecs_updater_disabled);
    }
}
