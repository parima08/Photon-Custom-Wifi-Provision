package com.ciklum.pigabstractionlayer.WifiProvisioning;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.util.SparseArray;

import com.ciklum.pigabstractionlayer.PiggyBank;
import com.ciklum.pigabstractionlayer.PiggyBankException;

import java.util.List;

import io.particle.android.sdk.devicesetup.SetupProcessException;
import io.particle.android.sdk.devicesetup.setupsteps.SetupStep;
import io.particle.android.sdk.devicesetup.setupsteps.SetupStepsRunnerTask;
import io.particle.android.sdk.devicesetup.setupsteps.StepProgress;

/**
 * This class extends SetupStepsRunnerTask (a particle class), which in return extends an AsyncTask.
 * It basically is used to run through the steps defined in buildSteps in ConnectToNetwork. The steps
 * in buildSteps send network information to the device. At the end, once the device succesfully goes
 * through provisioning, we claim the device against the particle cloud network using product
 * information. (In particle's implementation, claiming against a product is not possible).
 */

class ConnectingProcessWorkerTask extends SetupStepsRunnerTask {

    public static final String TAG = "ConnectingProcessWorker";

    private WifiProvisionManager wifiProvisionManager;
    private Context mContext;


    /**
     * Codes for error handling.
     */
    public static final int RESULT_SUCCESS = 1;
    public static final int RESULT_SUCCESS_UNKNOWN_OWNERSHIP = 2;
    public static final int RESULT_FAILURE_CLAIMING = 3;
    public static final int RESULT_FAILURE_CONFIGURE = 4;
    public static final int RESULT_FAILURE_NO_DISCONNECT = 5;
    public static final int RESULT_FAILURE_LOST_CONNECTION_TO_DEVICE = 6;
    private static final SparseArray<String> resultCodesToStringIds = new  SparseArray(6);

    static {
        resultCodesToStringIds.put(RESULT_SUCCESS, "Setup completed successfully!");

        resultCodesToStringIds.put(RESULT_SUCCESS_UNKNOWN_OWNERSHIP, "Setup was successful, but you're not" +
                "the primary owner so we can't check if the Photon connected to the Internet. If you see the" +
                "LED breathing cyan this means it worked! If not, please restart the setup process.");

        resultCodesToStringIds.put(RESULT_FAILURE_CLAIMING, "Setup process couldn't claim your device!");

        resultCodesToStringIds.put(RESULT_FAILURE_CONFIGURE, "Setup process couldn't configure the Wi-Fi credentials for your {device_name}");

        resultCodesToStringIds.put(RESULT_FAILURE_NO_DISCONNECT, "Setup process couldn't disconnect from the" +
                "device's Wi-Fi network. This is an internal problem with the device, so please try" +
                "running setup again after  putting it back in blinking blue listen mode");

        resultCodesToStringIds.put(RESULT_FAILURE_LOST_CONNECTION_TO_DEVICE, "Setup process lost connection to the device before being able to configure it");
    }

    ConnectingProcessWorkerTask(List<SetupStep> steps, int max, WifiProvisionManager wifiState, Context context) {
        super(steps, max);
        wifiProvisionManager = wifiState;
        mContext = context;
    }

    @Override
    protected void onProgressUpdate(StepProgress... values) {
        for (StepProgress progress : values) {
            Log.d(TAG, "Step has progressed " + progress.status);
        }
    }

    /**
     * Upon finishing wifi provisioning, a new piggybank object will be created and returned in the
     * successful callback. The device will also be claimed against the product.
     *
     * On failure, it will return an error based on the above defined codes.
     * @param error
     */

    @Override
    protected void onPostExecute(SetupProcessException error) {
        int resultCode;
        if (error == null) {
            if(PiggyBank.DEBUG){
                Log.d(TAG, "HUZZAH, VICTORY!");
            }
            final PiggyBank pig = wifiProvisionManager.createWifiProvisionPiggybank();
            if(PiggyBank.DEBUG) {
                Log.d(TAG, "Pig was just created");
            }

            ( (Activity) mContext).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    wifiProvisionManager.mCallback.onSuccess(pig);
                }
            });


        } else {
            resultCode = error.failedStep.getStepConfig().resultCode;
            Log.d(TAG, "There was an issue with Wifi Provisioning at step " + resultCode);
            Log.d(TAG, "The issue was " + resultCodesToStringIds.get(resultCode));

            // TODO: reset softAp here connection here!!!
            wifiProvisionManager.mCallback.onFailure(new PiggyBankException(resultCodesToStringIds.get(resultCode)));
        }

        Log.d(TAG, "Wifi Provision as ended");
    }

}