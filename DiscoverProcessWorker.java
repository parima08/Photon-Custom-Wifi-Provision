package com.ciklum.pigabstractionlayer.WifiProvisioning;

import android.util.Log;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.security.PublicKey;

import io.particle.android.sdk.devicesetup.commands.CommandClient;
import io.particle.android.sdk.devicesetup.commands.DeviceIdCommand;
import io.particle.android.sdk.devicesetup.commands.PublicKeyCommand;
import io.particle.android.sdk.devicesetup.commands.SetCommand;
import io.particle.android.sdk.devicesetup.setupsteps.SetupStepException;
import io.particle.android.sdk.utils.Crypto;

import static io.particle.android.sdk.utils.Py.truthy;

/**
 * DiscoverProcessWorker
 * This class is called once a connection with the device has been established. This process worker
 * sends three codes to the device - it get the device id (devicePlatformId) from the device, pulls
 * the public key from the device, and sends the claim code (that we originally recieved from the
 * particle cloud in WifiSetupSate.setDeviceClaim() ) to the device. Once all three steps are done,
 * it returns to the class where is creates (ConnectToAp).
 */
public class DiscoverProcessWorker {

    public static final String TAG = "DiscoverProcessWorker";

    private final CommandClient client;
    private WifiProvisionManager wifiProvisionManager;

    private String detectedDeviceID;

    private volatile boolean isDetectedDeviceClaimed;
    private volatile boolean gotOwnershipInfo;
    private volatile boolean needToClaimDevice;


    DiscoverProcessWorker(CommandClient client, WifiProvisionManager wifiSetup) {
        this.client = client;
        this.wifiProvisionManager = wifiSetup;
    }



    public void doTheThing(InterfaceBindingSocketFactoryDev socketFactory) throws SetupStepException {
        // 1. get device ID
        if (!truthy(detectedDeviceID)) {
            try {
                DeviceIdCommand.Response response = client.sendCommandAndReturnResponse(
                        new DeviceIdCommand(), DeviceIdCommand.Response.class, socketFactory);
                detectedDeviceID = response.deviceIdHex.toLowerCase();
                wifiProvisionManager.mDeviceToBeSetUpId = detectedDeviceID;
                isDetectedDeviceClaimed = truthy(response.isClaimed);
            } catch (IOException e) {
                throw new SetupStepException("Unable to get the Device Id ", e);
            }
        }

        // 2. Get public key
        if (wifiProvisionManager.mPublicKey == null) {
            try {
                wifiProvisionManager.mPublicKey = getPublicKey(socketFactory);
            } catch (Crypto.CryptoException e) {
                throw new SetupStepException("Unable to get public key: ", e);

            } catch (IOException e) {
                throw new SetupStepException("Error while fetching public key: ", e);
            }
        }

        // 3. check ownership
        //
        // all cases:
        // (1) device not claimed `c=0` — device should also not be in list from API => mobile
        //      app assumes user is claiming
        // (2) device claimed `c=1` and already in list from API => mobile app does not ask
        //      user about taking ownership because device already belongs to this user
        // (3) device claimed `c=1` and NOT in the list from the API => mobile app asks whether
        //      use would like to take ownership
        if (!gotOwnershipInfo) {
            needToClaimDevice = false;

            // device was never claimed before - so we need to claim it anyways
            if (!isDetectedDeviceClaimed) {
                setClaimCode(socketFactory);
                needToClaimDevice = true;

            } else {
                boolean deviceClaimedByUser = false;
                gotOwnershipInfo = true;

                if (isDetectedDeviceClaimed && !deviceClaimedByUser) {
                    // This device is already claimed by someone else. Ask the user if we should
                    // change ownership to the current logged in user, and if so, set the claim code.

                    //change the ownership and set the claim code.

                    setClaimCode(socketFactory);

                } else {
                    // Success: no exception thrown, this part of the process is complete.
                    // Let the caller continue on with the setup process.
                    return;
                }
            }

        } else {
            if (needToClaimDevice) {
                setClaimCode(socketFactory);
            }
            // Success: no exception thrown, the part of the process is complete.  Let the caller
            // continue on with the setup process.
            return;
        }
    }

    private void setClaimCode(InterfaceBindingSocketFactoryDev socketFactory)
            throws SetupStepException {

        if(wifiProvisionManager.mClaimCode == null){
            throw new SetupStepException("Claim Code was not Set");
        }

        try {
            Log.d(TAG, "Setting claim code using code: " + wifiProvisionManager.mClaimCode);

            SetCommand.Response response = client.sendCommandAndReturnResponse(
                    new SetCommand("cc", StringUtils.remove(wifiProvisionManager.mClaimCode, "\\")),
                    SetCommand.Response.class, socketFactory);

            if (truthy(response.responseCode)) {
                // a non-zero response indicates an error, ala UNIX return codes
                throw new SetupStepException("Received non-zero return code from set command: "
                        + response.responseCode);
            }

            Log.d(TAG, "Successfully set claim code");

        } catch (IOException e) {
            throw new SetupStepException(e);
        }
    }

    private PublicKey getPublicKey(InterfaceBindingSocketFactoryDev socketFactory)
            throws Crypto.CryptoException, IOException {
        PublicKeyCommand.Response response = this.client.sendCommandAndReturnResponse(
                new PublicKeyCommand(), PublicKeyCommand.Response.class, socketFactory);

        return Crypto.readPublicKeyFromHexEncodedDerString(response.publicKey);
    }
}

