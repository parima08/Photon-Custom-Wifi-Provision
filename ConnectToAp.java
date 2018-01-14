package com.ciklum.pigabstractionlayer.WifiProvisioning;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;

import com.ciklum.pigabstractionlayer.PiggyBank;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.particle.android.sdk.utils.SoftAPConfigRemover;
import io.particle.android.sdk.utils.TLog;
import io.particle.android.sdk.utils.WiFi;

import static io.particle.android.sdk.utils.Py.list;
import static io.particle.android.sdk.utils.Py.truthy;


/**
 * ConnectToAp:
 * This class is attempts to establish a connection to the Photon based on the SSID supplied by the
 * user. Once a successful connection is made, it returns to the client with two callbacks -
 * onApConnectionSuccessful and onApConnectionFailed.
 * If it failed, it will continue to retry until the maximum attempts is reached.
 *
 */

public class ConnectToAp {


    public interface Client {

        void onApConnectionSuccessful(WifiConfiguration config, Context context);

        void onApConnectionFailed(WifiConfiguration config, Context context);

    }


    public static final String TAG = "ConnectToAp";
    private static final TLog log = TLog.get(ConnectToAp.class);


    public static WifiConfiguration buildUnsecuredConfig(String ssid, boolean isHidden) {
        WifiConfiguration config = buildBasicConfig(ssid, isHidden);
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        // have to set a very high number in order to ensure that Android doesn't
        // immediately drop this connection and reconnect to the a different AP
        config.priority = 999999;
        return config;
    }

    private WifiManager wifiManager;
    private BroadcastReceiver wifiStateChangeListener;
    private WifiStateChangeLogger wifiStateChangeLogger;

    private ClientDecorator client;
    private SoftAPConfigRemover softAPConfigRemover;

    // for handling through the runloop
    private Handler mainThreadHandler;
    private Runnable onTimeoutRunnable;
    private final List<Runnable> setupRunnables = list();


    public ConnectToAp(Context context, Client callback, Handler threadHandler){
        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        softAPConfigRemover = new SoftAPConfigRemover(context);
        mainThreadHandler = threadHandler;
        wifiStateChangeLogger = new WifiStateChangeLogger();
        client = new ClientDecorator();
        client.setDecoratedClient(callback);
    }



    /**
     * Connect this Android device to the specified AP.
     *
     * @param config the WifiConfiguration defining which AP to connect to
     * @param timeoutInMillis how long to wait before timing out
     *
     * @return the SSID that was connected prior to calling this method.  Will be null if
     *          there was no network connected, or if already connected to the target network.
     */
    public String connectToAP(final WifiConfiguration config, long timeoutInMillis, final Context appContext) {
        // cancel any currently running timeout, etc
        clearState(appContext);

        final WifiInfo currentConnectionInfo = wifiManager.getConnectionInfo();
        // are we already connected to the right AP?  (this could happen on retries)
        if (isAlreadyConnectedToTargetNetwork(currentConnectionInfo, config.SSID)) {
            // we're already connected to this AP, nothing to do.
            client.onApConnectionSuccessful(config, appContext);
            return null;
        }

        scheduleTimeoutCheck(timeoutInMillis, config, appContext);
        wifiStateChangeListener = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                onWifiChangeBroadcastReceived(intent, config, appContext);
            }
        };

        appContext.registerReceiver(wifiStateChangeListener,
                new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));

        final boolean useMoreComplexConnectionProcess = Build.VERSION.SDK_INT < 18;


        // we don't need this for its atomicity, we just need it as a 'final' reference to an
        // integer which can be shared by a couple of the Runnables below
        final AtomicInteger networkID = new AtomicInteger(-1);

        // everything below is created in Runnables and scheduled on the runloop to avoid some
        // wonkiness I ran into when trying to do every one of these steps one right after
        // the other on the same thread.

        final int alreadyConfiguredId = WiFi.getConfiguredNetworkId(config.SSID, appContext);
        if (alreadyConfiguredId != -1 && !useMoreComplexConnectionProcess) {
            // For some unexplained (and probably sad-trombone-y) reason, if the AP specified was
            // already configured and had been connected to in the past, it will often get to
            // the "CONNECTING" event, but just before firing the "CONNECTED" event, the
            // WifiManager appears to change its mind and reconnects to whatever configured and
            // available AP it feels like.
            //
            // As a remedy, we pre-emptively remove that config.  *shakes fist toward Mountain View*

            setupRunnables.add(new Runnable() {
                @Override
                public void run() {
                    if (wifiManager.removeNetwork(alreadyConfiguredId)) {
                        if(PiggyBank.DEBUG) {
                            log.d("Removed already-configured " + config.SSID + " network successfully");
                        }
                    } else {
                        if(PiggyBank.DEBUG) {
                            log.e("Somehow failed to remove the already-configured network!?");
                        }
                        // not calling this state an actual failure, since it might succeed anyhow,
                        // and if it doesn't, the worst case is a longer wait to find that out.
                    }
                }
            });
        }

        if (alreadyConfiguredId == -1 || !useMoreComplexConnectionProcess) {
            setupRunnables.add(new Runnable() {
                @Override
                public void run() {
                    if(PiggyBank.DEBUG) {
                        log.d("Adding network " + config.SSID);
                    }
                    networkID.set(wifiManager.addNetwork(config));
                    if (networkID.get() == -1) {
                        if(PiggyBank.DEBUG) {
                            log.e("Adding network " + config.SSID + " failed.");
                        }
                        client.onApConnectionFailed(config, appContext);

                    } else {
                        if(PiggyBank.DEBUG) {
                            log.i("Added network with ID " + networkID + " successfully");
                        }
                    }
                }
            });
        }

        if (useMoreComplexConnectionProcess) {
            setupRunnables.add(new Runnable() {
                @Override
                public void run() {
                    log.d("Disconnecting from networks; reconnecting momentarily.");
                    wifiManager.disconnect();
                }
            });
        }

        setupRunnables.add(new Runnable() {
            @Override
            public void run() {
                log.i("Enabling network " + config.SSID + " with network ID " + networkID.get());
                wifiManager.enableNetwork(networkID.get(),
                        !useMoreComplexConnectionProcess);
            }
        });
        if (useMoreComplexConnectionProcess) {
            setupRunnables.add(new Runnable() {
                @Override
                public void run() {
                    log.d("Disconnecting from networks; reconnecting momentarily.");
                    wifiManager.reconnect();
                }
            });
        }

        String currentlyConnectedSSID = WiFi.getCurrentlyConnectedSSID(appContext);
        softAPConfigRemover.onWifiNetworkDisabled(currentlyConnectedSSID);

        long timeout = 0;
        for (Runnable runnable : setupRunnables) {
            mainThreadHandler.postDelayed(runnable, timeout);
            timeout += 1500;
        }

        return currentConnectionInfo.getSSID();
    }

    private static boolean isAlreadyConnectedToTargetNetwork(WifiInfo currentConnectionInfo,
                                                             String targetNetworkSsid) {
        return (isCurrentlyConnectedToAWifiNetwork(currentConnectionInfo)
                && targetNetworkSsid.equals(currentConnectionInfo.getSSID())
        );
    }

    private static boolean isCurrentlyConnectedToAWifiNetwork(WifiInfo currentConnectionInfo) {
        return (currentConnectionInfo != null
                && truthy(currentConnectionInfo.getSSID())
                && currentConnectionInfo.getNetworkId() != -1
                // yes, this happens.  Thanks, Android.
                && !"0x".equals(currentConnectionInfo.getSSID()));
    }

    private void scheduleTimeoutCheck(long timeoutInMillis, final WifiConfiguration config, final Context appContext) {
        onTimeoutRunnable = new Runnable() {

            @Override
            public void run() {
                client.onApConnectionFailed(config, appContext);
            }
        };
        mainThreadHandler.postDelayed(onTimeoutRunnable, timeoutInMillis);
    }

    private void clearState(Context appContext) {
        if (onTimeoutRunnable != null) {
            mainThreadHandler.removeCallbacks(onTimeoutRunnable);
            onTimeoutRunnable = null;
        }

        if (wifiStateChangeListener != null) {
            appContext.unregisterReceiver(wifiStateChangeListener);
            wifiStateChangeListener = null;
        }

        for (Runnable runnable : setupRunnables) {
            mainThreadHandler.removeCallbacks(runnable);
        }
        setupRunnables.clear();
    }

    private void onWifiChangeBroadcastReceived(Intent intent, WifiConfiguration config, Context appContext) {
        // this will only be present if the new state is CONNECTED
        WifiInfo wifiInfo = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
        if (wifiInfo == null || wifiInfo.getSSID() == null) {
            // no WifiInfo or SSID means we're not interested.
            return;
        }

        log.i("Connected to: " + wifiInfo.getSSID());
        String ssid = wifiInfo.getSSID();
        if (ssid.equals(config.SSID) || WiFi.enQuotifySsid(ssid).equals(config.SSID)) {
            // FIXME: find a way to record success in memory in case this happens to happen
            // during a config change (etc)?
            client.onApConnectionSuccessful(config, appContext);
        }
        //FIXME: CHECK HERE - if connected to the same SSID as before, then end wifi provisioing and exist.

    }

    private static WifiConfiguration buildBasicConfig(String ssid, boolean isHidden) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = WiFi.enQuotifySsid(ssid);
        config.hiddenSSID = isHidden;
        return config;
    }


    private class WifiStateChangeLogger extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            log.d("WifiStateChangeLogger - onRecieve");
            log.d("Received " + WifiManager.NETWORK_STATE_CHANGED_ACTION);
            log.d("EXTRA_NETWORK_INFO: " + intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO));
            // this will only be present if the new state is CONNECTED
            WifiInfo wifiInfo = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
            log.d("WIFI_INFO: " + wifiInfo);
        }

        IntentFilter buildIntentFilter() {
            return new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        }
    }


    private class ClientDecorator implements Client {

        Client decoratedClient;

        @Override
        public void onApConnectionSuccessful(WifiConfiguration config, Context context) {
            clearState(context);
            if (decoratedClient != null) {
                decoratedClient.onApConnectionSuccessful(config, context);
            }
        }

        @Override
        public void onApConnectionFailed(WifiConfiguration config, Context context) {
            clearState(context);
            if (decoratedClient != null) {
                decoratedClient.onApConnectionFailed(config, context);
            }
        }


        void setDecoratedClient(Client decoratedClient) {
            this.decoratedClient = decoratedClient;
        }

    }

}
