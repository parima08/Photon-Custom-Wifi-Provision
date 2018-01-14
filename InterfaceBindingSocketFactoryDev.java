package com.ciklum.pigabstractionlayer.WifiProvisioning;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.Build;

import java.io.IOException;
import java.net.Socket;

import io.particle.android.sdk.devicesetup.commands.CeciNestPasUnSocketFactory;
import io.particle.android.sdk.utils.TLog;
import io.particle.android.sdk.utils.WiFi;


/**
 * Factory for Sockets which binds communication to a particular {@link android.net.Network}
 */
public class InterfaceBindingSocketFactoryDev implements CeciNestPasUnSocketFactory {

    private static final TLog log = TLog.get(InterfaceBindingSocketFactoryDev.class);

    private final Context ctx;
    private final String softAPSSID;

    // FIXME: bad design, fix in next release
    public InterfaceBindingSocketFactoryDev(Context ctx) {
        // just use whatever we're connected to now
        this(ctx, WiFi.getCurrentlyConnectedSSID(ctx));
    }

    public InterfaceBindingSocketFactoryDev(Context ctx, String softAPSSID) {
        this.ctx = ctx.getApplicationContext();
        this.softAPSSID = softAPSSID;
    }

    public Socket buildSocket(int readTimeoutMillis) throws IOException {
        Socket socket = new Socket();
        socket.setSoTimeout(readTimeoutMillis);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                bindSocketToSoftAp(socket);
            } catch (SocketBindingException e) {
                log.i("Unable to bind to the socket; connection is probably going to fail...");
            }
        }
        return socket;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void bindSocketToSoftAp(Socket socket) throws SocketBindingException, IOException {
        ConnectivityManager connMan = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network softAp = null;
        for (Network network : connMan.getAllNetworks()) {
            log.i("Inspecting network:  " + network);
            NetworkInfo networkInfo = connMan.getNetworkInfo(network);
            log.i("Inspecting network info:  " + networkInfo);
            // Android doesn't have any means of directly
            // asking "I want the Network obj for the Wi-Fi network with SSID <foo>".
            // Instead, you have to infer it.  Let's hope that getExtraInfo() doesn't
            // ever change...
            String dequotifiedNetworkExtraSsid = WiFi.deQuotifySsid(networkInfo.getExtraInfo());
            String dequotifiedTargetSsid = WiFi.deQuotifySsid(softAPSSID);
            log.i("Network extra info: '" + dequotifiedNetworkExtraSsid + "'");
            log.i("And the SSID we were to connect to: '" + dequotifiedTargetSsid + "'");
            if (dequotifiedTargetSsid.equalsIgnoreCase(dequotifiedNetworkExtraSsid)) {
                softAp = network;
                break;
            }
        }

        if (softAp == null) {
            // If this ever fails, fail VERY LOUDLY to make sure we hear about it...
            throw new SocketBindingException("Could not find Network for SSID " + softAPSSID);
        }

        log.i("About to bind the Socket");

        softAp.bindSocket(socket);
    }

    private static class SocketBindingException extends Exception {

        SocketBindingException(String msg) {
            super(msg);
        }
    }

}
