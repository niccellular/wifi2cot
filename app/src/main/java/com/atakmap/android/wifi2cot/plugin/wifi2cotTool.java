
package com.atakmap.android.wifi2cot.plugin;

import android.content.Context;

import com.atak.plugins.impl.AbstractPluginTool;
import gov.tak.api.util.Disposable;

public class wifi2cotTool extends AbstractPluginTool implements Disposable {

    private final static String TAG = "wifi2cotTool";

    public wifi2cotTool(Context context) {
        super(context,
                context.getString(R.string.app_name),
                context.getString(R.string.app_name),
                context.getResources().getDrawable(R.drawable.ic_launcher),
                "com.atakmap.android.wifi2cot.SHOW_PLUGIN");
        PluginNativeLoader.init(context);
    }

    @Override
    public void dispose() {
    }

}