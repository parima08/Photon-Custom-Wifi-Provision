package com.ciklum.pigabstractionlayer.WifiProvisioning;

import com.ciklum.pigabstractionlayer.PiggyBank;

/**
 * WifiProvisionCallback
 *
 * This interface provides callbacks for the UI layer to receive feedback as what point in the
 * provisioning process the PAL library has reached.
 *
 * All of these
 */
public interface WifiProvisionCallback {

    void onTwoLeggedAuth();

    void onSoftApConnected();

    void onDeviceInfoReturned();

    void onSuccess(PiggyBank pig);

    void onFailure(Exception e);
}
