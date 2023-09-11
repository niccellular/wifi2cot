
package com.atakmap.android.wifi2cot.plugin;
import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import com.atakmap.android.wifi2cot.wifi2cotMapComponent;
import com.atakmap.coremap.log.Log;

public class wifi2cotLifecycle extends AbstractPlugin implements IPlugin {

    private final static String TAG = "wifi2cotLifecycle";

    public wifi2cotLifecycle(IServiceController serviceController) {
        super(serviceController, new wifi2cotTool(serviceController.getService(PluginContextProvider.class).getPluginContext()), new wifi2cotMapComponent());
        Log.d(TAG, "wifi2cotLifcycle");
    }
}